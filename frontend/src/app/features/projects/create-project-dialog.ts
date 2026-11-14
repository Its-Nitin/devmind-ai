import { Component, input, model, output, signal } from '@angular/core';
import { FormField, FormRoot, form, maxLength, required } from '@angular/forms/signals';
import { ButtonDirective } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';
import { CreateProjectRequest } from './projects-api';

function emptyDraft(): CreateProjectRequest {
  return { name: '', sourceType: 'LOCAL', sourceLocation: '' };
}

/**
 * Presentational dialog for registering a project (FE-6): it validates the draft and emits it, and the container decides
 * what happens next. The limits mirror `CreateProjectRequest` on the server, which still validates (and checks paths
 * and URLs properly during ingestion, SEC-2/3).
 */
@Component({
  selector: 'dm-create-project-dialog',
  imports: [Dialog, FormRoot, FormField, ButtonDirective, InputText],
  templateUrl: './create-project-dialog.html',
  styleUrl: './create-project-dialog.scss',
})
export class CreateProjectDialog {
  readonly open = model.required<boolean>();
  readonly saving = input(false);
  readonly create = output<CreateProjectRequest>();

  readonly #draft = signal(emptyDraft());

  protected readonly projectForm = form(
    this.#draft,
    (path) => {
      required(path.name, { message: 'Give the project a name.' });
      maxLength(path.name, 100, { message: 'Use at most 100 characters.' });
      required(path.sourceLocation, { message: 'Enter a folder path or repository URL.' });
      maxLength(path.sourceLocation, 500, { message: 'Use at most 500 characters.' });
    },
    {
      submission: {
        action: (field) => {
          this.create.emit(field().value());
          return Promise.resolve(undefined);
        },
      },
    },
  );

  /** Every opening starts from a blank, untouched form. */
  protected reset(): void {
    this.projectForm().reset(emptyDraft());
  }
}
