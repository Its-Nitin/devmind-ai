import { Injectable, inject } from '@angular/core';
import { MessageService } from 'primeng/api';
import { UserFacingError } from './problem';

/** Shows request failures as toasts. The shell renders the `<p-toast>` that displays them. */
@Injectable({ providedIn: 'root' })
export class ErrorNotifier {
  readonly #messages = inject(MessageService);

  notify(error: UserFacingError): void {
    const reference = error.correlationId === null ? '' : ` (reference ${error.correlationId})`;
    this.#messages.add({ severity: 'error', summary: error.title, detail: error.detail + reference, life: 8000 });
  }
}
