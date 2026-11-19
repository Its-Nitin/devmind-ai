import { Component, computed, input } from '@angular/core';
import { ProgressBar } from 'primeng/progressbar';
import { Job } from './explorer-api';

/** What a failed index job's error code means for the user; unknown codes fall back to the code itself. */
const FAILURES: Readonly<Record<string, string>> = {
  'ref-invalid': 'The ref is not a valid branch, tag or commit SHA.',
  'ref-not-found': 'No commit matches the ref.',
  'ref-ambiguous': 'The commit SHA prefix matches more than one commit.',
  'source-location-not-allowed': 'The source folder is outside the server’s allowed roots.',
  'source-location-not-found': 'The source repository does not exist.',
  'source-not-a-repository': 'The source folder is not a Git repository.',
  'source-url-not-allowed': 'The repository URL is not allowed.',
  'source-too-large': 'The repository is larger than the size limit.',
  'source-too-many-files': 'The commit has more files than the limit.',
  'source-timeout': 'Reading the repository took too long.',
  'source-unreachable': 'The repository could not be read.',
  'index-incomplete': 'Indexing finished with missing files or chunks.',
  'lease-expired': 'The indexing worker stopped before finishing.',
  'unexpected-error': 'Indexing failed with an unexpected error.',
};

/**
 * Presentational progress of an index job (FE-6): status, phase and `done / total`, or why it failed. Status is
 * always in words, not just colour (FE-11).
 */
@Component({
  selector: 'dm-job-progress',
  imports: [ProgressBar],
  template: `
    @let current = job();
    <div class="job" role="status" aria-live="polite">
      @switch (current.status) {
        @case ('QUEUED') {
          <span>Indexing is queued…</span>
          <p-progress-bar mode="indeterminate" [style]="{ height: '0.5rem' }" />
        }
        @case ('RUNNING') {
          <span>
            Indexing{{ current.progress.phase ? ' ' + current.progress.phase : '' }}: {{ current.progress.done }} /
            {{ current.progress.total }}
          </span>
          <p-progress-bar [value]="percent()" [showValue]="false" [style]="{ height: '0.5rem' }" />
        }
        @case ('SUCCEEDED') {
          <span>Indexing finished. The revision is ready to browse.</span>
        }
        @default {
          <span class="failed">Indexing failed: {{ failure() }}</span>
        }
      }
    </div>
  `,
  styles: `
    .job {
      display: grid;
      gap: 0.4rem;
      max-width: 36rem;
    }
    .failed {
      color: var(--p-red-600, #b00);
    }
  `,
})
export class JobProgress {
  readonly job = input.required<Job>();

  protected readonly percent = computed(() => {
    const { done, total } = this.job().progress;
    return total > 0 ? Math.round((done * 100) / total) : 0;
  });

  protected readonly failure = computed(() => {
    const code = this.job().errorCode;
    if (code === null || code === undefined) {
      return 'the job was cancelled.';
    }
    return FAILURES[code] ?? `error ${code}.`;
  });
}
