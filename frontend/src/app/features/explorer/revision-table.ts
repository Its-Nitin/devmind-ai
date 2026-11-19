import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Tag } from 'primeng/tag';
import { Revision } from './explorer-api';

type Severity = 'success' | 'info' | 'danger';

/**
 * Presentational table of a project's revisions (FE-6): status in words (FE-11), short SHA, stats, and a link to
 * browse each READY one.
 */
@Component({
  selector: 'dm-revision-table',
  imports: [DatePipe, RouterLink, Tag],
  template: `
    <table class="revisions">
      <caption class="visually-hidden">
        Indexed revisions, newest first
      </caption>
      <thead>
        <tr>
          <th scope="col">Commit</th>
          <th scope="col">Status</th>
          <th scope="col">Files</th>
          <th scope="col">Symbols</th>
          <th scope="col">Chunks</th>
          <th scope="col">Parse failures</th>
          <th scope="col">Redactions</th>
          <th scope="col">Indexed</th>
        </tr>
      </thead>
      <tbody>
        @for (revision of revisions(); track revision.commitSha) {
          <tr>
            <th scope="row">
              @if (revision.status === 'READY') {
                <a [routerLink]="['/projects', projectId(), 'revisions', revision.commitSha]">
                  <code>{{ revision.commitSha.slice(0, 7) }}</code>
                </a>
              } @else {
                <code>{{ revision.commitSha.slice(0, 7) }}</code>
              }
            </th>
            <td><p-tag [value]="revision.status" [severity]="severities[revision.status]" /></td>
            @if (revision.stats; as stats) {
              <td>{{ stats.filesKept }}</td>
              <td>{{ stats.symbols }}</td>
              <td>{{ stats.chunks }}</td>
              <td>{{ stats.parseFailures }}</td>
              <td>{{ stats.redactions }}</td>
            } @else {
              <td colspan="5">—</td>
            }
            <td>
              @if (revision.readyAt; as readyAt) {
                <time [attr.datetime]="readyAt">{{ readyAt | date: 'medium' }}</time>
              } @else {
                —
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    .revisions {
      width: 100%;
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 0.4rem 0.6rem;
      border-bottom: 1px solid var(--p-content-border-color, #ddd);
    }
  `,
})
export class RevisionTable {
  readonly projectId = input.required<string>();
  readonly revisions = input.required<readonly Revision[]>();

  protected readonly severities: Readonly<Record<Revision['status'], Severity>> = {
    READY: 'success',
    INDEXING: 'info',
    FAILED: 'danger',
  };
}
