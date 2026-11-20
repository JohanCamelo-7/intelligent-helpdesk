import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { errorMessage } from '../core/auth.interceptor';
import { AuthService } from '../core/auth.service';
import { CatalogService } from '../core/catalog.service';
import { ESTADOS, IMPACTOS, PRIORIDADES, duracion, fecha, pct } from '../core/format';
import { Recomendaciones, TicketDetalle } from '../core/models';

@Component({
  selector: 'app-ticket-detail',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatProgressBarModule, MatTabsModule, MatExpansionModule],
  template: `
    <div class="page">
      @if (error()) { <div class="banner error"><mat-icon>error</mat-icon>{{ error() }}</div> }
      @if (t(); as d) {
        <div class="page-header">
          <div>
            <a routerLink="/tickets" class="muted small back"><mat-icon>arrow_back</mat-icon> Tickets</a>
            <h1>{{ d.resumen.numero }}
              <span class="chip estado-{{ d.resumen.estado }}">{{ estados[d.resumen.estado] }}</span>
              <span class="chip prio-{{ d.resumen.prioridad }}">Prioridad {{ prioridades[d.resumen.prioridad] }}</span>
              @if (d.resumen.esDemo) { <span class="chip demo">demostración</span> }
            </h1>
          </div>
          <button mat-icon-button (click)="reload()" aria-label="Actualizar"><mat-icon>refresh</mat-icon></button>
        </div>
        @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
        @if (d.resumen.requiereRevision && d.resumen.estado !== 'FINALIZADA') {
          <div class="banner warn"><mat-icon>flag</mat-icon> Este ticket requiere revisión humana antes de asignarse.</div>
        }

        <div class="layout">
          <div class="main">
            <div class="card">
              <p class="desc">{{ d.descripcionCompleta }}</p>
              <dl class="facts">
                <div><dt>Categoría</dt><dd>{{ d.resumen.categoria ?? 'Sin clasificar' }}</dd></div>
                <div><dt>Tipo de falla</dt><dd>{{ d.resumen.tipoFalla ?? '—' }}</dd></div>
                <div><dt>Sede</dt><dd>{{ d.resumen.sede }}</dd></div>
                <div><dt>Equipo</dt><dd>{{ d.resumen.equipo ?? 'No aplica' }}</dd></div>
                <div><dt>Impacto</dt><dd>{{ impactos[d.impacto] }}{{ d.bloqueaTrabajo ? ' · impide trabajar' : '' }}</dd></div>
                <div><dt>Técnico</dt><dd>{{ d.resumen.tecnicoNombre ?? 'Sin asignar' }}</dd></div>
                <div><dt>Solicitante</dt><dd>{{ d.resumen.solicitanteNombre }} · {{ d.cargoSolicitante }}
                  @if (d.correoSolicitante) { <br><span class="muted small">{{ d.correoSolicitante }}</span> }</dd></div>
                <div><dt>Creado</dt><dd>{{ fmt(d.resumen.fechaCreacion) }}</dd></div>
                <div><dt>Asignado</dt><dd>{{ fmt(d.fechaAsignacion) }}</dd></div>
                <div><dt>Inicio de atención</dt><dd>{{ fmt(d.fechaInicioAtencion) }}</dd></div>
                <div><dt>Finalizado</dt><dd>{{ fmt(d.resumen.fechaFinalizacion) }}</dd></div>
                @if (d.reaperturas) { <div><dt>Reaperturas</dt><dd>{{ d.reaperturas }}</dd></div> }
              </dl>
              @if (d.solucion) { <div class="solution"><strong>Solución:</strong> {{ d.solucion }}</div> }
            </div>

            @if (d.adjuntos.length) {
              <div class="card">
                <h3 class="section-title">Imágenes</h3>
                <div class="gallery">
                  @for (a of d.adjuntos; track a.id) {
                    @if (imgs()[a.id]; as url) { <a [href]="url" target="_blank" rel="noopener"><img [src]="url" [alt]="a.nombre"></a> }
                    @else { <div class="ph">Cargando…</div> }
                  }
                </div>
              </div>
            }

            <div class="card">
              <h3 class="section-title">Diagnóstico de inteligencia artificial</h3>
              @for (g of d.diagnosticos; track g.id) {
                <div class="diag">
                  <div class="diag-head">
                    <mat-icon>{{ g.origen === 'IMAGEN' ? 'image_search' : 'notes' }}</mat-icon>
                    <strong>{{ g.origen === 'IMAGEN' ? 'Análisis de imagen' : 'Análisis del texto' }}</strong>
                    <span class="muted small">{{ g.proveedor }} · {{ g.modelo }}</span>
                    <span class="spacer"></span>
                    <span class="chip" [class.ok]="g.estado === 'ACEPTADO'" [class.bad]="g.estado === 'CORREGIDO'">{{ g.estado }}</span>
                  </div>
                  @if (g.concluyente) {
                    <div><strong>{{ g.tipoFallaNombre ?? g.tipoFallaCodigo }}</strong> ({{ g.categoriaCodigo }})</div>
                  } @else { <div class="muted">No concluyente: se requiere revisión humana.</div> }
                  <div class="conf"><span>Confianza {{ pct(g.confianza) }}</span>
                    <div class="bar"><div [style.width.%]="g.confianza * 100" [class.low]="g.confianza < 0.75"></div></div></div>
                  @if (g.descripcion) { <p class="small">{{ g.descripcion }}</p> }
                  @if (g.causaProbable) { <p class="small"><strong>Posible causa:</strong> {{ g.causaProbable }}</p> }
                  @if (g.evidencias.length) { <ul class="small">@for (e of g.evidencias; track $index) { <li>{{ e }}</li> }</ul> }
                  @if (g.recomendacion) { <p class="small"><strong>Recomendación:</strong> {{ g.recomendacion }}</p> }
                  @if (g.estado === 'PROPUESTO' && g.concluyente && d.permisos.reclasificar) {
                    <div class="row"><button mat-stroked-button (click)="validar(g.id, true)">La IA acertó</button>
                      <button mat-stroked-button (click)="validar(g.id, false)">La IA se equivocó</button></div>
                  }
                </div>
              } @empty { <div class="muted">El análisis está en proceso o no hay resultados todavía. Actualiza en unos segundos.</div> }
            </div>

            <div class="card">
              <mat-tab-group>
                <mat-tab label="Historial">
                  <ul class="timeline">
                    @for (h of d.historial; track $index) {
                      <li><span class="muted small">{{ fmt(h.fecha) }}</span> · <strong>{{ h.tipo }}</strong>
                        @if (h.valorNuevo) { : {{ h.valorAnterior ? h.valorAnterior + ' → ' : '' }}{{ h.valorNuevo }} }
                        <span class="muted"> — {{ h.usuario }}</span>
                        @if (h.detalle) { <div class="small muted">{{ h.detalle }}</div> }</li>
                    }
                  </ul>
                </mat-tab>
                <mat-tab label="Actividades ({{ d.actividades.length }})">
                  <ul class="timeline">
                    @for (a of d.actividades; track a.id) {
                      <li><span class="muted small">{{ fmt(a.fecha) }}</span> · <strong>{{ a.tipo }}</strong> ({{ a.minutos }} min) — {{ a.tecnico }}
                        <div class="small">{{ a.descripcion }}</div></li>
                    } @empty { <li class="muted">Sin actividades registradas.</li> }
                  </ul>
                </mat-tab>
                @if (d.asignaciones.length) {
                  <mat-tab label="Asignaciones">
                    <ul class="timeline">
                      @for (a of d.asignaciones; track a.id) {
                        <li><span class="muted small">{{ fmt(a.fecha) }}</span> · <strong>{{ a.modo }}</strong> → {{ a.tecnicoAsignado }}
                          @if (a.puntajeAsignado !== null) { (puntaje {{ a.puntajeAsignado }}) }
                          @if (a.tecnicoRecomendado && a.tecnicoRecomendado !== a.tecnicoAsignado) { · recomendado: {{ a.tecnicoRecomendado }} }
                          <div class="small">{{ a.motivo }}</div>
                          @if (a.explicacion) { <div class="small muted">{{ a.explicacion['explicacion'] }}</div> }</li>
                      }
                    </ul>
                  </mat-tab>
                }
              </mat-tab-group>
            </div>
          </div>

          <aside class="side">
            @let p = d.permisos;
            @if (p.iniciar) { <button mat-flat-button class="full" (click)="estado('EN_CURSO')"><mat-icon>play_arrow</mat-icon> Iniciar atención</button> }

            @if (p.finalizar) {
              <div class="card"><h3 class="section-title">Finalizar</h3>
                <mat-form-field appearance="outline" class="full"><mat-label>Tipo de falla confirmado</mat-label>
                  <mat-select [(ngModel)]="tipoFinal">
                    @for (tf of tipos(); track tf.codigo) { <mat-option [value]="tf.codigo">{{ tf.nombre }}</mat-option> }
                  </mat-select></mat-form-field>
                <mat-form-field appearance="outline" class="full"><mat-label>Solución aplicada</mat-label>
                  <textarea matInput rows="3" [(ngModel)]="solucion"></textarea></mat-form-field>
                <button mat-flat-button class="full" [disabled]="!tipoFinal || solucion.length < 10" (click)="finalizar()">Finalizar ticket</button>
              </div>
            }

            @if (p.registrarActividad) {
              <div class="card"><h3 class="section-title">Registrar actividad</h3>
                <div class="row">
                  <mat-form-field appearance="outline"><mat-label>Tipo</mat-label>
                    <mat-select [(ngModel)]="actTipo">
                      @for (x of tiposActividad; track x) { <mat-option [value]="x">{{ x }}</mat-option> }
                    </mat-select></mat-form-field>
                  <mat-form-field appearance="outline" class="mins"><mat-label>Minutos</mat-label>
                    <input matInput type="number" min="0" max="1440" [(ngModel)]="actMin"></mat-form-field>
                </div>
                <mat-form-field appearance="outline" class="full"><mat-label>Observación</mat-label>
                  <textarea matInput rows="2" [(ngModel)]="actDesc"></textarea></mat-form-field>
                <button mat-stroked-button class="full" [disabled]="!actDesc" (click)="actividad()">Guardar</button>
              </div>
            }

            @if (p.asignar) {
              <div class="card"><h3 class="section-title">Asignación de técnico</h3>
                @if (!recs()) { <button mat-stroked-button class="full" (click)="loadRecs()">Ver recomendaciones</button> }
                @else {
                  <p class="small muted">{{ recs()!.motivo }}</p>
                  <table class="simple small"><thead><tr><th>Técnico</th><th class="num">Puntaje</th></tr></thead>
                    <tbody>@for (b of recs()!.ranking.slice(0, 5); track b.id) {
                      <tr (click)="tecnicoSel = b.id" class="pick" [class.sel]="tecnicoSel === b.id">
                        <td>{{ b.nombre }}<div class="muted">{{ b.explicacion }}</div>
                          <div class="muted">E {{ b.e }} · X {{ b.x }} · C {{ b.carga }}/{{ b.cargaMaxima }} · D {{ b.d }}{{ b.dSinHistorial ? ' (sin historial)' : '' }}</div></td>
                        <td class="num">{{ b.puntaje.toFixed(2) }}</td></tr>
                    }</tbody></table>
                  @if (recs()!.excluidos.length) {
                    <details class="small"><summary>Excluidos ({{ recs()!.excluidos.length }})</summary>
                      @for (x of recs()!.excluidos; track x.id) { <div>{{ x.nombre }}: {{ x.motivo }}</div> }</details>
                  }
                  <mat-form-field appearance="outline" class="full"><mat-label>Motivo</mat-label>
                    <input matInput [(ngModel)]="motivoAsig" placeholder="Ej.: confirmo la recomendación"></mat-form-field>
                  <button mat-flat-button class="full" [disabled]="!tecnicoSel || motivoAsig.length < 5" (click)="asignar()">Asignar</button>
                }
              </div>
            }

            @if (p.reclasificar) {
              <mat-expansion-panel class="panel"><mat-expansion-panel-header><mat-panel-title>Reclasificar</mat-panel-title></mat-expansion-panel-header>
                <mat-form-field appearance="outline" class="full"><mat-label>Tipo de falla</mat-label>
                  <mat-select [(ngModel)]="tipoReclas">
                    @for (tf of tipos(); track tf.codigo) { <mat-option [value]="tf.codigo">{{ tf.nombre }} ({{ tf.categoriaCodigo }})</mat-option> }
                  </mat-select></mat-form-field>
                <button mat-stroked-button class="full" [disabled]="!tipoReclas" (click)="reclasificar()">Guardar clasificación</button>
              </mat-expansion-panel>
            }

            @if (p.cambiarPrioridad) {
              <mat-expansion-panel class="panel"><mat-expansion-panel-header><mat-panel-title>Cambiar prioridad</mat-panel-title></mat-expansion-panel-header>
                <mat-form-field appearance="outline" class="full"><mat-label>Prioridad</mat-label>
                  <mat-select [(ngModel)]="nuevaPrio">
                    @for (x of prioList; track x[0]) { <mat-option [value]="x[0]">{{ x[1] }}</mat-option> }
                  </mat-select></mat-form-field>
                <mat-form-field appearance="outline" class="full"><mat-label>Motivo</mat-label><input matInput [(ngModel)]="motivoPrio"></mat-form-field>
                <button mat-stroked-button class="full" [disabled]="!nuevaPrio || !motivoPrio" (click)="prioridad()">Guardar prioridad</button>
              </mat-expansion-panel>
            }

            @if (p.solicitarReasignacion) {
              <mat-expansion-panel class="panel"><mat-expansion-panel-header><mat-panel-title>Solicitar reasignación</mat-panel-title></mat-expansion-panel-header>
                <mat-form-field appearance="outline" class="full"><mat-label>Motivo</mat-label><input matInput [(ngModel)]="motivoReasig"></mat-form-field>
                <button mat-stroked-button class="full" [disabled]="motivoReasig.length < 5" (click)="solicitarReasignacion()">Enviar solicitud</button>
              </mat-expansion-panel>
            }

            @if (p.adjuntar) {
              <input #file type="file" accept="image/jpeg,image/png,image/webp" multiple hidden (change)="adjuntar(file.files); file.value=''">
              <button mat-stroked-button class="full" (click)="file.click()"><mat-icon>add_photo_alternate</mat-icon> Agregar imágenes</button>
            }

            @if (p.calificar) {
              <div class="card"><h3 class="section-title">Califica la atención</h3>
                <div class="stars">@for (s of [1,2,3,4,5]; track s) {
                  <button mat-icon-button (click)="nota = s" [attr.aria-label]="s + ' estrellas'"><mat-icon [class.on]="s <= nota">star</mat-icon></button> }</div>
                <mat-form-field appearance="outline" class="full"><mat-label>Comentario (opcional)</mat-label><input matInput [(ngModel)]="comentario"></mat-form-field>
                <button mat-flat-button class="full" [disabled]="!nota" (click)="calificar()">Enviar</button>
              </div>
            }
            @if (d.encuesta) { <div class="card small">Calificación: <strong>{{ d.encuesta.calificacion }}/5</strong> {{ d.encuesta.comentario }}</div> }

            @if (p.reabrir) { <button mat-stroked-button class="full" (click)="estado('EN_CURSO', 'Reapertura solicitada')"><mat-icon>replay</mat-icon> Reabrir</button> }
            @if (p.cancelar) { <button mat-button class="full danger" (click)="estado('CANCELADA', 'Cancelado por el usuario')">Cancelar ticket</button> }
          </aside>
        </div>
      } @else if (!error()) { <mat-progress-bar mode="indeterminate" /> }
    </div>
  `,
  styles: [`
    .back { display: inline-flex; align-items: center; gap: 4px; text-decoration: none; }
    .back mat-icon { font-size: 16px; width: 16px; height: 16px; }
    h1 { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 16px; }
    @media (max-width: 960px) { .layout { grid-template-columns: 1fr; } }
    .main, .side { display: flex; flex-direction: column; gap: 16px; }
    .desc { white-space: pre-wrap; font-size: 15px; margin-top: 0; }
    .facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px 16px; margin: 0; }
    dt { font-size: 12px; color: var(--hd-muted); } dd { margin: 2px 0 0; font-size: 14px; }
    .solution { margin-top: 12px; padding: 10px; background: #e3f4ea; border-radius: 8px; }
    .gallery { display: flex; gap: 8px; flex-wrap: wrap; }
    .gallery img, .ph { width: 140px; height: 140px; object-fit: cover; border-radius: 8px; border: 1px solid var(--hd-border); display: grid; place-items: center; }
    .diag { border-top: 1px solid var(--hd-border); padding: 12px 0; } .diag:first-of-type { border-top: 0; padding-top: 0; }
    .diag-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; } .spacer { flex: 1; }
    .chip.ok { background: #e3f4ea; color: #0b5a2f; } .chip.bad { background: #fde8e6; color: #7a1d16; }
    .conf { display: flex; align-items: center; gap: 8px; font-size: 13px; margin: 6px 0; }
    .bar { flex: 1; height: 6px; background: #eceef1; border-radius: 3px; overflow: hidden; max-width: 240px; }
    .bar div { height: 100%; background: var(--series-1); border-radius: 3px; } .bar div.low { background: var(--series-4); }
    .timeline { list-style: none; padding: 8px 0; margin: 0; } .timeline li { padding: 6px 0; border-bottom: 1px solid var(--hd-border); font-size: 14px; }
    .mins { max-width: 110px; } .panel { box-shadow: none !important; border: 1px solid var(--hd-border); border-radius: 12px !important; }
    tr.pick { cursor: pointer; } tr.sel { background: #e8f1fd; }
    .stars mat-icon { color: #c9cbd0; } .stars mat-icon.on { color: #eda100; }
    .danger { color: var(--hd-bad); }
  `],
})
export class TicketDetailPage implements OnDestroy {
  readonly id = input.required<string>();
  private http = inject(HttpClient);
  private snack = inject(MatSnackBar);
  readonly auth = inject(AuthService);
  readonly catalog = inject(CatalogService);
  readonly t = signal<TicketDetalle | null>(null);
  readonly imgs = signal<Record<string, string>>({});
  readonly recs = signal<Recomendaciones | null>(null);
  readonly error = signal('');
  readonly busy = signal(false);
  readonly tipos = computed(() => this.catalog.catalogo()?.tiposFalla ?? []);
  readonly estados = ESTADOS; readonly prioridades = PRIORIDADES; readonly impactos = IMPACTOS;
  readonly prioList = Object.entries(PRIORIDADES);
  readonly tiposActividad = ['DIAGNOSTICO', 'REPARACION', 'OBSERVACION', 'SEGUIMIENTO', 'SOLUCION'];
  readonly fmt = fecha; readonly pct = pct; readonly dur = duracion;
  tipoFinal = ''; solucion = ''; actTipo = 'DIAGNOSTICO'; actMin = 15; actDesc = '';
  tecnicoSel = ''; motivoAsig = ''; tipoReclas = ''; nuevaPrio = ''; motivoPrio = ''; motivoReasig = '';
  nota = 0; comentario = '';

  ngOnInit(): void { this.catalog.load(); this.reload(); }

  async reload(): Promise<void> {
    try {
      const d = await firstValueFrom(this.http.get<TicketDetalle>(`/api/v1/tickets/${this.id()}`));
      this.t.set(d);
      this.tipoFinal ||= d.tipoFallaCodigo ?? '';
      this.loadImages(d);
    } catch (e) { this.error.set(errorMessage(e)); }
  }

  /** Las imágenes se piden con el JWT (el almacenamiento nunca se expone) y se muestran como blob local. */
  private loadImages(d: TicketDetalle): void {
    for (const a of d.adjuntos) {
      if (this.imgs()[a.id]) continue;
      this.http.get(`/api/v1/tickets/${d.resumen.id}/adjuntos/${a.id}/contenido`, { responseType: 'blob' }).subscribe(b =>
        this.imgs.update(m => ({ ...m, [a.id]: URL.createObjectURL(b) })));
    }
  }

  private async act(req: Promise<TicketDetalle>, ok: string): Promise<void> {
    this.busy.set(true);
    try {
      const d = await req;
      this.t.set(d);
      this.loadImages(d);
      this.snack.open(ok, 'OK', { duration: 3000 });
    } catch (e) { this.snack.open(errorMessage(e), 'Cerrar', { duration: 6000 }); }
    finally { this.busy.set(false); }
  }

  private post(path: string, body: unknown): Promise<TicketDetalle> {
    return firstValueFrom(this.http.post<TicketDetalle>(`/api/v1/tickets/${this.id()}${path}`, body));
  }
  private patch(path: string, body: unknown): Promise<TicketDetalle> {
    return firstValueFrom(this.http.patch<TicketDetalle>(`/api/v1/tickets/${this.id()}${path}`, body));
  }

  estado(estado: string, comentario?: string) { this.act(this.post('/estado', { estado, comentario }), 'Estado actualizado'); }
  finalizar() { this.act(this.post('/estado', { estado: 'FINALIZADA', solucion: this.solucion, tipoFallaFinalCodigo: this.tipoFinal }), 'Ticket finalizado'); }
  actividad() {
    this.act(this.post('/actividades', { tipo: this.actTipo, minutos: this.actMin, descripcion: this.actDesc }), 'Actividad registrada')
      .then(() => this.actDesc = '');
  }
  validar(id: string, aceptado: boolean) { this.act(this.post(`/diagnosticos/${id}/validacion`, { aceptado }), 'Validación registrada'); }
  async loadRecs() {
    try { this.recs.set(await firstValueFrom(this.http.get<Recomendaciones>(`/api/v1/tickets/${this.id()}/recomendaciones`)));
      this.tecnicoSel = this.recs()!.ranking[0]?.id ?? ''; }
    catch (e) { this.snack.open(errorMessage(e), 'Cerrar', { duration: 5000 }); }
  }
  asignar() { this.act(this.post('/asignacion', { tecnicoId: this.tecnicoSel, motivo: this.motivoAsig }), 'Técnico asignado').then(() => this.recs.set(null)); }
  reclasificar() {
    const tf = this.tipos().find(x => x.codigo === this.tipoReclas);
    this.act(this.patch('/clasificacion', { categoriaCodigo: tf?.categoriaCodigo, tipoFallaCodigo: this.tipoReclas }), 'Clasificación guardada');
  }
  prioridad() { this.act(this.patch('/prioridad', { prioridad: this.nuevaPrio, motivo: this.motivoPrio }), 'Prioridad actualizada'); }
  solicitarReasignacion() { this.act(this.post('/solicitar-reasignacion', { motivo: this.motivoReasig }), 'Solicitud enviada'); }
  calificar() { this.act(this.post('/encuesta', { calificacion: this.nota, comentario: this.comentario }), '¡Gracias por tu calificación!'); }
  adjuntar(list: FileList | null) {
    const fd = new FormData();
    Array.from(list ?? []).slice(0, 5).forEach(f => fd.append('imagenes', f, f.name));
    this.act(this.post('/adjuntos', fd), 'Imágenes agregadas; la IA las analizará en segundo plano');
  }

  ngOnDestroy(): void { Object.values(this.imgs()).forEach(u => URL.revokeObjectURL(u)); }
}
