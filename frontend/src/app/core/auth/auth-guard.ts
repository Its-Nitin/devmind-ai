import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthStore } from './auth-store';

/** FE-9: signed-out users go to the login page. The server still enforces access on every request. */
export const authGuard: CanActivateFn = () => {
  const router = inject(Router);
  return inject(AuthStore).isAuthenticated() || router.createUrlTree(['/login']);
};
