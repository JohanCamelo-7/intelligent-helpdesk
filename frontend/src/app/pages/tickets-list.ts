import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { errorMessage } from '../core/auth.interceptor';
import { AuthService } from '../core/auth.service';
import { ESTADOS, PRIORIDADES, fecha } from '../core/format';
import { Page, TicketResumen } from '../core/models';

@Component({
  selector: 'app-tickets-list',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatCheckboxModule, MatPaginatorModule, MatProgressBarModule],
  template: `
    <div class="page">
      <div class="page-header">
        <h1>{{ titulo() }}</h1>
        <a mat-flat-button routerLink="/tickets/nuevo"><mat-icon>add</mat-icon> Nuevo ticket</a>
      </div>
      <div class="card">
        <div class="row">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Buscar</mat-label>
            <input matInput [(ngModel)]="q" (keyup.enter)="load(0)" placeholder="Número o texto">
            <mat-icon matSuffix>search</mat-icon>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Estado</mat-label>
            <mat-select [(ngModel)]="estado" (selectionChange)="load(0)">
              <mat-option [value]="null">Todos</mat-option>
              @for (e of estados; track e[0]) { <mat-option [value]="e[0]">{{ e[1] }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Prioridad</mat-label>
            <mat-select [(ngModel)]="prioridad" (selectionChange)="load(0)">
              <mat-option [value]="null">Todas</mat-option>
              @for (p of prioridades; track p[0]) { <mat-option [value]="p[0]">{{ p[1] }}</mat-option> }
            </mat-select>
          </mat-form-field>
          @if (auth.hasRole('ADMIN')) {
            <mat-checkbox [(ngModel)]="revision" (change)="load(0)">Requieren revisión</mat-checkbox>
            <mat-checkbox [(ngModel)]="sinTecnico" (change)="load(0)">Sin técnico</mat-checkbox>
          }
        </div>
      </div>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (error()) { <div class="banner error">{{ error() }}</div> }
      <div class="card list">
        <div class="table-wrap">
          <table class="simple">
            <thead><tr><th>Número</th><th>Descripción</th><th>Estado</th><th>Prioridad</th><th>Categoría</th>
              <th>Técnico</th><th>Creado</th></tr></thead>
            <tbody>
              @for (t of page()?.content ?? []; track t.id) {
                <tr class="clickable" (click)="router.navigate(['/tickets', t.id])">
                  <td><strong>{{ t.numero }}</strong>
                    @if (t.requiereRevision && t.estado !== 'FINALIZADA') { <mat-icon class="rev" title="Requiere revisión">flag</mat-icon> }
                    @if (t.esDemo) { <span class="chip demo">demo</span> }
                  </td>
                  <td class="desc">{{ t.descripcion }}</td>
                  <td><span class="chip estado-{{ t.estado }}">{{ estadoLabel(t.estado) }}</span></td>
                  <td><span class="chip prio-{{ t.prioridad }}">{{ prioLabel(t.prioridad) }}</span></td>
                  <td>{{ t.categoria ?? 'Sin clasificar' }}</td>
                  <td>{{ t.tecnicoNombre ?? '—' }}</td>
                  <td class="muted small">{{ fmt(t.fechaCreacion) }}</td>
                </tr>
              } @empty {
                <tr><td colspan="7" class="empty">No hay tickets con estos filtros.</td></tr>
              }
            </tbody>
          </table>
        </div>
        <mat-paginator [length]="page()?.totalElements ?? 0" [pageSize]="size" [pageIndex]="page()?.page ?? 0"
                       [pageSizeOptions]="[10, 20, 50]" (page)="onPage($event)" />
      </div>
    </div>
  `,
  styles: [`
    .list { margin-top: 16px; padding: 0; overflow: hidden; }
    tr.clickable { cursor: pointer; } tr.clickable:hover { background: #f4f7fb; }
    .desc { max-width: 380px; }
    .rev { font-size: 18px; width: 18px; height: 18px; color: var(--hd-warn); vertical-align: middle; margin-left: 4px; }
  `],
})
export class TicketsListPage {
  private http = inject(HttpClient);
  readonly auth = inject(AuthService);
  readonly router = inject(Router);
  readonly estados = Object.entries(ESTADOS);
  readonly prioridades = Object.entries(PRIORIDADES);
  readonly page = signal<Page<TicketResumen> | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly fmt = fecha;
  q = ''; estado: string | null = null; prioridad: string | null = null;
  revision = false; sinTecnico = false; size = 20;

  constructor() {
    const qp = inject(ActivatedRoute).snapshot.queryParamMap;
    this.revision = qp.get('revision') === 'true';
    this.sinTecnico = qp.get('sinTecnico') === 'true';
    this.estado = qp.get('estado');
    this.load(0);
  }

  titulo(): string {
    if (this.auth.hasRole('TECNICO')) return 'Tickets asignados';
    if (this.auth.hasRole('USER')) return 'Mis solicitudes';
    return 'Todos los tickets';
  }

  estadoLabel(e: string) { return ESTADOS[e] ?? e; }
  prioLabel(p: string) { return PRIORIDADES[p] ?? p; }

  onPage(e: PageEvent): void { this.size = e.pageSize; this.load(e.pageIndex); }

  load(pageIndex: number): void {
    let p = new HttpParams().set('page', pageIndex).set('size', this.size);
    if (this.q) p = p.set('q', this.q);
    if (this.estado) p = p.set('estado', this.estado);
    if (this.prioridad) p = p.set('prioridad', this.prioridad);
    if (this.revision) p = p.set('requiereRevision', true);
    if (this.sinTecnico) p = p.set('sinTecnico', true);
    this.loading.set(true);
    this.http.get<Page<TicketResumen>>('/api/v1/tickets', { params: p }).subscribe({
      next: r => { this.page.set(r); this.error.set(''); this.loading.set(false); },
      error: e => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }
}
