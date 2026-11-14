import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth-guard';

/** Every feature is lazy-loaded; everything but the login page needs a session (FE-9). */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in · DevMind',
    loadComponent: () => import('./features/login/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'projects',
    canActivate: [authGuard],
    loadChildren: () => import('./features/projects/projects.routes').then((m) => m.projectsRoutes),
  },
  { path: '', pathMatch: 'full', redirectTo: 'projects' },
  { path: '**', redirectTo: 'projects' },
];
