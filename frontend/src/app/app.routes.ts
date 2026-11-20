import { Routes } from '@angular/router';
import { authGuard, homeRedirect, roleGuard } from './core/guards';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login').then(m => m.LoginPage) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then(m => m.Shell),
    children: [
      { path: '', pathMatch: 'full', canActivate: [homeRedirect], children: [] },
      { path: 'tickets', loadComponent: () => import('./pages/tickets-list').then(m => m.TicketsListPage) },
      { path: 'tickets/nuevo', loadComponent: () => import('./pages/ticket-new').then(m => m.TicketNewPage) },
      { path: 'tickets/:id', loadComponent: () => import('./pages/ticket-detail').then(m => m.TicketDetailPage) },
      { path: 'asistente', loadComponent: () => import('./pages/agent').then(m => m.AgentPage) },
      { path: 'tecnico', canActivate: [roleGuard('TECNICO')], loadComponent: () => import('./pages/tecnico-home').then(m => m.TecnicoHomePage) },
      { path: 'admin', canActivate: [roleGuard('ADMIN')], loadComponent: () => import('./pages/admin-home').then(m => m.AdminHomePage) },
      { path: 'admin/personas', canActivate: [roleGuard('ADMIN')], loadComponent: () => import('./pages/admin-people').then(m => m.AdminPeoplePage) },
      { path: 'admin/configuracion', canActivate: [roleGuard('ADMIN')], loadComponent: () => import('./pages/admin-config').then(m => m.AdminConfigPage) },
      { path: 'analitica/preguntar', canActivate: [roleGuard('ADMIN')], loadComponent: () => import('./pages/lake-agent').then(m => m.LakeAgentPage) },
      { path: 'analitica', canActivate: [roleGuard('ADMIN')], loadComponent: () => import('./pages/analytics').then(m => m.AnalyticsPage) },
      { path: 'reportes', canActivate: [roleGuard('ADMIN')], loadComponent: () => import('./pages/reports').then(m => m.ReportsPage) },
    ],
  },
  { path: '**', redirectTo: '' },
];
