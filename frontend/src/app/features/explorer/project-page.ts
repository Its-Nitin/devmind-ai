import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { ButtonDirective } from 'primeng/button';
import { ProgressSpinner } from 'primeng/progressspinner';
import { IndexDialog } from './index-dialog';
import { JobProgress } from './job-progress';
import { ProjectStore } from './project-store';
import { RevisionTable } from './revision-table';

/** `/projects/:projectId`: the project, its revisions, and indexing (FE-6: talks to the store, children present). */
@Component({
  selector: 'dm-project-page',
  imports: [ButtonDirective, ProgressSpinner, IndexDialog, JobProgress, RevisionTable],
  template: `
    @if (store.loading()) {
      <div class="state" role="status">
        <p-progress-spinner strokeWidth="6" [style]="{ width: '1.5rem', height: '1.5rem' }" aria-hidden="true" />
        <span>Loading the project…</span>
      </div>
    } @else if (store.error(); as error) {
      <div class="state" role="alert">
        <span>{{ error }}</span>
        <button pButton type="button" [outlined]="true" (click)="reload()">Try again</button>
      </div>
    } @else if (store.project(); as project) {
      <section aria-labelledby="project-heading">
        <div class="toolbar">
          <h2 id="project-heading">{{ project.name }}</h2>
          <button pButton type="button" (click)="indexDialogOpen.set(true)">Index</button>
        </div>
        <p class="source">
          {{ project.sourceType === 'LOCAL' ? 'Local folder' : 'Public Git' }}:
          <code>{{ project.sourceLocation }}</code>
        </p>

        @if (store.job(); as job) {
          <dm-job-progress [job]="job" />
        } @else if (store.jobLost()) {
          <p role="status">Lost track of the index job. Reload the page to see the revisions.</p>
        }

        <h3>Revisions</h3>
        @if (store.revisions().length === 0) {
          <p class="state">Nothing indexed yet. Use “Index” to index a branch, tag or commit.</p>
        } @else {
          <dm-revision-table [projectId]="projectId()" [revisions]="store.revisions()" />
        }
      </section>

      <dm-index-dialog
        [(open)]="indexDialogOpen"
        [starting]="store.starting()"
        [defaultRef]="project.defaultBranch ?? 'main'"
        (index)="startIndex($event)"
      />
    }
  `,
  styles: `
    section {
      display: grid;
      gap: 1rem;
    }
    .toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .state {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }
    .source code {
      word-break: break-all;
    }
  `,
})
export class ProjectPage {
  /** Bound from the route (`withComponentInputBinding`). */
  readonly projectId = input.required<string>();

  protected readonly store = inject(ProjectStore);
  protected readonly indexDialogOpen = signal(false);

  constructor() {
    effect(() => {
      const projectId = this.projectId();
      untracked(() => void this.store.load(projectId));
    });
  }

  protected reload(): void {
    void this.store.load(this.projectId());
  }

  protected async startIndex(ref: string): Promise<void> {
    if (await this.store.startIndex(ref)) {
      this.indexDialogOpen.set(false);
    }
  }
}
