import type { MockInstance } from 'vitest';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ErrorNotifier } from '../http/error-notifier';
import { errorInterceptor } from '../http/error-interceptor';
import { User } from './auth-api';
import { AuthStore } from './auth-store';

const demoUser: User = {
  userId: '0b6f2c1e-4a8d-4f3a-9c55-2d7e8f1a0b3c',
  email: 'demo@devmind.local',
  displayName: 'Demo',
};
const credentials = { email: 'demo@devmind.local', password: 'correct horse' };

/** Runs through the real error interceptor, so these tests also prove the auth 401s are handled locally. */
describe('AuthStore', () => {
  let store: AuthStore;
  let backend: HttpTestingController;
  let navigate: MockInstance<Router['navigate']>;
  const notify = vi.fn<ErrorNotifier['notify']>();

  beforeEach(() => {
    notify.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        { provide: ErrorNotifier, useValue: { notify } },
      ],
    });
    store = TestBed.inject(AuthStore);
    backend = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    backend.verify();
  });

  /** Lets the store's pending `await` reach its next request before the test looks for it. */
  const settle = () => new Promise<void>((resolve) => setTimeout(resolve));

  describe('logIn', () => {
    it('stores the user when the credentials are right', async () => {
      const result = store.logIn(credentials);
      const request = backend.expectOne('/api/auth/login');
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual(credentials);
      request.flush(demoUser);

      expect(await result).toBe('signed-in');
      expect(store.user()).toEqual(demoUser);
      expect(store.isAuthenticated()).toBe(true);
    });

    it('reports invalid credentials without redirecting or toasting', async () => {
      const result = store.logIn({ ...credentials, password: 'wrong' });
      backend
        .expectOne('/api/auth/login')
        .flush({ code: 'invalid-credentials' }, { status: 401, statusText: 'Unauthorized' });

      expect(await result).toBe('invalid-credentials');
      expect(store.isAuthenticated()).toBe(false);
      expect(navigate).not.toHaveBeenCalled();
      expect(notify).not.toHaveBeenCalled();
    });

    it('reports any other failure as failed', async () => {
      const result = store.logIn(credentials);
      backend.expectOne('/api/auth/login').flush(null, { status: 500, statusText: 'Server Error' });

      expect(await result).toBe('failed');
      expect(store.isAuthenticated()).toBe(false);
    });
  });

  describe('initialize', () => {
    it('fetches a CSRF token, then restores an existing session', async () => {
      const done = store.initialize();
      backend.expectOne('/api/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      backend.expectOne('/api/auth/me').flush(demoUser);
      await done;

      expect(store.user()).toEqual(demoUser);
    });

    it('treats 401 from /me as signed out, without redirecting', async () => {
      const done = store.initialize();
      backend.expectOne('/api/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
      await settle();
      backend.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });
      await done;

      expect(store.isAuthenticated()).toBe(false);
      expect(navigate).not.toHaveBeenCalled();
    });

    it('still resolves when the server is down', async () => {
      const done = store.initialize();
      backend.expectOne('/api/auth/csrf').error(new ProgressEvent('error'));

      await expect(done).resolves.toBeUndefined();
      expect(store.isAuthenticated()).toBe(false);
    });
  });

  it('logOut forgets the user and fetches a fresh CSRF token', async () => {
    const login = store.logIn(credentials);
    backend.expectOne('/api/auth/login').flush(demoUser);
    await login;

    const done = store.logOut();
    backend.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    backend.expectOne('/api/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    await done;

    expect(store.user()).toBeNull();
  });
});
