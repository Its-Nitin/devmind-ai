import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom, switchMap, takeWhile, timer } from 'rxjs';
import type { components } from '../../core/api/schema';
import { LOCALLY_HANDLED_STATUSES } from '../../core/http/error-interceptor';

export type Project = components['schemas']['ProjectResponse'];
export type Revision = components['schemas']['RevisionResponse'];
export type RevisionPage = components['schemas']['PagedModelRevisionResponse'];
export type RevisionStats = components['schemas']['RevisionStatsResponse'];
export type IndexJob = components['schemas']['IndexJobResponse'];
export type Job = components['schemas']['JobResponse'];
export type JobStatus = Job['status'];
export type Directory = components['schemas']['DirectoryResponse'];
export type DirectoryEntry = components['schemas']['EntryResponse'];
export type FileSlice = components['schemas']['FileSliceResponse'];
export type SymbolSearch = components['schemas']['SymbolSearchResponse'];
export type SymbolHit = components['schemas']['SymbolResponse'];

/** How often a running job is polled. */
export const JOB_POLL_INTERVAL_MS = 1500;

/** A job in one of these states won't change again. */
export function isTerminal(status: JobStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED';
}

/**
 * Statuses a revision read answers that the explorer shows as a state of its own (a revision not READY yet, or not
 * found), so the error interceptor doesn't also toast them.
 */
const REVISION_STATES = new HttpContext().set(LOCALLY_HANDLED_STATUSES, [404, 409]);

/** The explorer's endpoints (FE-8): projects, revisions, index jobs, files and symbols, typed from the contract (FE-7). */
@Injectable()
export class ExplorerApi {
  readonly #http = inject(HttpClient);

  getProject(projectId: string): Promise<Project> {
    return firstValueFrom(this.#http.get<Project>(`/api/projects/${projectId}`));
  }

  /** The newest revisions first; the first page covers every project the explorer is meant for. */
  listRevisions(projectId: string): Promise<RevisionPage> {
    return firstValueFrom(
      this.#http.get<RevisionPage>(`/api/projects/${projectId}/revisions`, { params: { size: 100 } }),
    );
  }

  /** Queues indexing of `ref`; asking again for the same commit returns the same job. */
  startIndex(projectId: string, ref: string): Promise<IndexJob> {
    return firstValueFrom(this.#http.post<IndexJob>(`/api/projects/${projectId}/index-jobs`, { ref }));
  }

  /**
   * The job, now and every {@link JOB_POLL_INTERVAL_MS} until it ends; the terminal state is the last value. A
   * failed request ends the stream with that error.
   */
  pollJob(jobId: string): Observable<Job> {
    return timer(0, JOB_POLL_INTERVAL_MS).pipe(
      switchMap(() => this.#http.get<Job>(`/api/jobs/${jobId}`)),
      takeWhile((job) => !isTerminal(job.status), true),
    );
  }

  /** A directory's children; `''` for the root. A revision that isn't readable answers 404 or 409 untoasted. */
  listFiles(projectId: string, sha: string, path: string): Promise<Directory> {
    return firstValueFrom(
      this.#http.get<Directory>(revisionUrl(projectId, sha, 'files'), {
        params: { path },
        context: REVISION_STATES,
      }),
    );
  }

  /** Lines of a file: from `from` (default 1) to the end, within the per-call limit. */
  readFile(projectId: string, sha: string, path: string, from?: number): Promise<FileSlice> {
    let params = new HttpParams().set('path', path);
    if (from !== undefined) {
      params = params.set('from', from);
    }
    return firstValueFrom(this.#http.get<FileSlice>(revisionUrl(projectId, sha, 'file'), { params }));
  }

  searchSymbols(projectId: string, sha: string, q: string): Promise<SymbolSearch> {
    return firstValueFrom(
      this.#http.get<SymbolSearch>(revisionUrl(projectId, sha, 'symbols'), { params: { q, size: 20 } }),
    );
  }
}

function revisionUrl(projectId: string, sha: string, resource: string): string {
  return `/api/projects/${projectId}/revisions/${sha}/${resource}`;
}
