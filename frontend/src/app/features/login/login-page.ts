import { Component, inject, signal } from '@angular/core';
import { FormField, FormRoot, email, form, required } from '@angular/forms/signals';
import { Router } from '@angular/router';
import { ButtonDirective } from 'primeng/button';
import { InputText } from 'primeng/inputtext';
import type { LoginRequest } from '../../core/auth/auth-api';
import { AuthStore } from '../../core/auth/auth-store';

/**
 * Sign-in form. Uses signal forms (stable since Angular 22) rather than reactive forms, so form state is signals like
 * the rest of the app (FE-3). A wrong email and a wrong password get the same message.
 */
@Component({
  selector: 'dm-login-page',
  imports: [FormRoot, FormField, ButtonDirective, InputText],
  templateUrl: './login-page.html',
  styleUrl: './login-page.scss',
})
export class LoginPage {
  readonly #auth = inject(AuthStore);
  readonly #router = inject(Router);
  readonly #credentials = signal<LoginRequest>({ email: '', password: '' });

  protected readonly signInError = signal<string | null>(null);

  protected readonly loginForm = form(
    this.#credentials,
    (path) => {
      required(path.email, { message: 'Enter your email address.' });
      email(path.email, { message: 'Enter a valid email address.' });
      required(path.password, { message: 'Enter your password.' });
    },
    {
      submission: {
        action: async (field) => {
          await this.#signIn(field().value());
          return undefined;
        },
      },
    },
  );

  async #signIn(credentials: LoginRequest): Promise<void> {
    this.signInError.set(null);
    const result = await this.#auth.logIn(credentials);
    if (result === 'signed-in') {
      await this.#router.navigate(['/projects']);
    } else if (result === 'invalid-credentials') {
      this.signInError.set('Email or password is incorrect.');
    } else {
      this.signInError.set('Could not sign in. Try again.');
    }
  }
}
