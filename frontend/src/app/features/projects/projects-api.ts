import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { components } from '../../core/api/schema';

export type Project = components['schemas']['ProjectResponse'];
export type ProjectPage = components['schemas']['PagedModelProjectResponse'];
export type CreateProjectRequest = components['schemas']['CreateProjectRequest'];
export type SourceType = CreateProjectRequest['sourceType'];

/** The backend's page-size ceiling (`ProjectController.MAX_PAGE_SIZE`). */
export const MAX_PAGE_SIZE = 100;

/** The `/api/projects` endpoints (FE-8), typed from the OpenAPI contract (FE-7). Callers get promises (FE-3). */
@Injectable()
export class ProjectsApi {
  readonly #http = inject(HttpClient);

  /** The first page of the caller's projects, newest first. */
  listNewestFirst(): Promise<ProjectPage> {
    return firstValueFrom(
      this.#http.get<ProjectPage>('/api/projects', { params: { size: MAX_PAGE_SIZE, sort: 'createdAt,desc' } }),
    );
  }

  create(request: CreateProjectRequest): Promise<Project> {
    return firstValueFrom(this.#http.post<Project>('/api/projects', request));
  }
}
