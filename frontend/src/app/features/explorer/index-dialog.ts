import { Component, input, model, output, signal } from '@angular/core';
import { FormField, FormRoot, form, maxLength, required } from '@angular/forms/signals';
import { ButtonDirective } from 'primeng/button';
import { Dialog } from 'primeng/dialog';
import { InputText } from 'primeng/inputtext';

/**
 * Presentational dialog asking which ref to index (FE-6). The limit mirrors `StartIndexRequest` on the server, which
 * resolves the ref and answers 422 if it names no commit.
 */
@Component({
  selector: 'dm-index-dialog',
  imports: [Dialog, FormRoot, FormField, ButtonDirective, InputText],
  template: `
    <p-dialog
      header="Index a revision"
      [modal]="true"
      [(visible)]="open"
      (onShow)="reset()"
      [style]="{ width: '28rem' }"
    >
      <form [formRoot]="refForm" id="index-form">
        <div class="field">
          <label for="index-ref">Branch, tag or commit SHA</label>
          <input
            pInputText
            id="index-ref"
            [formField]="refForm.ref"
            [attr.aria-invalid]="refForm.ref().touched() && refForm.ref().invalid()"
            aria-describedby="index-ref-errors"
          />
          <div id="index-ref-errors" class="field-errors">
            @if (refForm.ref().touched()) {
              @for (error of refForm.ref().errors(); track error.kind) {
                <small>{{ error.message }}</small>
              }
            }
          </div>
        </div>
      </form>
      <ng-template #footer>
        <button pButton type="button" [text]="true" (click)="open.set(false)">Cancel</button>
        <button pButton type="submit" form="index-form" [disabled]="starting()">
          {{ starting() ? 'Starting…' : 'Index' }}
        </button>
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .field {
      display: grid;
      gap: 0.35rem;
    }
    .field-errors small {
      color: var(--p-red-600, #b00);
    }
  `,
})
export class IndexDialog {
  readonly open = model.required<boolean>();
  readonly starting = input(false);
  /** The ref the dialog opens with, e.g. the project's default branch. */
  readonly defaultRef = input('main');
  readonly index = output<string>();

  readonly #draft = signal({ ref: 'main' });

  protected readonly refForm = form(
    this.#draft,
    (path) => {
      required(path.ref, { message: 'Enter a branch, tag or commit SHA.' });
      maxLength(path.ref, 200, { message: 'Use at most 200 characters.' });
    },
    {
      submission: {
        action: (field) => {
          this.index.emit(field().value().ref.trim());
          return Promise.resolve(undefined);
        },
      },
    },
  );

  protected reset(): void {
    this.refForm().reset({ ref: this.defaultRef() });
  }
}
