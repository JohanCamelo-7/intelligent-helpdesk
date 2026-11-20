import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { errorMessage } from '../core/auth.interceptor';
import { CatalogService } from '../core/catalog.service';
import { PRIORIDADES, ESTADOS, duracion, fecha, isoDate, num, pct } from '../core/format';
import { Tecnico } from '../core/models';
import { ChartComponent, ChartSpec, SERIES } from '../shared/chart';

type Row = Record<string, any>;

@Component({
  selector: 'app-analytics',
  imports: [FormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatSlideToggleModule, MatButtonToggleModule, MatProgressBarModule, ChartComponent],
  template: `
    <div class="page">
      <div class="page-header">
        <h1>Analítica histórica</h1>
        <a mat-stroked-button routerLink="/reportes"><mat-icon>download</mat-icon> Generar reporte</a>
      </div>

      <div class="card filters">
        <div class="row">
          <mat-form-field appearance="outline" subscriptSizing="dynamic"><mat-label>Desde</mat-label>
            <input matInput type="date" [(ngModel)]="desde"></mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic"><mat-label>Hasta</mat-label>
            <input matInput type="date" [(ngModel)]="hasta"></mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic"><mat-label>Sede</mat-label>
            <mat-select [(ngModel)]="sede"><mat-option [value]="''">Todas</mat-option>
              @for (s of catalog.catalogo()?.sedes ?? []; track s.id) { <mat-option [value]="s.codigo">{{ s.nombre }}</mat-option> }</mat-select></mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic"><mat-label>Categoría</mat-label>
            <mat-select [(ngModel)]="categoria"><mat-option [value]="''">Todas</mat-option>
              @for (c of catalog.catalogo()?.categorias ?? []; track c.id) { <mat-option [value]="c.codigo">{{ c.nombre }}</mat-option> }</mat-select></mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic"><mat-label>Técnico</mat-label>
            <mat-select [(ngModel)]="tecnico"><mat-option [value]="''">Todos</mat-option>
              @for (t of tecnicos(); track t.id) { <mat-option [value]="t.id">{{ t.nombre }}</mat-option> }</mat-select></mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic"><mat-label>Prioridad</mat-label>
            <mat-select [(ngModel)]="prioridad"><mat-option [value]="''">Todas</mat-option>
              @for (p of prioList; track p[0]) { <mat-option [value]="p[0]">{{ p[1] }}</mat-option> }</mat-select></mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic"><mat-label>Estado</mat-label>
            <mat-select [(ngModel)]="estado"><mat-option [value]="''">Todos</mat-option>
              @for (e of estList; track e[0]) { <mat-option [value]="e[0]">{{ e[1] }}</mat-option> }</mat-select></mat-form-field>
        </div>
        <div class="row">
          <mat-button-toggle-group [(ngModel)]="agrupacion" aria-label="Agrupación">
            <mat-button-toggle value="DIA">Día</mat-button-toggle><mat-button-toggle value="SEMANA">Semana</mat-button-toggle>
            <mat-button-toggle value="MES">Mes</mat-button-toggle></mat-button-toggle-group>
          <mat-slide-toggle [(ngModel)]="incluirDemo">Incluir datos de demostración</mat-slide-toggle>
          <span class="spacer"></span>
          <button mat-flat-button (click)="load()"><mat-icon>filter_alt</mat-icon> Aplicar</button>
        </div>
      </div>

      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (error()) {
        <div class="banner {{ sinDatos() ? 'info' : 'error' }}"><mat-icon>{{ sinDatos() ? 'hourglass_empty' : 'error' }}</mat-icon>
          <span>{{ error() }}</span>
          @if (sinDatos()) { <a mat-button routerLink="/admin/configuracion">Ejecutar el pipeline</a> }</div>
      }

      @if (r(); as r) {
        <div class="banner info"><mat-icon>database</mat-icon> Indicadores históricos de la capa Gold del Data Lake.
          Última actualización: {{ fmt(r.ultimaActualizacion) }}. Las fechas son de creación (America/Bogota).</div>
        @if (r.kpis.registros_demo > 0) {
          <div class="banner warn"><mat-icon>science</mat-icon> {{ r.kpis.registros_demo }} de {{ r.kpis.total }} tickets son datos de
            demostración. Desactiva «Incluir datos de demostración» para verlos sin ellos.</div>
        }
        <div class="tiles">
          <div class="tile"><div class="label">Solicitudes</div><div class="value">{{ num(r.kpis.total) }}</div>
            <div class="hint">{{ num(r.kpis.abiertos) }} abiertas</div></div>
          <div class="tile"><div class="label">Finalizadas</div><div class="value">{{ pct(r.kpis.pct_finalizados) }}</div>
            <div class="hint">{{ num(r.kpis.finalizados) }} tickets</div></div>
          <div class="tile"><div class="label">Atención promedio</div><div class="value">{{ dur(r.kpis.prom_atencion_min) }}</div>
            <div class="hint">asignación → inicio</div></div>
          <div class="tile"><div class="label">Resolución promedio</div><div class="value">{{ dur(r.kpis.prom_resolucion_min) }}</div>
            <div class="hint">creación → cierre</div></div>
          <div class="tile"><div class="label">Acierto de la IA</div><div class="value">{{ pct(r.kpis.pct_ia_acierto) }}</div>
            <div class="hint">tipo propuesto = confirmado</div></div>
          <div class="tile"><div class="label">Asignación automática efectiva</div><div class="value">{{ pct(r.kpis.pct_asignacion_efectiva) }}</div>
            <div class="hint">sin reasignación</div></div>
          <div class="tile"><div class="label">Reincidentes</div><div class="value">{{ num(r.kpis.reincidentes) }}</div>
            <div class="hint">misma falla ≤ 30 días</div></div>
          <div class="tile"><div class="label">Satisfacción</div><div class="value">{{ r.kpis.satisfaccion ? num(r.kpis.satisfaccion, 1) + '/5' : '—' }}</div></div>
        </div>

        <div class="grid grid-2 gap">
          <div class="card"><h3 class="section-title">Solicitudes creadas y finalizadas</h3>
            <app-chart [spec]="serieSpec()" label="Evolución de solicitudes" /></div>
          <div class="card"><h3 class="section-title">Tickets abiertos al final de cada día</h3>
            <app-chart [spec]="pendSpec()" label="Evolución de pendientes" /></div>
          <div class="card"><h3 class="section-title">Por categoría</h3>
            <app-chart [spec]="catSpec()" label="Tickets por categoría" /></div>
          <div class="card"><h3 class="section-title">Fallas más frecuentes</h3>
            <app-chart [spec]="fallasSpec()" [height]="300" label="Fallas más frecuentes" /></div>
          <div class="card"><h3 class="section-title">Por prioridad</h3>
            <app-chart [spec]="prioSpec()" [height]="220" label="Tickets por prioridad" /></div>
          <div class="card"><h3 class="section-title">Por sede</h3>
            <app-chart [spec]="sedeSpec()" [height]="220" label="Tickets por sede" /></div>
          <div class="card"><h3 class="section-title">Día de la semana</h3>
            <app-chart [spec]="diaSpec()" [height]="220" label="Solicitudes por día de la semana" /></div>
          <div class="card"><h3 class="section-title">Efectividad de la IA por origen</h3>
            <app-chart [spec]="iaSpec()" [height]="220" label="Resultados de los diagnósticos de IA" />
            <table class="simple small"><thead><tr><th>Origen</th><th class="num">Total</th><th class="num">Acertados</th><th class="num">Corregidos</th>
              <th class="num">No concluyentes</th><th class="num">Confianza</th></tr></thead>
              <tbody>@for (x of r.ia; track x.origen) {
                <tr><td>{{ x.origen }}</td><td class="num">{{ x.total }}</td><td class="num">{{ x.acertados }}</td><td class="num">{{ x.corregidos }}</td>
                  <td class="num">{{ x.no_concluyentes }}</td><td class="num">{{ pct(x.confianza_prom) }}</td></tr>
              }</tbody></table></div>
        </div>

        <div class="grid grid-2 gap">
          <div class="card"><h3 class="section-title">Rendimiento de técnicos</h3>
            <div class="table-wrap"><table class="simple"><thead><tr><th>Técnico</th><th class="num">Resueltos</th><th class="num">Abiertos</th>
              <th class="num">Resolución</th><th class="num">Con reapertura</th><th class="num">Satisfacción</th></tr></thead>
              <tbody>@for (t of r.tecnicos; track t.tecnico_id) {
                <tr><td>{{ t.nombre ?? t.tecnico_id }}</td><td class="num">{{ t.resueltos }}</td><td class="num">{{ t.abiertos }}</td>
                  <td class="num">{{ dur(t.prom_resolucion_min) }}</td><td class="num">{{ t.con_reapertura }}</td>
                  <td class="num">{{ t.satisfaccion ? num(t.satisfaccion, 1) : '—' }}</td></tr>
              } @empty { <tr><td colspan="6" class="empty">Sin datos</td></tr> }</tbody></table></div></div>
          <div class="card"><h3 class="section-title">Categorías con mayor crecimiento</h3>
            <p class="small muted">Frente al periodo anterior de igual duración.</p>
            <table class="simple"><thead><tr><th>Categoría</th><th class="num">Actual</th><th class="num">Anterior</th><th class="num">Variación</th></tr></thead>
              <tbody>@for (c of r.crecimiento; track c.codigo) {
                <tr><td>{{ c.nombre }}</td><td class="num">{{ c.actual }}</td><td class="num">{{ c.anterior }}</td>
                  <td class="num">{{ c.variacion === null ? 'nueva' : pct(c.variacion) }}</td></tr>
              }</tbody></table></div>
          <div class="card"><h3 class="section-title">Equipos con más fallas · mantenimiento preventivo</h3>
            <table class="simple"><thead><tr><th>Equipo</th><th>Tipo</th><th>Sede</th><th class="num">Tickets</th><th class="num">Reincidentes</th><th></th></tr></thead>
              <tbody>@for (e of r.equipos; track e.codigo) {
                <tr><td>{{ e.codigo }}</td><td>{{ e.tipo }} {{ e.marca }}</td><td>{{ e.sede }}</td><td class="num">{{ e.total }}</td>
                  <td class="num">{{ e.reincidentes }}</td>
                  <td>@if (e.candidato_mantenimiento) { <span class="chip prio-ALTA"><mat-icon class="ic">build</mat-icon> preventivo</span> }</td></tr>
              } @empty { <tr><td colspan="6" class="empty">Sin equipos asociados</td></tr> }</tbody></table></div>
          <div class="card"><h3 class="section-title">Reincidencia por categoría</h3>
            <table class="simple"><thead><tr><th>Categoría</th><th class="num">Reincidentes</th><th class="num">Total</th></tr></thead>
              <tbody>@for (x of r.reincidencia; track x.codigo) {
                <tr><td>{{ x.nombre }}</td><td class="num">{{ x.reincidentes }}</td><td class="num">{{ x.total }}</td></tr>
              } @empty { <tr><td colspan="3" class="empty">Sin reincidencias en el periodo</td></tr> }</tbody></table></div>
        </div>
      }
    </div>
  `,
  styles: [`.filters { margin-bottom: 12px; display: flex; flex-direction: column; gap: 12px; } .spacer { flex: 1; } .gap { margin-top: 16px; }
    .ic { font-size: 14px; width: 14px; height: 14px; }`],
})
export class AnalyticsPage {
  private http = inject(HttpClient);
  readonly catalog = inject(CatalogService);
  readonly r = signal<Row | null>(null);
  readonly tecnicos = signal<Tecnico[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly sinDatos = signal(false);
  readonly prioList = Object.entries(PRIORIDADES);
  readonly estList = Object.entries(ESTADOS);
  readonly num = num; readonly pct = pct; readonly dur = duracion; readonly fmt = fecha;
  desde = isoDate(new Date(Date.now() - 90 * 86400000)); hasta = isoDate(new Date());
  sede = ''; categoria = ''; tecnico = ''; prioridad = ''; estado = ''; agrupacion = 'DIA'; incluirDemo = true;

  readonly serieSpec = computed<ChartSpec>(() => {
    const s: Row[] = this.r()?.['serie'] ?? [];
    return { type: 'line', labels: s.map(x => String(x['periodo']).slice(0, 10)),
      datasets: [{ label: 'Creadas', data: s.map(x => x['creados']) }, { label: 'Finalizadas', data: s.map(x => x['finalizados']) }] };
  });
  readonly pendSpec = computed<ChartSpec>(() => {
    const s: Row[] = this.r()?.['pendientes'] ?? [];
    return { type: 'line', labels: s.map(x => x['fecha']),
      datasets: [{ label: 'Abiertos', data: s.map(x => x['abiertos']) }, { label: 'Abiertos hace más de 3 días', data: s.map(x => x['mas_de_3_dias']) }] };
  });
  readonly catSpec = computed<ChartSpec>(() => this.bars(this.r()?.['porCategoria'], 'Tickets', true));
  readonly fallasSpec = computed<ChartSpec>(() => this.bars(this.r()?.['topFallas'], 'Tickets', true));
  readonly prioSpec = computed<ChartSpec>(() => this.bars(this.r()?.['porPrioridad'], 'Tickets'));
  readonly sedeSpec = computed<ChartSpec>(() => this.bars(this.r()?.['porSede'], 'Tickets'));
  readonly diaSpec = computed<ChartSpec>(() => this.bars(this.r()?.['porDiaSemana'], 'Tickets'));
  readonly iaSpec = computed<ChartSpec>(() => {
    const s: Row[] = this.r()?.['ia'] ?? [];
    return { type: 'bar', stacked: true, horizontal: true, labels: s.map(x => x['origen'] === 'IMAGEN' ? 'Imagen' : 'Texto'),
      datasets: [{ label: 'Acertados', data: s.map(x => x['acertados']), color: SERIES[2] },
                 { label: 'Corregidos', data: s.map(x => x['corregidos']), color: SERIES[1] },
                 { label: 'Pendientes de cierre', data: s.map(x => x['pendientes']), color: '#9aa0a8' },
                 { label: 'No concluyentes', data: s.map(x => x['no_concluyentes']), color: '#c9ccd1' }] };
  });

  constructor() {
    this.catalog.load();
    this.http.get<Tecnico[]>('/api/v1/tecnicos').subscribe({ next: t => this.tecnicos.set(t), error: () => {} });
    this.load();
  }

  private bars(rows: Row[] | undefined, label: string, horizontal = false): ChartSpec {
    const s = rows ?? [];
    return { type: 'bar', horizontal, labels: s.map(x => x['nombre'] ?? x['codigo']), datasets: [{ label, data: s.map(x => x['total']) }] };
  }

  load(): void {
    let p = new HttpParams().set('desde', this.desde).set('hasta', this.hasta).set('agrupacion', this.agrupacion)
      .set('incluirDemo', this.incluirDemo);
    for (const [k, v] of Object.entries({ sede: this.sede, categoria: this.categoria, tecnico: this.tecnico,
      prioridad: this.prioridad, estado: this.estado })) if (v) p = p.set(k, v);
    this.loading.set(true);
    this.http.get<Row>('/api/v1/analitica/resumen', { params: p }).subscribe({
      next: r => { this.r.set(r); this.error.set(''); this.loading.set(false); },
      error: (e: HttpErrorResponse) => {
        this.sinDatos.set(e.error?.codigo === 'LAKE_SIN_DATOS');
        this.error.set(errorMessage(e)); this.r.set(null); this.loading.set(false);
      },
    });
  }
}
