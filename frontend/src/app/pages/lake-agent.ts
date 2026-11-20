import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TimeoutError, firstValueFrom, timeout } from 'rxjs';
import { errorMessage } from '../core/auth.interceptor';
import { ChartComponent, ChartSpec } from '../shared/chart';

interface Columna { nombre: string; tipo: string; }
interface Intento { sql: string; error: string | null; }
interface RespuestaLago {
  pregunta: string; respuesta: string; explicacion?: string; sql: string | null; columnas: Columna[];
  filas: unknown[][]; truncado?: boolean; consulta_ms?: number; duracion_ms?: number;
  grafico?: { tipo: 'barras' | 'linea' | 'ninguno'; x?: string; y?: string };
  modo: 'ia' | 'plantillas'; modelo?: string | null; intentos: Intento[]; aclaracion: boolean;
  sugerencias?: string[]; advertencias?: string[];
}
interface EstadoLago { modo: 'ia' | 'plantillas'; modelo: string | null; tablas: string[]; sugerencias: string[]; max_filas: number; }
interface Turno { pregunta: string; r?: RespuestaLago; error?: string; verSql?: boolean; verTodo?: boolean; }

const FILAS_VISIBLES = 15;

@Component({
  selector: 'app-lake-agent',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatTooltipModule, ChartComponent],
  template: `
    <div class="page narrow">
      <div class="page-header">
        <div>
          <h1>Preguntar al Data Lake</h1>
          <p class="muted small sub">Pregunta en español; el agente consulta la capa Gold con Trino (solo lectura) y te muestra
            la respuesta, los datos y la consulta SQL que usó.</p>
        </div>
        @if (estado(); as e) {
          <span class="chip modo" [matTooltip]="e.modo === 'ia' ? 'Genera el SQL con el modelo ' + e.modelo : 'Sin modelo de lenguaje configurado: interpreta la pregunta con reglas'">
            <mat-icon>{{ e.modo === 'ia' ? 'auto_awesome' : 'rule' }}</mat-icon>
            {{ e.modo === 'ia' ? 'IA · ' + e.modelo : 'Modo plantillas' }}
          </span>
        }
      </div>

      <div class="turns" #lista>
        @if (!turnos().length) {
          <div class="card intro">
            <div class="section-title">Prueba con una de estas preguntas</div>
            <div class="sugs">
              @for (s of estado()?.sugerencias ?? []; track s) {
                <button mat-stroked-button (click)="preguntar(s)">{{ s }}</button>
              }
            </div>
            <p class="muted small">Los datos actuales son de demostración (30.000 tickets simulados de Bogotá, Bello y Girardot).
              Puedes hacer preguntas de seguimiento, por ejemplo «¿y en Bello?».</p>
          </div>
        }
        @for (t of turnos(); track $index) {
          <div class="q">{{ t.pregunta }}</div>
          @if (t.r; as r) {
            <div class="card a" [class.aclara]="r.aclaracion">
              @for (w of r.advertencias ?? []; track w) { <div class="banner warn"><mat-icon>info</mat-icon>{{ w }}</div> }
              <p class="answer">{{ r.respuesta }}</p>
              @if (r.explicacion) { <p class="muted small">{{ r.explicacion }}</p> }

              @if (grafica(r); as g) {
                <app-chart [spec]="g" [height]="240" [label]="r.pregunta" />
              }

              @if (r.columnas.length && r.filas.length) {
                <div class="table-wrap">
                  <table class="simple">
                    <thead><tr>@for (c of r.columnas; track c.nombre) { <th [class.num]="esNum(c)">{{ titulo(c.nombre) }}</th> }</tr></thead>
                    <tbody>
                      @for (f of (t.verTodo ? r.filas : r.filas.slice(0, filasVisibles)); track $index) {
                        <tr>@for (v of f; track $index; let i = $index) { <td [class.num]="esNum(r.columnas[i])">{{ valor(v) }}</td> }</tr>
                      }
                    </tbody>
                  </table>
                </div>
                @if (r.filas.length > filasVisibles) {
                  <button mat-button (click)="alternar(t, 'verTodo')">{{ t.verTodo ? 'Ver menos' : 'Ver las ' + r.filas.length + ' filas' }}</button>
                }
                @if (r.truncado) { <p class="muted small">Se muestran las primeras {{ r.filas.length }} filas.</p> }
              }

              @if (r.aclaracion && r.sugerencias?.length) {
                <div class="sugs">@for (s of r.sugerencias!.slice(0, 6); track s) { <button mat-stroked-button (click)="preguntar(s)">{{ s }}</button> }</div>
              }

              <div class="meta">
                <span class="chip"><mat-icon>{{ r.modo === 'ia' ? 'auto_awesome' : 'rule' }}</mat-icon>{{ r.modo === 'ia' ? 'IA' : 'Plantillas' }}</span>
                @if (r.consulta_ms != null) { <span class="muted small">Trino: {{ r.consulta_ms }} ms · total {{ r.duracion_ms }} ms</span> }
                @if (correcciones(r) > 0) {
                  <span class="chip warn" matTooltip="El agente recibió el error y corrigió la consulta">
                    <mat-icon>build</mat-icon>{{ correcciones(r) }} {{ correcciones(r) === 1 ? 'corrección' : 'correcciones' }}</span>
                }
                <span class="spacer"></span>
                @if (r.sql) {
                  <button mat-button (click)="alternar(t, 'verSql')"><mat-icon>code</mat-icon> {{ t.verSql ? 'Ocultar SQL' : 'Ver SQL' }}</button>
                }
                @if (r.filas.length) {
                  <button mat-button (click)="csv(r)"><mat-icon>download</mat-icon> CSV</button>
                }
              </div>
              @if (t.verSql) {
                @for (i of r.intentos; track $index) {
                  @if (i.error) { <div class="sql-error small"><mat-icon>error_outline</mat-icon>Intento {{ $index + 1 }} rechazado: {{ i.error }}</div> }
                  <pre class="sql" [class.bad]="i.error">{{ i.sql }}</pre>
                }
                <button mat-button (click)="copiar(r.sql!)"><mat-icon>content_copy</mat-icon> Copiar SQL</button>
              }
            </div>
          } @else if (t.error) {
            <div class="banner error"><mat-icon>error</mat-icon>{{ t.error }}</div>
          } @else {
            <div class="card a pensando"><span class="dot"></span><span class="dot"></span><span class="dot"></span>
              <span class="muted small">Consultando el Data Lake…</span></div>
          }
        }
      </div>

      <div class="card composer">
        <mat-form-field appearance="outline" class="input" subscriptSizing="dynamic">
          <input matInput [(ngModel)]="texto" (keyup.enter)="preguntar()" maxlength="500" [disabled]="ocupado()"
                 placeholder="Ej.: ¿Qué sede tarda más en resolver los tickets de redes en 2025?">
        </mat-form-field>
        <button mat-flat-button (click)="preguntar()" [disabled]="!texto.trim() || ocupado()"><mat-icon>send</mat-icon> Preguntar</button>
        @if (turnos().length) {
          <button mat-icon-button (click)="limpiar()" [disabled]="ocupado()" matTooltip="Nueva conversación" aria-label="Nueva conversación">
            <mat-icon>restart_alt</mat-icon></button>
        }
      </div>
      <p class="muted small">El agente solo puede leer las tablas Gold y ops: una guardia valida cada consulta y Trino la ejecuta con
        un usuario de solo lectura. Revisa el SQL antes de usar una cifra en un informe.</p>
    </div>
  `,
  styles: [`
    .narrow { max-width: 980px; }
    .sub { margin: 4px 0 0; max-width: 640px; }
    .modo mat-icon, .chip mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .modo { border-color: var(--hd-border); }
    .turns { display: flex; flex-direction: column; gap: 10px; margin-bottom: 12px; }
    .intro .sugs, .sugs { display: flex; gap: 8px; flex-wrap: wrap; margin: 8px 0; }
    .q { align-self: flex-end; background: #2a78d6; color: #fff; padding: 10px 14px; border-radius: 14px;
         border-bottom-right-radius: 4px; max-width: 80%; }
    .a { display: flex; flex-direction: column; gap: 10px; }
    .a.aclara { background: #fbfcfe; }
    .answer { margin: 0; font-size: 16px; line-height: 1.45; }
    .num { text-align: right; }
    .meta { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; border-top: 1px solid var(--hd-border); padding-top: 8px; }
    .chip { border-color: var(--hd-border); color: var(--hd-muted); }
    .chip.warn { border-color: #f1d3a1; color: var(--hd-warn); }
    .spacer { flex: 1; }
    .sql { background: #f3f4f7; border: 1px solid var(--hd-border); border-radius: 8px; padding: 10px 12px; margin: 0;
           font: 12.5px/1.5 ui-monospace, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
    .sql.bad { border-color: #f0c4bf; background: #fdf5f4; }
    .sql-error { display: flex; gap: 6px; align-items: center; color: var(--hd-bad); }
    .sql-error mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .pensando { flex-direction: row; align-items: center; gap: 6px; }
    .dot { width: 7px; height: 7px; border-radius: 50%; background: #2a78d6; animation: pulso 1.2s infinite; }
    .dot:nth-child(2) { animation-delay: .2s; } .dot:nth-child(3) { animation-delay: .4s; }
    @keyframes pulso { 0%, 80%, 100% { opacity: .25; } 40% { opacity: 1; } }
    .composer { display: flex; gap: 10px; align-items: center; padding: 10px 12px; position: sticky; bottom: 8px; z-index: 5;
                box-shadow: 0 -6px 16px rgba(246, 247, 249, .9); }
    .input { flex: 1; }
    @media (max-width: 600px) { .composer { flex-wrap: wrap; } .composer button[mat-flat-button] { flex: 1; } .q { max-width: 92%; } }
  `],
})
export class LakeAgentPage {
  private http = inject(HttpClient);
  private lista = viewChild<ElementRef<HTMLDivElement>>('lista');
  readonly estado = signal<EstadoLago | null>(null);
  readonly turnos = signal<Turno[]>([]);
  readonly ocupado = signal(false);
  readonly filasVisibles = FILAS_VISIBLES;
  texto = '';

  constructor() {
    firstValueFrom(this.http.get<EstadoLago>('/api/v1/agent/lago/estado'))
      .then(e => this.estado.set(e))
      .catch(() => this.estado.set(null));
  }

  async preguntar(texto?: string): Promise<void> {
    const pregunta = (texto ?? this.texto).trim();
    if (pregunta.length < 3 || this.ocupado()) return;
    const historial = this.turnos().filter(t => t.r && !t.r.aclaracion).slice(-4)
      .map(t => ({ pregunta: t.pregunta, sql: t.r!.sql }));
    const turno: Turno = { pregunta };
    this.turnos.update(l => [...l, turno]);
    this.texto = '';
    this.ocupado.set(true);
    this.bajar();
    try {
      turno.r = await firstValueFrom(this.http.post<RespuestaLago>('/api/v1/agent/lago/preguntar', { pregunta, historial })
        .pipe(timeout(88_000)));
    } catch (e) {
      turno.error = e instanceof TimeoutError ? 'La consulta tardó demasiado. Prueba con un periodo más corto o una pregunta más concreta.'
        : 'No se pudo consultar el Data Lake: ' + errorMessage(e);
    } finally {
      this.turnos.update(l => [...l]);
      this.ocupado.set(false);
      this.bajar();
    }
  }

  limpiar(): void { this.turnos.set([]); }

  alternar(t: Turno, campo: 'verSql' | 'verTodo'): void { t[campo] = !t[campo]; this.turnos.update(l => [...l]); }

  correcciones(r: RespuestaLago): number { return r.intentos.filter(i => i.error).length; }

  esNum(c?: Columna): boolean {
    return !!c && /^(bigint|integer|smallint|tinyint|double|real|decimal)/.test(c.tipo);
  }

  titulo(nombre: string): string {
    const t = nombre.replace(/_/g, ' ');
    return t.charAt(0).toUpperCase() + t.slice(1);
  }

  valor(v: unknown): string {
    if (v === null || v === undefined) return '—';
    if (typeof v === 'boolean') return v ? 'Sí' : 'No';
    if (typeof v === 'number') return v.toLocaleString('es-CO', { maximumFractionDigits: 2 });
    if (typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v) && v.length < 16) {
      return Number(v).toLocaleString('es-CO', { maximumFractionDigits: 2 });
    }
    return String(v);
  }

  grafica(r: RespuestaLago): ChartSpec | null {
    const g = r.grafico;
    if (!g || g.tipo === 'ninguno' || r.filas.length < 2 || r.filas.length > 60) return null;
    const ix = r.columnas.findIndex(c => c.nombre === g.x);
    const iy = r.columnas.findIndex(c => c.nombre === g.y);
    if (ix < 0 || iy < 0) return null;
    const datos = r.filas.map(f => Number(f[iy]));
    if (datos.some(d => Number.isNaN(d))) return null;
    return {
      type: g.tipo === 'linea' ? 'line' : 'bar',
      labels: r.filas.map(f => String(f[ix] ?? '—')),
      datasets: [{ label: this.titulo(g.y!), data: datos }],
      horizontal: g.tipo === 'barras' && r.filas.length > 8,
    };
  }

  csv(r: RespuestaLago): void {
    const esc = (v: unknown) => {
      const s = v === null || v === undefined ? '' : String(v);
      return /[";\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const lineas = [r.columnas.map(c => esc(c.nombre)).join(';'), ...r.filas.map(f => f.map(esc).join(';'))];
    const blob = new Blob(['﻿' + lineas.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'consulta-data-lake.csv';
    a.click();
    URL.revokeObjectURL(a.href);
  }

  copiar(sql: string): void { navigator.clipboard?.writeText(sql).catch(() => undefined); }

  private bajar(): void {
    setTimeout(() => {
      const el = this.lista()?.nativeElement;
      el?.lastElementChild?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
  }
}
