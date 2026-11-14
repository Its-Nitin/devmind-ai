import { Injectable, computed, inject, signal } from '@angular/core';
import { isUnauthorized } from '../http/problem';
import { AuthApi, LoginRequest, User } from './auth-api';

/** An expected outcome, so a result rather than an exception (ERR-2). It never says whether the email exists. */
export type LoginResult = 'signed-in' | 'invalid-credentials' | 'failed';

/** Who is signed in, as signals (FE-3). Session state itself lives in the server's cookie (ADR-0010). */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  readonly #api = inject(AuthApi);
  readonly #user = signal<User | null>(null);

  readonly user = this.#user.asReadonly();
  readonly isAuthenticated = computed(() => this.#user() !== null);

  /**
   * Runs once on app start: get a CSRF cookie, then find out whether a session already exists. Never rejects, so a
   * server that is down still lets the app render (the error interceptor has shown a toast by then).
   */
  async initialize(): Promise<void> {
    try {
      await this.#api.issueCsrfToken();
      await this.loadCurrentUser();
    } catch {
      this.#user.set(null);
    }
  }

  /** Signed out (401) leaves `user` null; any other failure propagates. */
  async loadCurrentUser(): Promise<void> {
    try {
      this.#user.set(await this.#api.getCurrentUser());
    } catch (error) {
      this.#user.set(null);
      if (!isUnauthorized(error)) {
        throw error;
      }
    }
  }

  async logIn(credentials: LoginRequest): Promise<LoginResult> {
    try {
      this.#user.set(await this.#api.logIn(credentials));
      return 'signed-in';
    } catch (error) {
      this.#user.set(null);
      return isUnauthorized(error) ? 'invalid-credentials' : 'failed';
    }
  }

  /** Logout clears the XSRF-TOKEN cookie, so fetch a fresh one for the next login. */
  async logOut(): Promise<void> {
    try {
      await this.#api.logOut();
    } finally {
      this.#user.set(null);
      await this.#api.issueCsrfToken().catch(() => undefined);
    }
  }

  /** Called when the server says the session is gone (any 401 the caller did not expect). */
  forgetUser(): void {
    this.#user.set(null);
  }
}
