import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, OnDestroy, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { TimeoutError, catchError, firstValueFrom, throwError, timeout } from 'rxjs';
import { errorMessage } from '../core/auth.interceptor';
import { IMPACTOS, PRIORIDADES } from '../core/format';
import { AgentReply, Borrador } from '../core/models';

interface Msg { de: 'yo' | 'agente' | 'sistema'; texto: string; }
const MAX_SECONDS = 60;

@Component({
  selector: 'app-agent',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatSlideToggleModule, MatCheckboxModule, MatButtonToggleModule],
  template: `
    <div class="page narrow">
      <div class="page-header">
        <h1>Asistente de soporte</h1>
        <mat-slide-toggle [(ngModel)]="vozActiva">Responder con voz</mat-slide-toggle>
      </div>
      @if (!micDisponible) {
        <div class="banner warn"><mat-icon>mic_off</mat-icon>
          El micrófono solo funciona en conexiones seguras (HTTPS mediante el túnel de Cloudflare o en localhost). Puedes escribir.</div>
      }
      <div class="card chat">
        <div class="msgs" #msgs>
          @for (m of mensajes(); track $index) {
            <div class="msg {{ m.de }}">{{ m.texto }}</div>
          }
          @if (borrador(); as b) {
            <div class="draft">
              <div class="draft-title"><mat-icon>assignment</mat-icon> Borrador del ticket</div>
              <mat-form-field appearance="outline" class="full"><mat-label>Descripción</mat-label>
                <textarea matInput rows="3" [(ngModel)]="b.descripcion"></textarea></mat-form-field>
              <mat-button-toggle-group [(ngModel)]="b.impacto" aria-label="Impacto">
                @for (i of impactos; track i[0]) { <mat-button-toggle [value]="i[0]">{{ i[1] }}</mat-button-toggle> }
              </mat-button-toggle-group>
              <mat-checkbox [(ngModel)]="b.bloqueaTrabajo">Me impide trabajar</mat-checkbox>
              <div class="small muted">Equipo: {{ b.equipo ?? 'no aplica' }} · Prioridad estimada: {{ prio[b.prioridadEstimada] }}
                @if (b.tipoFallaSugerido) { · Falla sugerida: {{ b.tipoFallaSugerido }} }</div>
              <div class="row">
                <button mat-flat-button (click)="confirmar(b)" [disabled]="ocupado()"><mat-icon>check</mat-icon> Confirmar y crear ticket</button>
                <button mat-button (click)="enviar('editar')">Descartar</button>
              </div>
            </div>
          }
          @if (ticket(); as t) {
            <a mat-stroked-button [routerLink]="['/tickets', t.id]"><mat-icon>open_in_new</mat-icon> Ver ticket {{ t.numero }}</a>
          }
          @if (ocupado()) { <div class="msg sistema">{{ estadoTexto() }}</div> }
        </div>
        @if (sugerencias().length) {
          <div class="sugs">@for (s of sugerencias(); track s) { <button mat-stroked-button (click)="enviar(s)">{{ s }}</button> }</div>
        }
        <div class="composer">
          <button mat-fab [class.rec]="grabando()" (click)="grabando() ? detener() : grabar()"
                  [disabled]="!micDisponible || (ocupado() && !grabando())" [attr.aria-label]="grabando() ? 'Detener grabación' : 'Hablar'">
            <mat-icon>{{ grabando() ? 'stop' : 'mic' }}</mat-icon>
          </button>
          @if (grabando()) {
            <div class="recording" role="status"><span class="dot"></span> Grabando… {{ segundos() }} s (máx. 60)</div>
          } @else {
            <mat-form-field appearance="outline" class="input" subscriptSizing="dynamic">
              <input matInput [(ngModel)]="texto" (keyup.enter)="enviar()" placeholder="Escribe tu mensaje" maxlength="2000" [disabled]="ocupado()">
            </mat-form-field>
            <button mat-icon-button (click)="enviar()" [disabled]="!texto.trim() || ocupado()" aria-label="Enviar"><mat-icon>send</mat-icon></button>
          }
        </div>
      </div>
      <p class="muted small">El audio se procesa en el servidor y se descarta: no se guardan grabaciones. El asistente solo crea un
        ticket cuando tú lo confirmas y únicamente sugiere comprobaciones seguras.
        <mat-checkbox [(ngModel)]="consentimiento" (change)="nuevaSesion()">Autorizo registrar los metadatos de esta conversación
          (duración, número de turnos, si usé voz) para mejorar el servicio.</mat-checkbox></p>
    </div>
  `,
  styles: [`
    .narrow { max-width: 820px; }
    .chat { display: flex; flex-direction: column; gap: 10px; padding: 12px; }
    .msgs { min-height: 320px; max-height: 58vh; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; padding: 4px; }
    .msg { max-width: 80%; padding: 10px 14px; border-radius: 14px; line-height: 1.4; white-space: pre-wrap; }
    .msg.agente { background: #eef3fa; align-self: flex-start; border-bottom-left-radius: 4px; }
    .msg.yo { background: #2a78d6; color: #fff; align-self: flex-end; border-bottom-right-radius: 4px; }
    .msg.sistema { align-self: center; color: var(--hd-muted); font-size: 13px; }
    .draft { border: 1px solid #9bb8e0; border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 8px; background: #fbfdff; }
    .draft-title { display: flex; gap: 6px; align-items: center; font-weight: 500; }
    .sugs { display: flex; gap: 8px; flex-wrap: wrap; }
    .composer { display: flex; gap: 10px; align-items: center; }
    .input { flex: 1; }
    .rec { background: var(--hd-bad) !important; color: #fff !important; }
    .recording { flex: 1; display: flex; align-items: center; gap: 8px; color: var(--hd-bad); font-weight: 500; }
    .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--hd-bad); animation: blink 1s infinite; }
    @keyframes blink { 50% { opacity: .2; } }
  `],
})
export class AgentPage implements OnDestroy {
  private http = inject(HttpClient);
  private msgsEl = viewChild<ElementRef<HTMLDivElement>>('msgs');
  readonly mensajes = signal<Msg[]>([]);
  readonly sugerencias = signal<string[]>([]);
  readonly borrador = signal<Borrador | null>(null);
  readonly ticket = signal<{ id: string; numero: string } | null>(null);
  readonly ocupado = signal(false);
  readonly estadoTexto = signal('');
  readonly grabando = signal(false);
  readonly segundos = signal(0);
  readonly impactos = Object.entries(IMPACTOS);
  readonly prio = PRIORIDADES;
  readonly micDisponible = !!(navigator.mediaDevices?.getUserMedia) && window.isSecureContext && typeof MediaRecorder !== 'undefined';
  texto = ''; vozActiva = false; consentimiento = false;
  private sesionId = '';
  private recorder?: MediaRecorder;
  private stream?: MediaStream;
  private chunks: Blob[] = [];
  private timer?: ReturnType<typeof setInterval>;
  private audio?: HTMLAudioElement;

  constructor() { this.nuevaSesion(); }

  async nuevaSesion(): Promise<void> {
    await this.terminar();
    this.mensajes.set([]); this.borrador.set(null); this.ticket.set(null);
    try {
      const r = await firstValueFrom(this.http.post<AgentReply>('/api/v1/agent/sesiones', { consentimiento: this.consentimiento }));
      this.sesionId = r.sesion_id;
      this.mostrar(r);
    } catch (e) { this.sistema('No se pudo iniciar el asistente: ' + errorMessage(e)); }
  }

  async enviar(texto?: string): Promise<void> {
    const t = (texto ?? this.texto).trim();
    if (!t || !this.sesionId) return;
    this.texto = '';
    this.agregar({ de: 'yo', texto: t });
    await this.ejecutar('Pensando…', () => firstValueFrom(this.http.post<AgentReply>('/api/v1/agent/chat',
      { sesion_id: this.sesionId, mensaje: t, voz: this.vozActiva })));
  }

  async grabar(): Promise<void> {
    try {
      // El navegador pide permiso la primera vez
      this.stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      this.sistema('No se concedió acceso al micrófono. Puedes escribir tu mensaje.');
      return;
    }
    this.chunks = [];
    const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : '';
    this.recorder = new MediaRecorder(this.stream, mime ? { mimeType: mime } : undefined);
    this.recorder.ondataavailable = e => { if (e.data.size) this.chunks.push(e.data); };
    this.recorder.onstop = () => this.enviarAudio();
    this.recorder.start();
    this.grabando.set(true);
    this.segundos.set(0);
    this.timer = setInterval(() => {
      this.segundos.update(s => s + 1);
      if (this.segundos() >= MAX_SECONDS) this.detener();
    }, 1000);
  }

  detener(): void {
    clearInterval(this.timer);
    this.grabando.set(false);
    if (this.recorder?.state === 'recording') this.recorder.stop();
    this.stream?.getTracks().forEach(t => t.stop());   // apaga el micrófono
  }

  private async enviarAudio(): Promise<void> {
    const blob = new Blob(this.chunks, { type: this.recorder?.mimeType || 'audio/webm' });
    this.chunks = [];
    if (blob.size < 1000) { this.sistema('La grabación fue muy corta.'); return; }
    const fd = new FormData();
    fd.append('sesion_id', this.sesionId);
    fd.append('audio', blob, 'turno.webm');
    this.vozActiva = true;
    await this.ejecutar('Procesando audio…', async () => {
      const r = await firstValueFrom(this.http.post<AgentReply>('/api/v1/agent/voz', fd).pipe(
        timeout(85_000), // antes que el límite de 90 s de Nginx
        catchError(e => throwError(() => e instanceof TimeoutError
          ? new Error('El servidor tardó demasiado en procesar el audio. Intenta de nuevo o escribe tu mensaje.') : e))));
      if (r.transcripcion) this.agregar({ de: 'yo', texto: r.transcripcion });
      return r;
    });
  }

  async confirmar(b: Borrador): Promise<void> {
    await this.ejecutar('Creando el ticket…', () => firstValueFrom(this.http.post<AgentReply>(
      `/api/v1/agent/borradores/${b.id}/confirmar`,
      { sesion_id: this.sesionId, descripcion: b.descripcion, impacto: b.impacto, bloqueaTrabajo: b.bloqueaTrabajo })));
  }

  private async ejecutar(estado: string, fn: () => Promise<AgentReply>): Promise<void> {
    this.ocupado.set(true);
    this.estadoTexto.set(estado);
    try {
      this.mostrar(await fn());
    } catch (e) {
      this.sistema(errorMessage(e));
      if ((e as { status?: number }).status === 404) await this.nuevaSesion();
    } finally {
      this.ocupado.set(false);
    }
  }

  private mostrar(r: AgentReply): void {
    this.agregar({ de: 'agente', texto: r.respuesta });
    this.sugerencias.set(r.sugerencias ?? []);
    this.borrador.set(r.borrador ? { ...r.borrador } : null);
    if (r.ticket && r.etapa === 'CREADO') this.ticket.set(r.ticket);
    if (this.vozActiva) this.hablar(r);
  }

  /** Reproduce la voz del servidor (Piper); si no está disponible, usa la síntesis del navegador. */
  private hablar(r: AgentReply): void {
    this.audio?.pause();
    window.speechSynthesis?.cancel();
    if (r.audio_wav_base64) {
      this.audio = new Audio(`data:audio/wav;base64,${r.audio_wav_base64}`);
      this.audio.play().catch(() => {});
    } else if ('speechSynthesis' in window) {
      const u = new SpeechSynthesisUtterance(r.respuesta);
      u.lang = 'es-CO';
      window.speechSynthesis.speak(u);
    }
  }

  private agregar(m: Msg): void {
    this.mensajes.update(a => [...a, m]);
    queueMicrotask(() => { const el = this.msgsEl()?.nativeElement; if (el) el.scrollTop = el.scrollHeight; });
  }
  private sistema(texto: string): void { this.agregar({ de: 'sistema', texto }); }

  private async terminar(): Promise<void> {
    if (!this.sesionId) return;
    const id = this.sesionId;
    this.sesionId = '';
    try { await firstValueFrom(this.http.post(`/api/v1/agent/sesiones/${id}/fin`, {})); } catch { /* la sesión expira sola */ }
  }

  ngOnDestroy(): void {
    this.detener();
    this.audio?.pause();
    window.speechSynthesis?.cancel();
    this.terminar();
  }
}
