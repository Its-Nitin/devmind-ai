import type { MockInstance } from 'vitest';
import { HttpClient, HttpContext, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthStore } from '../auth/auth-store';
import { ErrorNotifier } from './error-notifier';
import { LOCALLY_HANDLED_STATUSES, errorInterceptor } from './error-interceptor';
import { Problem } from './problem';

describe('errorInterceptor', () => {
  let http: HttpClient;
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
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    backend.verify();
  });

  async function failingGet(
    status: number,
    body: string | object | null,
    context = new HttpContext(),
  ): Promise<HttpErrorResponse> {
    const response = firstValueFrom(http.get('/api/projects', { context }));
    backend.expectOne('/api/projects').flush(body, { status, statusText: 'Error' });
    return response.then(
      () => expect.fail('the request should fail'),
      (error: unknown) => {
        expect(error).toBeInstanceOf(HttpErrorResponse);
        return error as HttpErrorResponse;
      },
    );
  }

  it('sends the user to the login page and forgets them on an unexpected 401', async () => {
    const forgetUser = vi.spyOn(TestBed.inject(AuthStore), 'forgetUser');

    const error = await failingGet(401, null);

    expect(error.status).toBe(401);
    expect(forgetUser).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith(['/login']);
    expect(notify).not.toHaveBeenCalled();
  });

  it('leaves a 401 the caller expects alone', async () => {
    await failingGet(401, null, new HttpContext().set(LOCALLY_HANDLED_STATUSES, [401]));

    expect(navigate).not.toHaveBeenCalled();
    expect(notify).not.toHaveBeenCalled();
  });

  it('turns a ProblemDetail into a toast with its correlation ID', async () => {
    const problem: Problem = {
      type: 'https://devmind.dev/problems/project-name-taken',
      title: 'Project name taken',
      status: 409,
      detail: 'You already have a project with this name.',
      code: 'project-name-taken',
      correlationId: 'c0ffee00-0000-4000-8000-000000000000',
    };

    await failingGet(409, problem);

    expect(notify).toHaveBeenCalledWith({
      title: 'Project name taken',
      detail: 'You already have a project with this name.',
      correlationId: 'c0ffee00-0000-4000-8000-000000000000',
    });
    expect(navigate).not.toHaveBeenCalled();
  });

  it('falls back to a generic message when the body is not a ProblemDetail', async () => {
    await failingGet(502, '<html>Bad gateway</html>');

    expect(notify).toHaveBeenCalledWith({
      title: 'Something went wrong',
      detail: 'The server answered with status 502.',
      correlationId: null,
    });
  });
});
