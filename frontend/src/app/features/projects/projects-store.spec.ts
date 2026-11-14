import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CreateProjectRequest, Project, ProjectPage, ProjectsApi } from './projects-api';
import { ProjectsStore } from './projects-store';

function project(name: string, projectId: string): Project {
  return {
    projectId,
    name,
    sourceType: 'LOCAL',
    sourceLocation: `/srv/code/${name}`,
    createdAt: '2026-11-14T09:00:00Z',
    defaultBranch: null,
  };
}

function page(content: Project[], totalElements = content.length): ProjectPage {
  return { content, page: { number: 0, size: 100, totalElements, totalPages: 1 } };
}

describe('ProjectsStore', () => {
  let store: ProjectsStore;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ProjectsApi, ProjectsStore],
    });
    store = TestBed.inject(ProjectsStore);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
  });

  describe('load', () => {
    it('asks for the newest projects first and exposes them', async () => {
      const done = store.load();
      expect(store.loading()).toBe(true);

      const request = backend.expectOne((r) => r.url === '/api/projects');
      expect(request.request.params.get('sort')).toBe('createdAt,desc');
      expect(request.request.params.get('size')).toBe('100');
      request.flush(page([project('petclinic', 'p-1')]));
      await done;

      expect(store.loading()).toBe(false);
      expect(store.error()).toBeNull();
      expect(store.projects().map((p) => p.name)).toEqual(['petclinic']);
      expect(store.hiddenCount()).toBe(0);
    });

    it('counts projects beyond the first page', async () => {
      const done = store.load();
      backend.expectOne((r) => r.url === '/api/projects').flush(page([project('a', 'p-1')], 101));
      await done;

      expect(store.hiddenCount()).toBe(100);
    });

    it('exposes an error and clears loading when the request fails', async () => {
      const done = store.load();
      backend.expectOne((r) => r.url === '/api/projects').flush(null, { status: 500, statusText: 'Server Error' });
      await done;

      expect(store.loading()).toBe(false);
      expect(store.error()).toBe('Your projects could not be loaded.');
      expect(store.projects()).toEqual([]);
    });
  });

  describe('create', () => {
    const request: CreateProjectRequest = { name: 'devmind', sourceType: 'LOCAL', sourceLocation: '/srv/code/devmind' };

    it('posts the request and puts the new project first', async () => {
      const load = store.load();
      backend.expectOne((r) => r.url === '/api/projects').flush(page([project('older', 'p-1')]));
      await load;

      const created = store.create(request);
      expect(store.saving()).toBe(true);
      const post = backend.expectOne({ method: 'POST', url: '/api/projects' });
      expect(post.request.body).toEqual(request);
      post.flush(project('devmind', 'p-2'), { status: 201, statusText: 'Created' });

      expect(await created).toBe(true);
      expect(store.saving()).toBe(false);
      expect(store.projects().map((p) => p.name)).toEqual(['devmind', 'older']);
    });

    it('reports failure and leaves the list unchanged', async () => {
      const created = store.create(request);
      backend
        .expectOne({ method: 'POST', url: '/api/projects' })
        .flush({ code: 'project-name-taken' }, { status: 409, statusText: 'Conflict' });

      expect(await created).toBe(false);
      expect(store.saving()).toBe(false);
      expect(store.projects()).toEqual([]);
    });
  });
});
