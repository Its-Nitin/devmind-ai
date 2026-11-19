import { Component, computed, effect, inject, input, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ProgressSpinner } from 'primeng/progressspinner';
import { CodeViewer } from '../../shared/code-viewer/code-viewer';
import { SymbolHit } from './explorer-api';
import { ExplorerStore, FileNode, toFileLocation } from './explorer-store';
import { FileTree } from './file-tree';
import { SymbolSearch } from './symbol-search';

/**
 * `/projects/:projectId/revisions/:sha`: file tree and symbol search on the left, the code on the right. The open file
 * and its highlighted lines live in the URL (`?path=…&from=…&to=…`), so clicks, deep links and M4's citation chips
 * all work the same way: they navigate, and the page shows what the URL names.
 */
@Component({
  selector: 'dm-explorer-page',
  imports: [RouterLink, ProgressSpinner, CodeViewer, FileTree, SymbolSearch],
  template: `
    <nav class="crumbs" aria-label="Breadcrumb">
      <a [routerLink]="['/projects', projectId()]">Project</a>
      <span aria-hidden="true">/</span>
      <code>{{ (store.commitSha() || sha()).slice(0, 7) }}</code>
    </nav>

    @switch (store.state()) {
      @case ('loading') {
        <div class="state" role="status">
          <p-progress-spinner strokeWidth="6" [style]="{ width: '1.5rem', height: '1.5rem' }" aria-hidden="true" />
          <span>Opening the revision…</span>
        </div>
      }
      @case ('not-ready') {
        <p class="state" role="status">
          This revision is still being indexed, or its indexing failed. Check the
          <a [routerLink]="['/projects', projectId()]">project page</a>.
        </p>
      }
      @case ('ambiguous') {
        <p class="state" role="alert">This short SHA matches more than one revision. Use the full commit SHA.</p>
      }
      @case ('not-found') {
        <p class="state" role="alert">This revision isn’t indexed in this project.</p>
      }
      @case ('error') {
        <div class="state" role="alert">
          <span>The revision could not be opened.</span>
          <button type="button" (click)="retry()">Try again</button>
        </div>
      }
      @default {
        <div class="explorer">
          <aside class="side">
            <dm-symbol-search
              [results]="store.results()"
              [searching]="store.searching()"
              [searched]="store.query() !== ''"
              (query)="store.search($event)"
              (selectSymbol)="openSymbol($event)"
            />
            <dm-file-tree [nodes]="store.nodes()" (expand)="expand($event)" (openFile)="openFile($event)" />
          </aside>
          <section class="code" aria-label="File">
            @if (store.file(); as file) {
              <h2 class="path">
                <code>{{ file.path }}</code>
                <span class="lines">{{ file.lineCount }} lines</span>
              </h2>
              @if (file.lineCount > file.to - file.from + 1) {
                <p class="hint">Showing lines {{ file.from }}–{{ file.to }} of {{ file.lineCount }}.</p>
              }
              <dm-code-viewer
                [content]="file.content"
                [language]="file.language"
                [firstLine]="store.firstLine()"
                [highlight]="store.highlight()"
                [label]="'Source of ' + file.path"
              />
            } @else if (store.fileLoading()) {
              <p class="state" role="status">Loading the file…</p>
            } @else {
              <p class="state">Pick a file in the tree, or search for a symbol.</p>
            }
          </section>
        </div>
      }
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: 0.75rem;
    }
    .crumbs {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    .state {
      display: flex;
      gap: 0.75rem;
      align-items: center;
    }
    .explorer {
      display: grid;
      grid-template-columns: minmax(16rem, 24rem) 1fr;
      gap: 1rem;
      min-height: 70vh;
    }
    .side {
      display: grid;
      align-content: start;
      gap: 1rem;
      overflow: auto;
      max-height: 80vh;
    }
    .code {
      display: grid;
      grid-template-rows: auto auto 1fr;
      min-width: 0;
      height: 80vh;
    }
    .path {
      font-size: 1rem;
      margin: 0 0 0.5rem;
      display: flex;
      gap: 0.75rem;
      align-items: baseline;
      word-break: break-all;
    }
    .lines,
    .hint {
      font-size: 0.85rem;
      opacity: 0.75;
      margin: 0 0 0.5rem;
    }
  `,
})
export class ExplorerPage {
  // Route and query parameters, bound by the router (withComponentInputBinding).
  readonly projectId = input.required<string>();
  readonly sha = input.required<string>();
  readonly path = input<string>();
  readonly from = input<string>();
  readonly to = input<string>();

  protected readonly store = inject(ExplorerStore);
  readonly #router = inject(Router);

  /** The file and lines the URL names, or `null`. */
  readonly location = computed(() => toFileLocation(this.path(), this.from(), this.to()));

  constructor() {
    effect(() => {
      const projectId = this.projectId();
      const sha = this.sha();
      untracked(() => void this.store.openRevision(projectId, sha));
    });
    effect(() => {
      const location = this.location();
      if (this.store.state() === 'ready' && location !== null) {
        untracked(() => void this.store.showFile(location));
      }
    });
  }

  protected retry(): void {
    void this.store.openRevision(this.projectId(), this.sha());
  }

  protected expand(node: FileNode): void {
    void this.store.expand(node);
  }

  protected openFile(path: string): void {
    void this.#router.navigate([], { queryParams: { path } });
  }

  protected openSymbol(symbol: SymbolHit): void {
    void this.#router.navigate([], {
      queryParams: { path: symbol.path, from: symbol.startLine, to: symbol.endLine },
    });
  }
}
