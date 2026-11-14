import { HttpInterceptorFn } from '@angular/common/http';
import { CORRELATION_ID_HEADER } from './problem';

/**
 * Tags every request with a fresh correlation ID (OBS-1, FE-8). The backend echoes it in logs and in ProblemDetails,
 * so an error toast and the server log line can be matched.
 */
export const correlationIdInterceptor: HttpInterceptorFn = (request, next) =>
  next(request.clone({ setHeaders: { [CORRELATION_ID_HEADER]: crypto.randomUUID() } }));
