import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { errorMessage } from '../core/auth.interceptor';
import { AuthService } from '../core/auth.service';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatProgressBarModule],
  template: `
    <div class="wrap">
      <form class="card box" [formGroup]="form" (ngSubmit)="submit()">
        <div class="brand"><mat-icon>build_circle</mat-icon> Soporte Técnico Inteligente</div>
        <p class="muted small">Reporta fallas por texto, imagen o voz. La IA clasifica y asigna al técnico adecuado.</p>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (error()) { <div class="banner error"><mat-icon>error</mat-icon>{{ error() }}</div> }
        <mat-form-field class="full" appearance="outline">
          <mat-label>Correo</mat-label>
          <input matInput type="email" formControlName="email" autocomplete="username" required>
        </mat-form-field>
        <mat-form-field class="full" appearance="outline">
          <mat-label>Contraseña</mat-label>
          <input matInput [type]="show() ? 'text' : 'password'" formControlName="password" autocomplete="current-password" required>
          <button mat-icon-button matSuffix type="button" (click)="show.set(!show())" [attr.aria-label]="show() ? 'Ocultar' : 'Mostrar'">
            <mat-icon>{{ show() ? 'visibility_off' : 'visibility' }}</mat-icon>
          </button>
        </mat-form-field>
        <button mat-flat-button class="full" type="submit" [disabled]="form.invalid || loading()">Ingresar</button>
      </form>
    </div>
  `,
  styles: [`
    .wrap { min-height: 100vh; display: grid; place-items: center; padding: 16px; box-sizing: border-box; }
    .box { width: 100%; max-width: 400px; padding: 28px; display: flex; flex-direction: column; gap: 4px; }
    .brand { display: flex; align-items: center; gap: 8px; font-size: 20px; font-weight: 500; color: #0f3d73; }
    button[type=submit] { height: 44px; margin-top: 8px; }
  `],
})
export class LoginPage {
  private auth = inject(AuthService);
  private router = inject(Router);
  readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  readonly loading = signal(false);
  readonly error = signal('');
  readonly show = signal(false);

  constructor() {
    if (this.auth.loggedIn()) this.router.navigateByUrl(this.auth.homeUrl());
  }

  async submit(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const { email, password } = this.form.getRawValue();
      await this.auth.login(email, password);
      await this.router.navigateByUrl(this.auth.homeUrl());
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.loading.set(false);
    }
  }
}
