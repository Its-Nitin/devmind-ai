import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { Project, SourceType } from './projects-api';

/** Presentational table of projects: inputs only, no services (FE-6). Content renders as text (SEC-9). */
@Component({
  selector: 'dm-project-list',
  imports: [DatePipe],
  templateUrl: './project-list.html',
  styleUrl: './project-list.scss',
})
export class ProjectList {
  readonly projects = input.required<readonly Project[]>();

  protected readonly sourceLabels: Readonly<Record<SourceType, string>> = {
    LOCAL: 'Local folder',
    GIT_PUBLIC: 'Public Git',
  };
}
