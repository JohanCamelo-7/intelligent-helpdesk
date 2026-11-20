import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';
import { errorMessage } from '../core/auth.interceptor';
import { fecha } from '../core/format';

@Component({
  selector: 'app-admin-config',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatIconModule, MatProgressBarModule],
  template: `
    <div class="page">
      <div class="page-header"><h1>Configuración</h1></div>
      <div class="grid grid-2">
        <div class="card">
          <h3 class="section-title">Algoritmo de asignación</h3>
          <p class="small muted">S = wE·Especialidad + wX·Experiencia + wC·(1 − Carga) + wD·Desempeño. Los pesos deben sumar 1.
            En prioridad alta o crítica se intercambian wX y wC.</p>
          <div class="row">
            @for (k of pesos; track k[0]) {
              <mat-form-field appearance="outline"><mat-label>{{ k[1] }}</mat-label>
                <input matInput type="number" step="0.05" min="0" max="1" [(ngModel)]="cfg[k[0]]"></mat-form-field>
            }
          </div>
          <div class="row">
            <mat-form-field appearance="outline"><mat-label>Umbral de confianza IA</mat-label>
              <input matInput type="number" step="0.05" min="0" max="1" [(ngModel)]="cfg.umbralConfianza"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Puntaje mínimo</mat-label>
              <input matInput type="number" step="0.05" min="0" max="1" [(ngModel)]="cfg.umbralPuntaje"></mat-form-field>
          </div>
          <div class="row"><span class="small" [class.bad]="suma() !== 1">Suma de pesos: {{ suma() }}</span>
            <span class="spacer"></span><button mat-flat-button [disabled]="suma() !== 1" (click)="guardar()">Guardar</button></div>
        </div>

        <div class="card">
          <h3 class="section-title">Pipeline del Data Lake</h3>
          <p class="small muted">Se ejecuta cada hora en Airflow: extracción → Bronze → Silver (calidad) → Gold. Puedes lanzarlo ahora.</p>
          <button mat-flat-button (click)="ejecutar()"><mat-icon>play_circle</mat-icon> Ejecutar ahora</button>
          <button mat-button (click)="cargarPipeline()"><mat-icon>refresh</mat-icon> Actualizar</button>
          @if (pipeError()) { <div class="banner warn">{{ pipeError() }}</div> }
          <div class="table-wrap"><table class="simple small">
            <thead><tr><th>Paso</th><th>Estado</th><th>Inicio</th><th class="num">Filas</th><th class="num">Rechazadas</th></tr></thead>
            <tbody>@for (r of runs(); track $index) {
              <tr [title]="r.message"><td>{{ r.step }}</td><td [class.bad]="r.status !== 'OK'">{{ r.status }}</td>
                <td>{{ fmt(r.started_at) }}</td><td class="num">{{ r.rows_out }}</td><td class="num">{{ r.rows_rejected }}</td></tr>
            } @empty { <tr><td colspan="5" class="empty">Sin ejecuciones registradas todavía.</td></tr> }</tbody></table></div>
          <h4>Calidad de datos (últimas reglas)</h4>
          <div class="table-wrap"><table class="simple small">
            <thead><tr><th>Tabla</th><th>Regla</th><th>Severidad</th><th>Resultado</th><th class="num">Filas</th></tr></thead>
            <tbody>@for (q of calidad(); track $index) {
              <tr><td>{{ q.table_name }}</td><td>{{ q.rule_name }}</td><td>{{ q.severity }}</td>
                <td [class.bad]="!q.passed">{{ q.passed ? 'Cumple' : 'Falla' }}</td><td class="num">{{ q.failed_rows }}</td></tr>
            }</tbody></table></div>
        </div>

        <div class="card">
          <h3 class="section-title">Escenario de demostración</h3>
          <div class="banner warn"><mat-icon>science</mat-icon> Genera tickets HISTÓRICOS simulados (marcados es_demo = true y canal DEMO),
            recorriendo el ciclo real: eventos, outbox y Data Lake. El diagnóstico de IA de estos tickets es simulado.</div>
          <div class="row">
            <mat-form-field appearance="outline"><mat-label>Desde</mat-label><input matInput type="date" [(ngModel)]="esc.desde"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Tickets</mat-label><input matInput type="number" min="1" max="50000" [(ngModel)]="esc.tickets"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Usuarios</mat-label><input matInput type="number" min="1" max="500" [(ngModel)]="esc.usuarios"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Técnicos</mat-label><input matInput type="number" min="1" max="100" [(ngModel)]="esc.tecnicos"></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Administradores</mat-label><input matInput type="number" min="0" max="10" [(ngModel)]="esc.administradores"></mat-form-field>
          </div>
          <div class="row">
            <button mat-stroked-button (click)="preset('pequeno')">Escenario pequeño (150 tickets, 60 días)</button>
            <button mat-stroked-button (click)="preset('masivo')">Carga masiva (30.000 tickets desde 2024)</button>
          </div>
          <p class="small muted">La fecha final es hoy. Una carga masiva tarda aproximadamente entre 20 y 60 minutos según el equipo;
            puedes seguir usando la aplicación mientras se genera.</p>
          <button mat-flat-button (click)="demo()" [disabled]="generando()">Generar escenario</button>
          <button mat-button (click)="estadoDemo()">Ver estado</button>
          @if (avance() !== null) { <mat-progress-bar mode="determinate" [value]="avance()!" class="avance"></mat-progress-bar> }
          @if (demoEstado()) { <pre class="small">{{ demoEstado() }}</pre> }
          <p class="small muted">Cuentas de demostración (contraseña Demo1234!): usuario1…N&#64;demo.helpdesk.local,
            tecnico1…N&#64;demo.helpdesk.local y admin1…N&#64;demo.helpdesk.local.</p>
        </div>
      </div>
    </div>
  `,
  styles: [`.spacer { flex: 1; } .avance { margin: 12px 0; } .bad { color: var(--hd-bad); font-weight: 500; } pre { white-space: pre-wrap; background: #f4f5f7; padding: 8px; border-radius: 8px; }`],
})
export class AdminConfigPage implements OnDestroy {
  private http = inject(HttpClient);
  private snack = inject(MatSnackBar);
  readonly fmt = fecha;
  readonly pesos: [string, string][] = [['especialidad', 'wE especialidad'], ['experiencia', 'wX experiencia'], ['carga', 'wC carga'], ['desempeno', 'wD desempeño']];
  cfg: Record<string, number> = { especialidad: 0.4, experiencia: 0.2, carga: 0.25, desempeno: 0.15, umbralConfianza: 0.75, umbralPuntaje: 0.6 };
  readonly runs = signal<any[]>([]);
  readonly calidad = signal<any[]>([]);
  readonly pipeError = signal('');
  readonly demoEstado = signal('');
  readonly generando = signal(false);
  readonly avance = signal<number | null>(null);
  esc = { desde: this.haceDias(60), tickets: 150, usuarios: 8, tecnicos: 6, administradores: 0 };
  private sondeo?: ReturnType<typeof setInterval>;

  constructor() {
    this.http.get<any>('/api/v1/config/asignacion').subscribe(v => this.cfg = {
      ...v.pesos, umbralConfianza: v.umbralConfianza, umbralPuntaje: v.umbralPuntaje });
    this.cargarPipeline();
    this.estadoDemo();   // si hay una carga en curso, retoma el seguimiento
  }

  suma(): number { return Math.round((+this.cfg['especialidad'] + +this.cfg['experiencia'] + +this.cfg['carga'] + +this.cfg['desempeno']) * 1000) / 1000; }

  async guardar() {
    try { await firstValueFrom(this.http.put('/api/v1/config/asignacion', {
      especialidad: +this.cfg['especialidad'], experiencia: +this.cfg['experiencia'], carga: +this.cfg['carga'],
      desempeno: +this.cfg['desempeno'], umbralConfianza: +this.cfg['umbralConfianza'], umbralPuntaje: +this.cfg['umbralPuntaje'] })); this.snack.open('Configuración guardada', 'OK', { duration: 3000 }); }
    catch (e) { this.snack.open(errorMessage(e), 'Cerrar', { duration: 6000 }); }
  }

  cargarPipeline(): void {
    this.http.get<any>('/api/v1/analitica/pipeline/ejecuciones').subscribe({
      next: r => { this.runs.set(r.lago); this.pipeError.set(''); },
      error: e => this.pipeError.set(errorMessage(e)),
    });
    this.http.get<any[]>('/api/v1/analitica/calidad').subscribe({ next: q => this.calidad.set(q), error: () => {} });
  }

  async ejecutar() {
    try { const r: any = await firstValueFrom(this.http.post('/api/v1/analitica/pipeline/ejecutar', {}));
      this.snack.open(`Pipeline lanzado (${r.dag_run_id}). Tarda unos minutos.`, 'OK', { duration: 5000 }); }
    catch (e) { this.snack.open(errorMessage(e), 'Cerrar', { duration: 6000 }); }
  }

  preset(tipo: 'pequeno' | 'masivo'): void {
    this.esc = tipo === 'masivo'
      ? { desde: '2024-01-01', tickets: 30000, usuarios: 100, tecnicos: 25, administradores: 2 }
      : { desde: this.haceDias(60), tickets: 150, usuarios: 8, tecnicos: 6, administradores: 0 };
  }

  private haceDias(n: number): string {
    const d = new Date(Date.now() - n * 86_400_000);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  async demo() {
    // Cada ejecución AGREGA tickets: confirmar evita duplicar una carga masiva por un doble clic.
    const n = +this.esc.tickets;
    if (!confirm(`Se crearán ${n.toLocaleString('es-CO')} tickets de demostración NUEVOS, sumados a los que ya existan. ` +
                 `Si ya generaste este escenario, se duplicará. ¿Continuar?`)) return;
    try {
      await firstValueFrom(this.http.post('/api/v1/demo/escenario', {
        desde: this.esc.desde, tickets: +this.esc.tickets, usuarios: +this.esc.usuarios,
        tecnicos: +this.esc.tecnicos, administradores: +this.esc.administradores }));
      this.snack.open('Generando escenario…', 'OK', { duration: 3000 });
      this.seguir();
    } catch (e) { this.snack.open(errorMessage(e), 'Cerrar', { duration: 6000 }); }
  }

  /** Consulta el estado cada 5 s mientras el escenario se genera. */
  private seguir(): void {
    clearInterval(this.sondeo);
    this.estadoDemo();
    this.sondeo = setInterval(() => this.estadoDemo(), 5000);
  }

  estadoDemo(): void {
    this.http.get<any>('/api/v1/demo/estado').subscribe(r => {
      const u = r.ultimo ?? {};
      this.demoEstado.set(JSON.stringify(u, null, 2));
      const enProceso = u.estado === 'EN_PROCESO';
      this.generando.set(enProceso);
      this.avance.set(enProceso ? (u.porcentaje ?? 0) : u.estado === 'COMPLETADO' ? 100 : null);
      if (enProceso && !this.sondeo) this.seguir();
      if (!enProceso) { clearInterval(this.sondeo); this.sondeo = undefined; }
    });
  }

  ngOnDestroy(): void { clearInterval(this.sondeo); }
}
