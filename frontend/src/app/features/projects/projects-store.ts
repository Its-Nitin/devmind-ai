import { Injectable, computed, inject, signal } from '@angular/core';
import { CreateProjectRequest, Project, ProjectsApi } from './projects-api';

/**
 * The projects page's state as signals (FE-3). Failures are already toasted by the error interceptor; the store keeps
 * just enough to render the error state (FE-10) and report success to the caller.
 */
@Injectable()
export class ProjectsStore {
  readonly #api = inject(ProjectsApi);
  readonly #projects = signal<readonly Project[]>([]);
  readonly #totalCount = signal(0);
  readonly #loading = signal(false);
  readonly #error = signal<string | null>(null);
  readonly #saving = signal(false);

  readonly projects = this.#projects.asReadonly();
  readonly loading = this.#loading.asReadonly();
  readonly error = this.#error.asReadonly();
  readonly saving = this.#saving.asReadonly();
  /** How many projects exist beyond the loaded page; 0 for nearly everyone until the list gets paging. */
  readonly hiddenCount = computed(() => Math.max(0, this.#totalCount() - this.#projects().length));

  async load(): Promise<void> {
    this.#loading.set(true);
    this.#error.set(null);
    try {
      const page = await this.#api.listNewestFirst();
      this.#projects.set(page.content);
      this.#totalCount.set(page.page.totalElements);
    } catch {
      this.#error.set('Your projects could not be loaded.');
    } finally {
      this.#loading.set(false);
    }
  }

  /** Resolves `true` when the project was created; it then heads the list, matching the newest-first order. */
  async create(request: CreateProjectRequest): Promise<boolean> {
    this.#saving.set(true);
    try {
      const created = await this.#api.create(request);
      this.#projects.update((projects) => [created, ...projects]);
      this.#totalCount.update((count) => count + 1);
      return true;
    } catch {
      return false;
    } finally {
      this.#saving.set(false);
    }
  }
}
