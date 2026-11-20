import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const PUBLIC = ['/api/v1/auth/login', '/api/v1/auth/refresh', '/api/v1/auth/logout'];

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

/** Agrega el JWT y, ante un 401, intenta renovar la sesión una sola vez. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/') || PUBLIC.some(p => req.url.startsWith(p))) {
    return next(req);
  }
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(withToken(req, auth.token())).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status !== 401) return throwError(() => err);
      return from(auth.refresh()).pipe(switchMap(ok => {
        if (!ok) {
          router.navigateByUrl('/login');
          return throwError(() => err);
        }
        return next(withToken(req, auth.token()));
      }));
    }),
  );
};

/** Mensaje legible de un error de la API (formato Problem Details o detail de FastAPI). */
export function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const b = err.error;
    if (b && typeof b === 'object') {
      if (b.errores) return Object.entries(b.errores).map(([k, v]) => `${k}: ${v}`).join(' · ');
      if (typeof b.detail === 'string') return b.detail;
    }
    if (err.status === 0) return 'No hay conexión con el servidor';
    return `Error ${err.status}`;
  }
  return err instanceof Error ? err.message : 'Error inesperado';
}
