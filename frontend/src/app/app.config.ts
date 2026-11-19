import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withRouterConfig } from '@angular/router';
import Aura from '@primeuix/themes/aura';
import { MessageService } from 'primeng/api';
import { providePrimeNG } from 'primeng/config';
import { routes } from './app.routes';
import { AuthStore } from './core/auth/auth-store';
import { correlationIdInterceptor } from './core/http/correlation-id-interceptor';
import { errorInterceptor } from './core/http/error-interceptor';

/** Zoneless and OnPush are Angular 22's defaults (FE-2), so neither needs a provider here. */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Route and query parameters bind to component inputs; child routes see their parents' (e.g. :projectId).
    provideRouter(routes, withComponentInputBinding(), withRouterConfig({ paramsInheritanceStrategy: 'always' })),
    // The backend's CookieCsrfTokenRepository names (SEC-7); Angular sends the header on mutating relative requests.
    provideHttpClient(
      withInterceptors([correlationIdInterceptor, errorInterceptor]),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
    provideAppInitializer(() => inject(AuthStore).initialize()),
    providePrimeNG({ theme: { preset: Aura } }),
    MessageService,
  ],
};
