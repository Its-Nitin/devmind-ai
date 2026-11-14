import { HttpClient, HttpContext, HttpStatusCode } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { components } from '../api/schema';
import { LOCALLY_HANDLED_STATUSES } from '../http/error-interceptor';

export type User = components['schemas']['UserResponse'];
export type LoginRequest = components['schemas']['LoginRequest'];

/** A 401 here is an answer, not a lost session: "signed out" for `me`, "wrong credentials" for `login`. */
function unauthorizedIsExpected(): HttpContext {
  return new HttpContext().set(LOCALLY_HANDLED_STATUSES, [HttpStatusCode.Unauthorized]);
}

/** The `/api/auth` endpoints (FE-8). RxJS stops here: callers get promises (FE-3). */
@Injectable({ providedIn: 'root' })
export class AuthApi {
  readonly #http = inject(HttpClient);

  /** Sets the XSRF-TOKEN cookie that Angular echoes as `X-XSRF-TOKEN` on mutating requests. 204, no body. */
  async issueCsrfToken(): Promise<void> {
    await firstValueFrom(this.#http.get<null>('/api/auth/csrf'));
  }

  getCurrentUser(): Promise<User> {
    return firstValueFrom(this.#http.get<User>('/api/auth/me', { context: unauthorizedIsExpected() }));
  }

  logIn(credentials: LoginRequest): Promise<User> {
    return firstValueFrom(this.#http.post<User>('/api/auth/login', credentials, { context: unauthorizedIsExpected() }));
  }

  /** 204, no body. */
  async logOut(): Promise<void> {
    await firstValueFrom(this.#http.post<null>('/api/auth/logout', null));
  }
}
