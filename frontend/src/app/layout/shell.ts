import { BreakpointObserver } from '@angular/cdk/layout';
import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { fecha } from '../core/format';
import { Notificacion, Rol } from '../core/models';

interface NavItem { label: string; icon: string; link: string; roles: Rol[]; }

const NAV: NavItem[] = [
  { label: 'Panel operativo', icon: 'dashboard', link: '/admin', roles: ['ADMIN'] },
  { label: 'Mi trabajo', icon: 'engineering', link: '/tecnico', roles: ['TECNICO'] },
  { label: 'Tickets', icon: 'confirmation_number', link: '/tickets', roles: ['ADMIN', 'TECNICO', 'USER'] },
  { label: 'Nuevo ticket', icon: 'add_circle', link: '/tickets/nuevo', roles: ['ADMIN', 'TECNICO', 'USER'] },
  { label: 'Asistente de IA', icon: 'support_agent', link: '/asistente', roles: ['ADMIN', 'TECNICO', 'USER'] },
  { label: 'Analítica (Data Lake)', icon: 'insights', link: '/analitica', roles: ['ADMIN'] },
  { label: 'Preguntar al Data Lake', icon: 'manage_search', link: '/analitica/preguntar', roles: ['ADMIN'] },
  { label: 'Reportes', icon: 'description', link: '/reportes', roles: ['ADMIN'] },
  { label: 'Usuarios y técnicos', icon: 'group', link: '/admin/personas', roles: ['ADMIN'] },
  { label: 'Configuración', icon: 'tune', link: '/admin/configuracion', roles: ['ADMIN'] },
];

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatSidenavModule, MatToolbarModule, MatListModule,
    MatIconModule, MatButtonModule, MatBadgeModule, MatMenuModule, MatTooltipModule],
  template: `
    <mat-sidenav-container class="container">
      <mat-sidenav #nav [mode]="mobile() ? 'over' : 'side'" [opened]="!mobile()" class="nav">
        <div class="brand"><mat-icon>build_circle</mat-icon><span>Soporte Inteligente</span></div>
        <mat-nav-list>
          @for (item of items(); track item.link) {
            <a mat-list-item [routerLink]="item.link" routerLinkActive="active" [routerLinkActiveOptions]="{exact: true}"
               (click)="mobile() && nav.close()">
              <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
              <span matListItemTitle>{{ item.label }}</span>
            </a>
          }
        </mat-nav-list>
      </mat-sidenav>
      <mat-sidenav-content>
        <mat-toolbar class="toolbar">
          @if (mobile()) { <button mat-icon-button (click)="nav.toggle()" aria-label="Menú"><mat-icon>menu</mat-icon></button> }
          <span class="spacer"></span>
          <button mat-icon-button [matMenuTriggerFor]="notifMenu" aria-label="Notificaciones" (menuOpened)="loadNotifs()">
            <mat-icon [matBadge]="unread() || null" matBadgeColor="warn" matBadgeSize="small">notifications</mat-icon>
          </button>
          <mat-menu #notifMenu="matMenu" class="notif-menu">
            <div class="notif-head" (click)="$event.stopPropagation()">
              <strong>Notificaciones</strong>
              @if (unread()) { <button mat-button (click)="markAll()">Marcar todas</button> }
            </div>
            @for (n of notifs(); track n.id) {
              <button mat-menu-item (click)="open(n)" [class.unread]="!n.leida">
                <div class="notif">
                  <div class="t">{{ n.titulo }}</div>
                  <div class="m">{{ n.mensaje }}</div>
                  <div class="f">{{ fmt(n.fecha) }}</div>
                </div>
              </button>
            } @empty { <div class="empty">Sin notificaciones</div> }
          </mat-menu>
          <button mat-button [matMenuTriggerFor]="userMenu" class="user">
            <mat-icon>account_circle</mat-icon> {{ auth.user()?.nombre }}
          </button>
          <mat-menu #userMenu="matMenu">
            <div class="user-info">{{ auth.user()?.email }} · {{ auth.rol() }}</div>
            <button mat-menu-item (click)="auth.logout()"><mat-icon>logout</mat-icon> Cerrar sesión</button>
          </mat-menu>
        </mat-toolbar>
        <main><router-outlet /></main>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
  styles: [`
    .container { height: 100vh; }
    .nav { width: 248px; border-right: 1px solid var(--hd-border); }
    .brand { display: flex; align-items: center; gap: 8px; padding: 18px 16px; font-weight: 500; font-size: 17px; color: #0f3d73; }
    .active { background: #e8f1fd; }
    .toolbar { background: var(--hd-card); border-bottom: 1px solid var(--hd-border); position: sticky; top: 0; z-index: 2; }
    .spacer { flex: 1; }
    .user { font-size: 14px; }
    .user-info { padding: 8px 16px; font-size: 12px; color: var(--hd-muted); }
    .notif-head { display: flex; justify-content: space-between; align-items: center; padding: 4px 16px; min-width: 300px; }
    .notif { white-space: normal; line-height: 1.3; padding: 6px 0; max-width: 320px; }
    .notif .t { font-weight: 500; font-size: 14px; }
    .notif .m { font-size: 13px; color: var(--hd-muted); }
    .notif .f { font-size: 11px; color: var(--hd-muted); }
    .unread .t::before { content: '● '; color: var(--series-1); }
    .empty { padding: 16px; color: var(--hd-muted); }
  `],
})
export class Shell {
  readonly auth = inject(AuthService);
  private http = inject(HttpClient);
  private router = inject(Router);
  readonly mobile = toSignal(inject(BreakpointObserver).observe('(max-width: 900px)').pipe(map(r => r.matches)), { initialValue: false });
  readonly items = computed(() => NAV.filter(i => this.auth.hasRole(...i.roles)));
  readonly notifs = signal<Notificacion[]>([]);
  readonly unread = signal(0);
  readonly fmt = fecha;

  constructor() {
    this.loadNotifs();
    const id = setInterval(() => this.loadNotifs(), 30000);
    inject(DestroyRef).onDestroy(() => clearInterval(id));
  }

  loadNotifs(): void {
    if (!this.auth.loggedIn()) return;
    this.http.get<{ noLeidas: number; items: Notificacion[] }>('/api/v1/notificaciones').subscribe({
      next: b => { this.notifs.set(b.items); this.unread.set(b.noLeidas); },
      error: () => {},
    });
  }

  open(n: Notificacion): void {
    if (!n.leida) this.http.post(`/api/v1/notificaciones/${n.id}/leida`, {}).subscribe(() => this.loadNotifs());
    if (n.ticketId) this.router.navigate(['/tickets', n.ticketId]);
  }

  markAll(): void {
    this.http.post('/api/v1/notificaciones/leidas', {}).subscribe(() => this.loadNotifs());
  }
}
