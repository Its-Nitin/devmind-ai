import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { authGuard } from './auth-guard';
import { AuthStore } from './auth-store';

describe('authGuard', () => {
  const isAuthenticated = signal(false);

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthStore, useValue: { isAuthenticated } }],
    });
  });

  const runGuard = () =>
    TestBed.runInInjectionContext(() => authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot));

  it('lets a signed-in user through', () => {
    isAuthenticated.set(true);

    expect(runGuard()).toBe(true);
  });

  it('sends a signed-out user to the login page', () => {
    isAuthenticated.set(false);

    const result = runGuard();

    expect(result).toBeInstanceOf(UrlTree);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/login');
  });
});
