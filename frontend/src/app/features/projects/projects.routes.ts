import { Routes } from '@angular/router';
import { ProjectsApi } from './projects-api';
import { ProjectsPage } from './projects-page';
import { ProjectsStore } from './projects-store';

/** Lazy-loaded (FE-9). The API and store are scoped to this feature's route, not the root injector. */
export const projectsRoutes: Routes = [
  { path: '', title: 'Projects · DevMind', providers: [ProjectsApi, ProjectsStore], component: ProjectsPage },
];
