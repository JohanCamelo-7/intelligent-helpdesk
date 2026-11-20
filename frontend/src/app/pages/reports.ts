import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { errorMessage } from '../core/auth.interceptor';
import { CatalogService } from '../core/catalog.service';
import { fecha, isoDate } from '../core/format';

interface Job { id: string; tipo: string; formato: string; estado: string; error?: string; creadoEn: string; terminadoEn?: string; }

@Component({
  selector: 'app-reports',
  imports: [FormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatSlideToggleModule],
  template: `
    <div class="page">
      <div class="page-header"><h1>Reportes</h1></div>
      <div class="grid grid-2">
        <div class="card form">
          <h3 class="section-title">Nuevo reporte</h3>
          <div class="row">
            <mat-form-field appearance="outline"><mat-label>Tipo</mat-label><mat-select [(ngModel)]="tipo">
              @for (t of tipos; track t[0]) { <mat-option [value]="t[0]">{{ t[1] }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Formato</mat-label><mat-select [(ngModel)]="formato">
              <mat-option value="XLSX">Excel</mat-option><mat-option value="CSV">CSV</mat-option><mat-option value="PDF">PDF</mat-option></mat-select></mat-form-field>
          </div>
          <div class="row">
            <mat-form-field appearance="outline"><mat-label>Periodo</mat-label><mat-select [(ngModel)]="preset" (selectionChange)="aplicarPreset()">
              <mat-option value="dia">Diario (hoy)</mat-option><mat-option value="semana">Semanal (7 días)</mat-option>
              <mat-option value="mes">Mensual (30 días)</mat-option><mat-option value="rango">Rango personalizado</mat-option></mat-select></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Agrupación de la serie</mat-label><mat-select [(ngModel)]="agrupacion">
              <mat-option value="DIA">Día</mat-option><mat-option value="SEMANA">Semana</mat-option><mat-option value="MES">Mes</mat-option></mat-select></mat-form-field>
          </div>
          <div class="row">
            <mat-form-field appearance="outline"><mat-label>Desde</mat-label><input matInput type="date" [(ngModel)]="desde" (change)="preset='rango'"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Hasta</mat-label><input matInput type="date" [(ngModel)]="hasta" (change)="preset='rango'"></mat-form-field>
          </div>
          <div class="row">
            <mat-form-field appearance="outline"><mat-label>Sede</mat-label><mat-select [(ngModel)]="sede"><mat-option [value]="''">Todas</mat-option>
              @for (s of catalog.catalogo()?.sedes ?? []; track s.id) { <mat-option [value]="s.codigo">{{ s.nombre }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Categoría</mat-label><mat-select [(ngModel)]="categoria"><mat-option [value]="''">Todas</mat-option>
              @for (c of catalog.catalogo()?.categorias ?? []; track c.id) { <mat-option [value]="c.codigo">{{ c.nombre }}</mat-option> }</mat-select></mat-form-field>
          </div>
          <mat-slide-toggle [(ngModel)]="incluirDemo">Incluir datos de demostración</mat-slide-toggle>
          <button mat-flat-button (click)="solicitar()"><mat-icon>play_arrow</mat-icon> Generar</button>
          <p class="small muted">Los reportes se generan en segundo plano desde la capa Gold e incluyen las reglas de contabilización y
            la fecha de la última actualización del Data Lake.</p>
        </div>
        <div class="card">
          <h3 class="section-title">Mis reportes</h3>
          <table class="simple"><thead><tr><th>Tipo</th><th>Formato</th><th>Estado</th><th>Solicitado</th><th></th></tr></thead>
            <tbody>@for (j of jobs(); track j.id) {
              <tr><td>{{ j.tipo }}</td><td>{{ j.formato }}</td>
                <td [title]="j.error ?? ''" [class.bad]="j.estado === 'ERROR'">{{ j.estado }}</td><td class="small muted">{{ fmt(j.creadoEn) }}</td>
                <td>@if (j.estado === 'LISTO') { <button mat-icon-button (click)="descargar(j)" aria-label="Descargar"><mat-icon>download</mat-icon></button> }</td></tr>
              @if (j.error) { <tr><td colspan="5" class="small bad">{{ j.error }}</td></tr> }
            } @empty { <tr><td colspan="5" class="empty">Aún no has generado reportes.</td></tr> }</tbody></table>
        </div>
      </div>
    </div>
  `,
  styles: [`.form { display: flex; flex-direction: column; gap: 8px; } .bad { color: var(--hd-bad); }`],
})
export class ReportsPage {
  private http = inject(HttpClient);
  private snack = inject(MatSnackBar);
  readonly catalog = inject(CatalogService);
  readonly jobs = signal<Job[]>([]);
  readonly fmt = fecha;
  readonly tipos: [string, string][] = [['RESUMEN', 'Resumen general'], ['DETALLE_TICKETS', 'Detalle de tickets'], ['TECNICOS', 'Rendimiento por técnico'],
    ['CATEGORIAS', 'Por categoría y crecimiento'], ['SEDES', 'Por sede'], ['PRIORIDADES', 'Por prioridad'],
    ['REINCIDENCIA', 'Fallas recurrentes y mantenimiento'], ['IA', 'Efectividad de la IA']];
  tipo = 'RESUMEN'; formato = 'XLSX'; preset = 'mes'; agrupacion = 'DIA'; sede = ''; categoria = ''; incluirDemo = true;
  desde = ''; hasta = '';

  constructor() {
    this.catalog.load();
    this.aplicarPreset();
    this.load();
    const id = setInterval(() => { if (this.jobs().some(j => j.estado === 'EN_COLA' || j.estado === 'PROCESANDO')) this.load(); }, 3000);
    inject(DestroyRef).onDestroy(() => clearInterval(id));
  }

  aplicarPreset(): void {
    const dias = { dia: 0, semana: 6, mes: 29 }[this.preset as 'dia' | 'semana' | 'mes'];
    if (dias === undefined) return;
    this.hasta = isoDate(new Date());
    this.desde = isoDate(new Date(Date.now() - dias * 86400000));
    this.agrupacion = this.preset === 'mes' ? 'DIA' : this.agrupacion;
  }

  load(): void { this.http.get<Job[]>('/api/v1/reportes').subscribe(j => this.jobs.set(j)); }

  async solicitar() {
    try {
      await firstValueFrom(this.http.post('/api/v1/reportes', { tipo: this.tipo, formato: this.formato, desde: this.desde, hasta: this.hasta,
        sede: this.sede || null, categoria: this.categoria || null, incluirDemo: this.incluirDemo, agrupacion: this.agrupacion }));
      this.snack.open('Reporte en cola', 'OK', { duration: 2500 });
      this.load();
    } catch (e) { this.snack.open(errorMessage(e), 'Cerrar', { duration: 6000 }); }
  }

  descargar(j: Job): void {
    this.http.get(`/api/v1/reportes/${j.id}/descarga`, { responseType: 'blob' }).subscribe(b => {
      const url = URL.createObjectURL(b);
      const a = document.createElement('a');
      a.href = url;
      a.download = `reporte-${j.tipo.toLowerCase()}.${j.formato.toLowerCase()}`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
  }
}
