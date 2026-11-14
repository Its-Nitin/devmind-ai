import { Component, inject } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { ButtonDirective } from 'primeng/button';
import { Toast } from 'primeng/toast';
import { AuthStore } from './core/auth/auth-store';

/** The application shell: header with the signed-in user, the toast outlet for errors, and the router outlet. */
@Component({
  selector: 'dm-root',
  imports: [RouterOutlet, ButtonDirective, Toast],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly auth = inject(AuthStore);
  readonly #router = inject(Router);

  protected async logOut(): Promise<void> {
    await this.auth.logOut();
    await this.#router.navigate(['/login']);
  }
}
