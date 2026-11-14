import { HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthStore } from '../auth/auth-store';
import { ErrorNotifier } from './error-notifier';
import { isUnauthorized, toUserFacingError } from './problem';

/**
 * Statuses the caller expects and handles itself, such as 401 from `GET /api/auth/me` (signed out) or from
 * `POST /api/auth/login` (wrong credentials). The interceptor neither redirects nor toasts for these.
 */
export const LOCALLY_HANDLED_STATUSES = new HttpContextToken<readonly number[]>(() => []);

/**
 * FE-8: a 401 means the session is gone, so forget the user and go to the login page; every other failure becomes a
 * toast built from the ProblemDetail. The error is always rethrown, so callers can still update their own state.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  const auth = inject(AuthStore);
  const notifier = inject(ErrorNotifier);

  return next(request).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && !request.context.get(LOCALLY_HANDLED_STATUSES).includes(error.status)) {
        if (isUnauthorized(error)) {
          auth.forgetUser();
          void router.navigate(['/login']);
        } else {
          notifier.notify(toUserFacingError(error));
        }
      }
      return throwError(() => error);
    }),
  );
};
