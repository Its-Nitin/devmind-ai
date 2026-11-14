import { Component, inject, signal } from '@angular/core';
import { ButtonDirective } from 'primeng/button';
import { ProgressSpinner } from 'primeng/progressspinner';
import { CreateProjectDialog } from './create-project-dialog';
import { ProjectList } from './project-list';
import { CreateProjectRequest } from './projects-api';
import { ProjectsStore } from './projects-store';

/** Container for the projects feature: talks to the store, hands data to presentational children (FE-6). */
@Component({
  selector: 'dm-projects-page',
  imports: [ButtonDirective, ProgressSpinner, ProjectList, CreateProjectDialog],
  templateUrl: './projects-page.html',
  styleUrl: './projects-page.scss',
})
export class ProjectsPage {
  protected readonly store = inject(ProjectsStore);
  protected readonly createDialogOpen = signal(false);

  constructor() {
    void this.store.load();
  }

  protected reload(): void {
    void this.store.load();
  }

  protected async create(request: CreateProjectRequest): Promise<void> {
    if (await this.store.create(request)) {
      this.createDialogOpen.set(false);
    }
  }
}
