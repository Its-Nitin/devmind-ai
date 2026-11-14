import { HttpErrorResponse } from '@angular/common/http';
import type { components } from '../api/schema';

/** The RFC 9457 body every API error carries (API-3). Generated from the contract (FE-7). */
export type Problem = components['schemas']['Problem'];

/** What the user sees for a failed request: the ProblemDetail's title and detail, never a raw response body. */
export interface UserFacingError {
  readonly title: string;
  readonly detail: string;
  readonly correlationId: string | null;
}

export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

function isProblem(body: unknown): body is Problem {
  if (typeof body !== 'object' || body === null) {
    return false;
  }
  const candidate = body as Record<string, unknown>;
  return (
    typeof candidate['title'] === 'string' &&
    typeof candidate['detail'] === 'string' &&
    typeof candidate['code'] === 'string' &&
    typeof candidate['status'] === 'number'
  );
}

/** Narrows an HTTP error to the server's ProblemDetail, or `null` when the body is something else. */
export function problemOf(error: HttpErrorResponse): Problem | null {
  return isProblem(error.error) ? error.error : null;
}

/** Maps any HTTP failure to a message fit for a toast, keeping the correlation ID so the user can quote it (OBS-1). */
export function toUserFacingError(error: HttpErrorResponse): UserFacingError {
  if (error.status === 0) {
    return {
      title: 'Server unreachable',
      detail: 'DevMind could not reach the server. Check that it is running and try again.',
      correlationId: null,
    };
  }
  const problem = problemOf(error);
  if (problem !== null) {
    return { title: problem.title, detail: problem.detail, correlationId: problem.correlationId ?? null };
  }
  return {
    title: 'Something went wrong',
    detail: `The server answered with status ${String(error.status)}.`,
    correlationId: error.headers.get(CORRELATION_ID_HEADER),
  };
}

/** True for a 401 response. `HttpErrorResponse.status` is a plain number, so compare it with one. */
export function isUnauthorized(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 401;
}
