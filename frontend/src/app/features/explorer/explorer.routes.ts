import { Routes } from '@angular/router';
import { ExplorerApi } from './explorer-api';
import { ExplorerPage } from './explorer-page';
import { ExplorerStore } from './explorer-store';
import { ProjectPage } from './project-page';
import { ProjectStore } from './project-store';

/**
 * Lazy-loaded under `/projects/:projectId` (FE-9). The API is shared by both pages; each store is scoped to its page's
 * route, so leaving a page drops its state (and stops any job polling).
 */
export const explorerRoutes: Routes = [
  {
    path: '',
    providers: [ExplorerApi],
    children: [
      { path: '', title: 'Project · DevMind', providers: [ProjectStore], component: ProjectPage },
      { path: 'revisions/:sha', title: 'Explorer · DevMind', providers: [ExplorerStore], component: ExplorerPage },
    ],
  },
];
