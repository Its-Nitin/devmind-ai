import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Directory, ExplorerApi, FileSlice } from './explorer-api';
import { ExplorerStore, MAX_LINES_PER_READ, toFileLocation } from './explorer-store';

const SHA = 'a'.repeat(40);
const BASE = `/api/projects/p-1/revisions/${SHA}`;

function directory(path: string, entries: [string, 'DIRECTORY' | 'FILE'][]): Directory {
  return {
    commitSha: SHA,
    path,
    truncated: false,
    entries: entries.map(([name, type]) => ({ name, type, path: path === '' ? name : `${path}/${name}` })),
  };
}

function slice(path: string, from: number, to: number, lineCount: number): FileSlice {
  return { commitSha: SHA, path, language: 'java', lineCount, from, to, content: 'class A {}' };
}

describe('toFileLocation (deep links)', () => {
  it('maps path, from and to', () => {
    expect(toFileLocation('src/A.java', '10', '20')).toEqual({ path: 'src/A.java', from: 10, to: 20 });
    expect(toFileLocation('src/A.java', '10')).toEqual({ path: 'src/A.java', from: 10 });
    expect(toFileLocation('src/A.java')).toEqual({ path: 'src/A.java' });
  });

  it('ignores line numbers that are not positive integers, and a to before from', () => {
    expect(toFileLocation('a', 'x', '5')).toEqual({ path: 'a' });
    expect(toFileLocation('a', '0')).toEqual({ path: 'a' });
    expect(toFileLocation('a', '2.5')).toEqual({ path: 'a' });
    expect(toFileLocation('a', '9', '3')).toEqual({ path: 'a', from: 9 });
  });

  it('names no file without a path', () => {
    expect(toFileLocation(undefined, '1')).toBeNull();
    expect(toFileLocation('')).toBeNull();
  });
});

describe('ExplorerStore', () => {
  let store: ExplorerStore;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ExplorerApi, ExplorerStore],
    });
    store = TestBed.inject(ExplorerStore);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    backend.verify();
  });

  async function openRevision(): Promise<void> {
    const done = store.openRevision('p-1', SHA.slice(0, 7));
    backend
      .expectOne((r) => r.url.endsWith('/files') && r.params.get('path') === '')
      .flush(
        directory('', [
          ['src', 'DIRECTORY'],
          ['README.md', 'FILE'],
        ]),
      );
    await done;
  }

  describe('openRevision', () => {
    it('shows the root and the full SHA once the revision can be read', async () => {
      await openRevision();

      expect(store.state()).toBe('ready');
      expect(store.commitSha()).toBe(SHA);
      expect(store.nodes().map((n) => [n.label, n.leaf])).toEqual([
        ['src', false],
        ['README.md', true],
      ]);
    });

    it.each([
      [409, 'revision-not-ready', 'not-ready'],
      [409, 'revision-ambiguous', 'ambiguous'],
      [404, 'revision-not-found', 'not-found'],
    ])('turns a %i %s into the %s state', async (status, code, state) => {
      const done = store.openRevision('p-1', SHA);
      backend
        .expectOne((r) => r.url.endsWith('/files'))
        .flush(
          { title: 'x', detail: 'x', code, status, type: `https://devmind.dev/problems/${code}`, instance: '/x' },
          { status, statusText: 'x' },
        );
      await done;

      expect(store.state()).toBe(state);
    });
  });

  describe('expand', () => {
    it('loads a directory once, the first time it is expanded', async () => {
      await openRevision();
      const src = store.nodes()[0];
      if (src === undefined) {
        throw new Error('no src node');
      }

      const done = store.expand(src);
      expect(src.loading).toBe(true);
      backend
        .expectOne((r) => r.url === `${BASE.replace(SHA, SHA.slice(0, 7))}/files` && r.params.get('path') === 'src')
        .flush(directory('src', [['App.java', 'FILE']]));
      await done;

      expect(src.loading).toBe(false);
      expect(src.children?.map((n) => n.key)).toEqual(['src/App.java']);
      await store.expand(src);
      backend.expectNone((r) => r.url.endsWith('/files'));
    });
  });

  describe('showFile (deep-link params → viewer inputs)', () => {
    it('reads a file from its start and highlights the linked lines', async () => {
      await openRevision();

      const done = store.showFile({ path: 'src/App.java', from: 12, to: 18 });
      const request = backend.expectOne((r) => r.url.endsWith('/file'));
      expect(request.request.params.get('path')).toBe('src/App.java');
      expect(request.request.params.has('from')).toBe(false);
      request.flush(slice('src/App.java', 1, 40, 40));
      await done;

      expect(store.file()?.path).toBe('src/App.java');
      expect(store.firstLine()).toBe(1);
      expect(store.highlight()).toEqual({ from: 12, to: 18 });
    });

    it('reads a long file from just above a range past the first read', async () => {
      await openRevision();

      const done = store.showFile({ path: 'Big.java', from: MAX_LINES_PER_READ + 500 });
      const request = backend.expectOne((r) => r.url.endsWith('/file'));
      expect(request.request.params.get('from')).toBe(String(MAX_LINES_PER_READ + 400));
      request.flush(slice('Big.java', MAX_LINES_PER_READ + 400, MAX_LINES_PER_READ + 2399, 5000));
      await done;

      expect(store.firstLine()).toBe(MAX_LINES_PER_READ + 400);
      expect(store.highlight()).toEqual({ from: MAX_LINES_PER_READ + 500, to: MAX_LINES_PER_READ + 500 });
    });

    it('keeps the file of the latest request when answers arrive out of order', async () => {
      await openRevision();

      const first = store.showFile({ path: 'A.java' });
      const second = store.showFile({ path: 'B.java' });
      const [a, b] = backend.match((r) => r.url.endsWith('/file'));
      b?.flush(slice('B.java', 1, 1, 1));
      a?.flush(slice('A.java', 1, 1, 1));
      await Promise.all([first, second]);

      expect(store.file()?.path).toBe('B.java');
      expect(store.highlight()).toBeNull();
    });
  });

  describe('search', () => {
    it('searches the trimmed query and clears results for a blank one', async () => {
      await openRevision();

      const done = store.search('  Owner ');
      const request = backend.expectOne((r) => r.url.endsWith('/symbols'));
      expect(request.request.params.get('q')).toBe('Owner');
      request.flush({
        commitSha: SHA,
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
        content: [
          {
            symbolId: 's-1',
            kind: 'CLASS',
            name: 'Owner',
            qualifiedName: 'com.acme.Owner',
            signature: 'public class Owner',
            path: 'src/Owner.java',
            startLine: 3,
            endLine: 13,
          },
        ],
      });
      await done;
      expect(store.results().map((s) => s.qualifiedName)).toEqual(['com.acme.Owner']);

      await store.search('   ');
      expect(store.results()).toEqual([]);
      backend.expectNone((r) => r.url.endsWith('/symbols'));
    });
  });
});
