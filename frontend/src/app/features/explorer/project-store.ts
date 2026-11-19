import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { ExplorerApi, Job, Project, Revision } from './explorer-api';

/**
 * The project page's state (FE-3): the project, its revisions, and the index job being watched. Request failures are
 * already toasted by the error interceptor; the store keeps what the page needs for its states (FE-10).
 */
@Injectable()
export class ProjectStore {
  readonly #api = inject(ExplorerApi);
  readonly #destroyRef = inject(DestroyRef);

  readonly #project = signal<Project | null>(null);
  readonly #revisions = signal<readonly Revision[]>([]);
  readonly #loading = signal(false);
  readonly #error = signal<string | null>(null);
  readonly #job = signal<Job | null>(null);
  readonly #jobLost = signal(false);
  readonly #starting = signal(false);
  #projectId = '';
  #polling: Subscription | null = null;

  readonly project = this.#project.asReadonly();
  readonly revisions = this.#revisions.asReadonly();
  readonly loading = this.#loading.asReadonly();
  readonly error = this.#error.asReadonly();
  /** The latest state of the index job started from this page; `null` before one is started. */
  readonly job = this.#job.asReadonly();
  /** Whether polling the job failed, so its progress is no longer known. */
  readonly jobLost = this.#jobLost.asReadonly();
  readonly starting = this.#starting.asReadonly();

  async load(projectId: string): Promise<void> {
    this.#projectId = projectId;
    this.#loading.set(true);
    this.#error.set(null);
    try {
      const [project, revisions] = await Promise.all([
        this.#api.getProject(projectId),
        this.#api.listRevisions(projectId),
      ]);
      this.#project.set(project);
      this.#revisions.set(revisions.content);
    } catch {
      this.#error.set('This project could not be loaded.');
    } finally {
      this.#loading.set(false);
    }
  }

  /** Queues indexing of `ref` and starts watching the job; resolves `true` once it's queued. */
  async startIndex(ref: string): Promise<boolean> {
    this.#starting.set(true);
    try {
      const { jobId } = await this.#api.startIndex(this.#projectId, ref);
      this.#watch(jobId);
      return true;
    } catch {
      return false; // the toast says why (422 ref-not-found, 503 unreachable, ...)
    } finally {
      this.#starting.set(false);
    }
  }

  /** Polls the job until it ends; a success brings its revision into the list. */
  #watch(jobId: string): void {
    this.#polling?.unsubscribe();
    this.#job.set(null);
    this.#jobLost.set(false);
    this.#polling = this.#api
      .pollJob(jobId)
      .pipe(takeUntilDestroyed(this.#destroyRef))
      .subscribe({
        next: (job) => {
          this.#job.set(job);
          if (job.status === 'SUCCEEDED') {
            void this.#reloadRevisions();
          }
        },
        error: () => {
          this.#jobLost.set(true);
        },
      });
  }

  async #reloadRevisions(): Promise<void> {
    try {
      this.#revisions.set((await this.#api.listRevisions(this.#projectId)).content);
    } catch {
      // Toasted; the list keeps what it showed.
    }
  }
}
