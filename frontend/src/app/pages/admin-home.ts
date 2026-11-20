import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { errorMessage } from '../core/auth.interceptor';
import { duracion, fecha, num } from '../core/format';

@Component({
  selector: 'app-admin-home',
  imports: [RouterLink, MatButtonModule, MatIconModule],
  template: `
    <div class="page">
      <div class="page-header">
        <h1>Panel operativo</h1>
        <a mat-stroked-button routerLink="/analitica"><mat-icon>insights</mat-icon> Analítica histórica</a>
      </div>
      <div class="banner info"><mat-icon>bolt</mat-icon> Indicadores operativos en tiempo real (base transaccional).
        Los históricos salen del Data Lake en «Analítica».</div>
      @if (error()) { <div class="banner error">{{ error() }}</div> }
      @if (d(); as d) {
        <div class="tiles">
          @for (k of tiles; track k[0]) {
            <a class="tile link" [routerLink]="k[2] ? '/tickets' : null" [queryParams]="k[2]">
              <div class="label">{{ k[1] }}</div><div class="value">{{ num(d.contadores[k[0]]) }}</div></a>
          }
          <div class="tile"><div class="label">Técnicos disponibles</div><div class="value">{{ d.tecnicosDisponibles }}</div></div>
          <div class="tile"><div class="label">Atención promedio (30 d)</div><div class="value">{{ dur(d.tiempos30Dias.prom_atencion_min) }}</div></div>
          <div class="tile"><div class="label">Resolución promedio (30 d)</div><div class="value">{{ dur(d.tiempos30Dias.prom_resolucion_min) }}</div></div>
        </div>
        <div class="grid grid-2 gap">
          <div class="card">
            <h3 class="section-title">Carga de los técnicos</h3>
            <div class="table-wrap"><table class="simple">
              <thead><tr><th>Técnico</th><th>Nivel</th><th class="num">Abiertos</th><th>Carga</th></tr></thead>
              <tbody>@for (t of d.tecnicos; track t.id) {
                <tr><td>{{ t.nombre }} @if (!t.disponible) { <span class="chip estado-CANCELADA">no disponible</span> }</td>
                  <td>{{ t.nivel }}</td><td class="num">{{ t.abiertos }}</td>
                  <td><div class="bar" [title]="t.carga + ' de ' + t.carga_maxima"><div [style.width.%]="min(100, t.carga / t.carga_maxima * 100)"
                    [class.high]="t.carga / t.carga_maxima > 0.8"></div></div>
                    <span class="small muted">{{ t.carga }}/{{ t.carga_maxima }}</span></td></tr>
              } @empty { <tr><td colspan="4" class="empty">Aún no hay técnicos. Créalos en «Usuarios y técnicos».</td></tr> }</tbody>
            </table></div>
          </div>
          <div class="card">
            <h3 class="section-title">Pendientes de validación</h3>
            <table class="simple"><thead><tr><th>Ticket</th><th>Prioridad</th><th class="num">Confianza IA</th><th>Creado</th></tr></thead>
              <tbody>@for (r of d.revision; track r.id) {
                <tr><td><a [routerLink]="['/tickets', r.id]">{{ r.numero }}</a></td>
                  <td><span class="chip prio-{{ r.prioridad }}">{{ r.prioridad }}</span></td>
                  <td class="num">{{ r.ai_confianza !== null ? num(r.ai_confianza * 100) + ' %' : '—' }}</td>
                  <td class="small muted">{{ fmt(r.fecha_creacion) }}</td></tr>
              } @empty { <tr><td colspan="4" class="empty">Nada pendiente de revisión.</td></tr> }</tbody></table>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    .link { text-decoration: none; color: inherit; } a.link[href]:hover { border-color: #9bb8e0; }
    .gap { margin-top: 16px; }
    .bar { display: inline-block; vertical-align: middle; width: 90px; height: 8px; background: #eceef1; border-radius: 4px; overflow: hidden; margin-right: 6px; }
    .bar div { height: 100%; background: var(--series-1); } .bar div.high { background: var(--series-2); }
  `],
})
export class AdminHomePage {
  private http = inject(HttpClient);
  readonly d = signal<any>(null);
  readonly error = signal('');
  readonly num = num; readonly dur = duracion; readonly fmt = fecha; readonly min = Math.min;
  readonly tiles: [string, string, Record<string, string> | null][] = [
    ['total', 'Total de tickets', {}], ['pendientes', 'Pendientes', { estado: 'PENDIENTE' }],
    ['en_curso', 'En curso', { estado: 'EN_CURSO' }], ['finalizados', 'Finalizados', { estado: 'FINALIZADA' }],
    ['prioritarios', 'Prioritarios abiertos', null], ['sin_tecnico', 'Sin técnico', { sinTecnico: 'true' }],
    ['clasificados_ia', 'Clasificados por IA', null], ['pendientes_validacion', 'Pendientes de validación', { revision: 'true' }],
  ];

  constructor() {
    this.load();
    const id = setInterval(() => this.load(), 30000);
    inject(DestroyRef).onDestroy(() => clearInterval(id));
  }

  load(): void {
    this.http.get('/api/v1/dashboard/operativo').subscribe({
      next: d => { this.d.set(d); this.error.set(''); },
      error: e => this.error.set(errorMessage(e)),
    });
  }
}
