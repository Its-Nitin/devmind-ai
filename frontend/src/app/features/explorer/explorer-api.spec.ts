import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ExplorerApi, JOB_POLL_INTERVAL_MS, Job, JobStatus } from './explorer-api';

function job(status: JobStatus, done = 0): Job {
  return {
    jobId: 'j-1',
    type: 'INDEX',
    status,
    progress: { phase: 'files', done, total: 10 },
    attempts: 1,
    errorCode: status === 'FAILED' ? 'ref-not-found' : null,
    createdAt: '2026-11-19T09:00:00Z',
    updatedAt: '2026-11-19T09:00:01Z',
  };
}

describe('ExplorerApi.pollJob', () => {
  let api: ExplorerApi;
  let backend: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), ExplorerApi] });
    api = TestBed.inject(ExplorerApi);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  function answerNextPoll(answer: Job): void {
    backend.expectOne('/api/jobs/j-1').flush(answer);
  }

  it.each<JobStatus>(['SUCCEEDED', 'FAILED', 'CANCELLED'])(
    'polls until the job is %s, emits that state last, then stops',
    (terminal) => {
      const seen: JobStatus[] = [];
      let completed = false;
      api.pollJob('j-1').subscribe({ next: (j) => seen.push(j.status), complete: () => (completed = true) });

      vi.advanceTimersByTime(0);
      answerNextPoll(job('QUEUED'));
      vi.advanceTimersByTime(JOB_POLL_INTERVAL_MS);
      answerNextPoll(job('RUNNING', 4));
      vi.advanceTimersByTime(JOB_POLL_INTERVAL_MS);
      answerNextPoll(job(terminal, 10));

      expect(seen).toEqual(['QUEUED', 'RUNNING', terminal]);
      expect(completed).toBe(true);
      vi.advanceTimersByTime(JOB_POLL_INTERVAL_MS * 3);
      backend.expectNone('/api/jobs/j-1'); // no request after the terminal state
    },
  );

  it('ends the stream with the error when a poll fails', () => {
    let failed = false;
    api.pollJob('j-1').subscribe({ error: () => (failed = true) });

    vi.advanceTimersByTime(0);
    backend.expectOne('/api/jobs/j-1').flush(null, { status: 500, statusText: 'Server Error' });

    expect(failed).toBe(true);
    vi.advanceTimersByTime(JOB_POLL_INTERVAL_MS * 2);
    backend.expectNone('/api/jobs/j-1');
  });
});
