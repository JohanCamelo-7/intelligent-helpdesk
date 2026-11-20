import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Rol, TokenResponse, Usuario } from './models';

/**
 * El token de acceso vive solo en memoria (no en localStorage) para reducir el riesgo de XSS.
 * La sesión se restaura con la cookie HttpOnly de refresco (SameSite=Strict).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);
  private timer?: ReturnType<typeof setTimeout>;
  private refreshing?: Promise<boolean>;

  readonly token = signal<string | null>(null);
  readonly user = signal<Usuario | null>(null);
  readonly loggedIn = computed(() => this.user() !== null);
  readonly rol = computed<Rol | null>(() => this.user()?.rol ?? null);

  async login(email: string, password: string): Promise<void> {
    const r = await firstValueFrom(this.http.post<TokenResponse>('/api/v1/auth/login', { email, password }));
    this.apply(r);
  }

  /** Intenta renovar el token con la cookie de refresco. Comparte la promesa si ya hay una en curso. */
  refresh(): Promise<boolean> {
    this.refreshing ??= firstValueFrom(this.http.post<TokenResponse>('/api/v1/auth/refresh', {}))
      .then(r => { this.apply(r); return true; })
      .catch(() => { this.clear(); return false; })
      .finally(() => { this.refreshing = undefined; });
    return this.refreshing;
  }

  async logout(): Promise<void> {
    try { await firstValueFrom(this.http.post('/api/v1/auth/logout', {})); } catch { /* sin conexión */ }
    this.clear();
    await this.router.navigateByUrl('/login');
  }

  hasRole(...roles: Rol[]): boolean {
    const r = this.rol();
    return r !== null && roles.includes(r);
  }

  homeUrl(): string {
    switch (this.rol()) {
      case 'ADMIN': return '/admin';
      case 'TECNICO': return '/tecnico';
      default: return '/tickets';
    }
  }

  private apply(r: TokenResponse): void {
    this.token.set(r.accessToken);
    this.user.set(r.usuario);
    clearTimeout(this.timer);
    // Renueva un minuto antes de que expire
    this.timer = setTimeout(() => this.refresh(), Math.max(30, r.expiresIn - 60) * 1000);
  }

  private clear(): void {
    clearTimeout(this.timer);
    this.token.set(null);
    this.user.set(null);
  }
}
