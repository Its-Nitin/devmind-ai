import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import type { TreeNode } from 'primeng/api';
import { problemOf } from '../../core/http/problem';
import type { LineRange } from '../../shared/code-viewer/code-viewer';
import { DirectoryEntry, ExplorerApi, FileSlice, SymbolHit } from './explorer-api';

/** Lines one file read returns at most (`RevisionReadService.MAX_LINES`). */
export const MAX_LINES_PER_READ = 2000;

/** How far above a deep-linked range a read of a long file starts, so the range has context. */
const CONTEXT_LINES = 100;

/** Whether the revision can be browsed, and if not, why. */
export type RevisionState = 'loading' | 'ready' | 'not-ready' | 'ambiguous' | 'not-found' | 'error';

/** A file to show and the lines to highlight, as a deep link names them (`?path=…&from=…&to=…`). */
export interface FileLocation {
  readonly path: string;
  readonly from?: number;
  readonly to?: number;
}

/** Parses deep-link query parameters; line numbers that aren't positive integers are ignored. */
export function toFileLocation(path: string | undefined, from?: string, to?: string): FileLocation | null {
  if (path === undefined || path === '') {
    return null;
  }
  const first = positive(from);
  const last = positive(to);
  return {
    path,
    ...(first === undefined ? {} : { from: first }),
    ...(last === undefined || first === undefined || last < first ? {} : { to: last }),
  };
}

function positive(value: string | undefined): number | undefined {
  const number = value === undefined ? NaN : Number(value);
  return Number.isInteger(number) && number > 0 ? number : undefined;
}

export type FileNode = TreeNode<DirectoryEntry>;

/**
 * The explorer's state (FE-3): the revision's readability, the file tree (loaded a directory at a time), the open file
 * with its highlight, and symbol search results.
 */
@Injectable()
export class ExplorerStore {
  readonly #api = inject(ExplorerApi);

  readonly #state = signal<RevisionState>('loading');
  readonly #commitSha = signal('');
  readonly #nodes = signal<FileNode[]>([]);
  readonly #file = signal<FileSlice | null>(null);
  readonly #fileLoading = signal(false);
  readonly #highlight = signal<LineRange | null>(null);
  readonly #results = signal<readonly SymbolHit[]>([]);
  readonly #searching = signal(false);
  readonly #query = signal('');
  #projectId = '';
  #sha = '';
  #fileRequest = 0;
  #searchRequest = 0;

  readonly state = this.#state.asReadonly();
  /** The full SHA, once the server has resolved the one in the URL. */
  readonly commitSha = this.#commitSha.asReadonly();
  readonly nodes = this.#nodes.asReadonly();
  readonly file = this.#file.asReadonly();
  readonly fileLoading = this.#fileLoading.asReadonly();
  readonly highlight = this.#highlight.asReadonly();
  readonly results = this.#results.asReadonly();
  readonly searching = this.#searching.asReadonly();
  readonly query = this.#query.asReadonly();
  /** Line numbering for the viewer: the open slice's first line. */
  readonly firstLine = computed(() => Math.max(1, this.#file()?.from ?? 1));

  /** Opens a revision: checks it can be read by listing its root. */
  async openRevision(projectId: string, sha: string): Promise<void> {
    this.#projectId = projectId;
    this.#sha = sha;
    this.#state.set('loading');
    this.#nodes.set([]);
    this.#file.set(null);
    this.#highlight.set(null);
    this.#results.set([]);
    try {
      const root = await this.#api.listFiles(projectId, sha, '');
      this.#commitSha.set(root.commitSha);
      this.#nodes.set(root.entries.map(toNode));
      this.#state.set('ready');
    } catch (error) {
      this.#state.set(revisionState(error));
    }
  }

  /** Loads a directory's children the first time it's expanded. */
  async expand(node: FileNode): Promise<void> {
    const entry = node.data;
    if (entry?.type !== 'DIRECTORY' || node.children !== undefined) {
      return;
    }
    node.loading = true;
    this.#refreshTree();
    try {
      const directory = await this.#api.listFiles(this.#projectId, this.#sha, entry.path);
      node.children = directory.entries.map(toNode);
    } catch {
      node.expanded = false; // toasted; collapse, so expanding again retries
    } finally {
      node.loading = false;
      this.#refreshTree();
    }
  }

  /**
   * Shows a file, highlighting `from`–`to` (a single line if only `from` is given). A long file is read from a little
   * above the range, so the range is within the slice; otherwise from its first line.
   */
  async showFile(location: FileLocation): Promise<void> {
    const request = ++this.#fileRequest;
    const from = location.from;
    const start = from !== undefined && from > MAX_LINES_PER_READ ? Math.max(1, from - CONTEXT_LINES) : undefined;
    this.#fileLoading.set(true);
    try {
      const slice = await this.#api.readFile(this.#projectId, this.#sha, location.path, start);
      if (request !== this.#fileRequest) {
        return; // a later click won
      }
      this.#file.set(slice);
      this.#highlight.set(from === undefined ? null : { from, to: location.to ?? from });
    } catch {
      if (request === this.#fileRequest) {
        this.#file.set(null); // toasted (404 file-not-found, 422 line-range-invalid)
        this.#highlight.set(null);
      }
    } finally {
      if (request === this.#fileRequest) {
        this.#fileLoading.set(false);
      }
    }
  }

  /** Searches symbols by name; a blank query clears the results. */
  async search(query: string): Promise<void> {
    const request = ++this.#searchRequest;
    const q = query.trim();
    this.#query.set(q);
    if (q === '') {
      this.#results.set([]);
      return;
    }
    this.#searching.set(true);
    try {
      const results = await this.#api.searchSymbols(this.#projectId, this.#sha, q);
      if (request === this.#searchRequest) {
        this.#results.set(results.content);
      }
    } catch {
      if (request === this.#searchRequest) {
        this.#results.set([]);
      }
    } finally {
      if (request === this.#searchRequest) {
        this.#searching.set(false);
      }
    }
  }

  /** PrimeNG mutates and reads the nodes in place; a new array makes the signal notify. */
  #refreshTree(): void {
    this.#nodes.update((nodes) => [...nodes]);
  }
}

function toNode(entry: DirectoryEntry): FileNode {
  const directory = entry.type === 'DIRECTORY';
  return {
    key: entry.path,
    label: entry.name,
    data: entry,
    leaf: !directory,
    icon: directory ? 'pi pi-folder' : 'pi pi-file',
  };
}

function revisionState(error: unknown): RevisionState {
  if (!(error instanceof HttpErrorResponse)) {
    return 'error';
  }
  const code = problemOf(error)?.code;
  if (code === 'revision-not-ready') {
    return 'not-ready';
  }
  if (code === 'revision-ambiguous') {
    return 'ambiguous';
  }
  return error.status === 404 ? 'not-found' : 'error';
}
