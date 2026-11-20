import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { Rol } from './models';

export const authGuard: CanActivateFn = async () => {
  // inject() solo funciona antes del primer await (contexto de inyección síncrono)
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.loggedIn()) return true;
  if (await auth.refresh()) return true;
  return router.parseUrl('/login');
};

/** La interfaz oculta lo que no corresponde; el backend vuelve a validar siempre. */
export function roleGuard(...roles: Rol[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    return auth.hasRole(...roles) ? true : inject(Router).parseUrl(auth.homeUrl());
  };
}

export const homeRedirect: CanActivateFn = () => {
  const auth = inject(AuthService);
  return inject(Router).parseUrl(auth.homeUrl());
};
