import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { duracion, num } from '../core/format';

@Component({
  selector: 'app-tecnico-home',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatSlideToggleModule],
  template: `
    <div class="page">
      <div class="page-header">
        <h1>Mi trabajo</h1>
        @if (data(); as d) {
          <mat-slide-toggle [ngModel]="d.carga.disponible" (ngModelChange)="disponible($event)">Disponible para nuevas asignaciones</mat-slide-toggle>
        }
      </div>
      @if (data(); as d) {
        <div class="tiles">
          <div class="tile"><div class="label">Pendientes</div><div class="value">{{ d.contadores.pendientes }}</div></div>
          <div class="tile"><div class="label">En curso</div><div class="value">{{ d.contadores.en_curso }}</div></div>
          <div class="tile"><div class="label">Finalizados (30 días)</div><div class="value">{{ d.contadores.finalizados_30d }}</div></div>
          <div class="tile"><div class="label">Resolución promedio</div><div class="value">{{ dur(d.contadores.prom_resolucion_min) }}</div></div>
          <div class="tile"><div class="label">Satisfacción</div><div class="value">{{ d.satisfaccion ? num(d.satisfaccion, 1) + '/5' : '—' }}</div></div>
        </div>
        <div class="card load">
          <div class="row"><strong>Carga actual</strong><span class="muted small">{{ d.carga.carga }} de {{ d.carga.carga_maxima }} puntos
            (baja 1 · media 2 · alta 3 · crítica 4)</span></div>
          <div class="bar"><div [style.width.%]="min(100, d.carga.carga / d.carga.carga_maxima * 100)"></div></div>
        </div>
      }
      <a mat-flat-button routerLink="/tickets"><mat-icon>list</mat-icon> Ver mis tickets asignados</a>
    </div>
  `,
  styles: [`.load { margin: 16px 0; } .bar { height: 10px; background: #eceef1; border-radius: 5px; margin-top: 8px; overflow: hidden; }
    .bar div { height: 100%; background: var(--series-1); }`],
})
export class TecnicoHomePage {
  private http = inject(HttpClient);
  readonly data = signal<any>(null);
  readonly dur = duracion; readonly num = num; readonly min = Math.min;

  constructor() { this.load(); }
  load(): void { this.http.get('/api/v1/dashboard/tecnico').subscribe(d => this.data.set(d)); }
  disponible(v: boolean): void {
    this.http.patch('/api/v1/tecnicos/me/disponibilidad', { disponible: v }).subscribe(() => this.load());
  }
}
