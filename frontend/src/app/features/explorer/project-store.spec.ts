import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ExplorerApi, JOB_POLL_INTERVAL_MS, Job, JobStatus, Project, Revision, RevisionPage } from './explorer-api';
import { ProjectStore } from './project-store';

const PROJECT: Project = {
  projectId: 'p-1',
  name: 'petclinic',
  sourceType: 'LOCAL',
  sourceLocation: '/targets/petclinic',
  createdAt: '2026-11-19T09:00:00Z',
  defaultBranch: null,
};

function revisions(...shas: string[]): RevisionPage {
  const content: Revision[] = shas.map((sha) => ({
    commitSha: sha,
    status: 'READY',
    createdAt: '2026-11-19T09:00:00Z',
    readyAt: '2026-11-19T09:01:00Z',
    stats: null,
  }));
  return { content, page: { number: 0, size: 100, totalElements: content.length, totalPages: 1 } };
}

function job(status: JobStatus): Job {
  return {
    jobId: 'j-1',
    type: 'INDEX',
    status,
    progress: { phase: null, done: 0, total: 0 },
    attempts: 1,
    errorCode: null,
    createdAt: '2026-11-19T09:00:00Z',
    updatedAt: '2026-11-19T09:00:00Z',
  };
}

describe('ProjectStore', () => {
  let store: ProjectStore;
  let backend: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ExplorerApi, ProjectStore],
    });
    store = TestBed.inject(ProjectStore);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  async function load(): Promise<void> {
    const done = store.load('p-1');
    backend.expectOne('/api/projects/p-1').flush(PROJECT);
    backend.expectOne((r) => r.url === '/api/projects/p-1/revisions').flush(revisions());
    await done;
  }

  it('loads the project and its revisions', async () => {
    await load();

    expect(store.project()?.name).toBe('petclinic');
    expect(store.revisions()).toEqual([]);
    expect(store.error()).toBeNull();
  });

  it('starts an index job, follows it, and lists the new revision once it succeeds', async () => {
    await load();

    const started = store.startIndex('main');
    const request = backend.expectOne('/api/projects/p-1/index-jobs');
    expect(request.request.body).toEqual({ ref: 'main' });
    request.flush({ jobId: 'j-1' }, { status: 202, statusText: 'Accepted' });
    expect(await started).toBe(true);

    vi.advanceTimersByTime(0);
    backend.expectOne('/api/jobs/j-1').flush(job('RUNNING'));
    expect(store.job()?.status).toBe('RUNNING');

    vi.advanceTimersByTime(JOB_POLL_INTERVAL_MS);
    backend.expectOne('/api/jobs/j-1').flush(job('SUCCEEDED'));
    backend.expectOne((r) => r.url === '/api/projects/p-1/revisions').flush(revisions('b'.repeat(40)));
    await vi.waitFor(() => {
      expect(store.revisions().map((r) => r.commitSha)).toEqual(['b'.repeat(40)]);
    });
    expect(store.job()?.status).toBe('SUCCEEDED');
  });

  it('reports a rejected ref as not started', async () => {
    await load();

    const started = store.startIndex('nope');
    backend
      .expectOne('/api/projects/p-1/index-jobs')
      .flush({ code: 'ref-not-found' }, { status: 422, statusText: 'Unprocessable Content' });

    expect(await started).toBe(false);
    expect(store.job()).toBeNull();
  });
});
