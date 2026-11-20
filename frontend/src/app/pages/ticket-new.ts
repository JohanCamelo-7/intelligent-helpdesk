import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { errorMessage } from '../core/auth.interceptor';
import { AuthService } from '../core/auth.service';
import { CatalogService } from '../core/catalog.service';
import { TicketDetalle } from '../core/models';

const MAX_FILES = 5;
const MAX_BYTES = 5 * 1024 * 1024;
const TIPOS = ['image/jpeg', 'image/png', 'image/webp'];

@Component({
  selector: 'app-ticket-new',
  imports: [ReactiveFormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatButtonToggleModule, MatCheckboxModule, MatIconModule, MatProgressBarModule],
  template: `
    <div class="page narrow">
      <div class="page-header">
        <h1>Nuevo ticket</h1>
        <a mat-stroked-button routerLink="/asistente"><mat-icon>mic</mat-icon> Prefiero hablar con el asistente</a>
      </div>
      <form class="card" [formGroup]="form" (ngSubmit)="submit()">
        <mat-form-field appearance="outline" class="full">
          <mat-label>¿Qué está pasando?</mat-label>
          <textarea matInput formControlName="descripcion" rows="5" maxlength="4000"
            placeholder="Describe el problema: qué intentabas hacer, qué ocurre y si aparece algún mensaje de error."></textarea>
          <mat-hint align="end">{{ form.controls.descripcion.value.length }}/4000</mat-hint>
          @if (form.controls.descripcion.hasError('minlength')) { <mat-error>Escribe al menos 10 caracteres</mat-error> }
        </mat-form-field>
        <div class="row">
          <mat-form-field appearance="outline">
            <mat-label>Sede</mat-label>
            <mat-select formControlName="sedeId">
              @for (s of catalog.catalogo()?.sedes ?? []; track s.id) { <mat-option [value]="s.id">{{ s.nombre }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Equipo afectado (opcional)</mat-label>
            <mat-select formControlName="equipoId">
              <mat-option [value]="null">No aplica / no lo sé</mat-option>
              @for (e of equipos(); track e.id) { <mat-option [value]="e.id">{{ e.codigo }} · {{ e.tipo }} {{ e.marca }}</mat-option> }
            </mat-select>
          </mat-form-field>
        </div>
        <div class="field">
          <div class="lbl">¿A quién afecta?</div>
          <mat-button-toggle-group formControlName="impacto" aria-label="Impacto">
            <mat-button-toggle value="INDIVIDUAL">Solo a mí</mat-button-toggle>
            <mat-button-toggle value="AREA">A mi área</mat-button-toggle>
            <mat-button-toggle value="SEDE">A toda la sede</mat-button-toggle>
          </mat-button-toggle-group>
        </div>
        <mat-checkbox formControlName="bloqueaTrabajo">Me impide trabajar</mat-checkbox>

        <div class="field">
          <div class="lbl">Imágenes (opcional, hasta 5 de máximo 5 MB: JPG, PNG o WEBP)</div>
          <input #file type="file" accept="image/jpeg,image/png,image/webp" multiple hidden (change)="pick(file.files); file.value=''">
          <button mat-stroked-button type="button" (click)="file.click()" [disabled]="files().length >= 5">
            <mat-icon>add_photo_alternate</mat-icon> Agregar imágenes
          </button>
          <div class="thumbs">
            @for (f of files(); track f.url; let i = $index) {
              <div class="thumb"><img [src]="f.url" [alt]="f.file.name">
                <button mat-icon-button type="button" (click)="remove(i)" aria-label="Quitar"><mat-icon>close</mat-icon></button></div>
            }
          </div>
          @if (fileError()) { <div class="banner warn">{{ fileError() }}</div> }
        </div>
        @if (error()) { <div class="banner error">{{ error() }}</div> }
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        <div class="actions">
          <button mat-flat-button type="submit" [disabled]="form.invalid || loading()">Enviar solicitud</button>
        </div>
        <p class="muted small">Tras enviarla, la IA analizará el texto y las imágenes en segundo plano para proponer la falla
          y asignar al técnico. Si la confianza es baja, un administrador la revisará.</p>
      </form>
    </div>
  `,
  styles: [`
    .narrow { max-width: 820px; }
    form { display: flex; flex-direction: column; gap: 8px; }
    .field { margin: 8px 0; } .lbl { font-size: 13px; color: var(--hd-muted); margin-bottom: 6px; }
    .thumbs { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
    .thumb { position: relative; width: 96px; height: 96px; border-radius: 8px; overflow: hidden; border: 1px solid var(--hd-border); }
    .thumb img { width: 100%; height: 100%; object-fit: cover; }
    .thumb button { position: absolute; top: 0; right: 0; background: rgba(255,255,255,.85); }
    .actions { display: flex; justify-content: flex-end; }
  `],
})
export class TicketNewPage implements OnDestroy {
  private http = inject(HttpClient);
  private router = inject(Router);
  readonly catalog = inject(CatalogService);
  private auth = inject(AuthService);
  readonly form = inject(FormBuilder).nonNullable.group({
    descripcion: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(4000)]],
    sedeId: [null as number | null, Validators.required],
    equipoId: [null as number | null],
    impacto: ['INDIVIDUAL', Validators.required],
    bloqueaTrabajo: [false],
  });
  private sede = toSignal(this.form.controls.sedeId.valueChanges, { initialValue: null });
  readonly equipos = computed(() => (this.catalog.catalogo()?.equipos ?? []).filter(e => e.sedeId === this.sede()));
  readonly files = signal<{ file: File; url: string }[]>([]);
  readonly fileError = signal('');
  readonly error = signal('');
  readonly loading = signal(false);

  constructor() {
    this.catalog.load().then(() => {
      const sede = this.auth.user()?.sedeId;
      if (sede) this.form.controls.sedeId.setValue(sede);
    });
    this.form.controls.sedeId.valueChanges.subscribe(() => this.form.controls.equipoId.setValue(null));
  }

  pick(list: FileList | null): void {
    this.fileError.set('');
    for (const f of Array.from(list ?? [])) {
      if (this.files().length >= MAX_FILES) { this.fileError.set('Máximo 5 imágenes'); break; }
      if (!TIPOS.includes(f.type)) { this.fileError.set(`${f.name}: solo JPG, PNG o WEBP`); continue; }
      if (f.size > MAX_BYTES) { this.fileError.set(`${f.name} supera 5 MB`); continue; }
      this.files.update(a => [...a, { file: f, url: URL.createObjectURL(f) }]);
    }
  }

  remove(i: number): void {
    URL.revokeObjectURL(this.files()[i].url);
    this.files.update(a => a.filter((_, j) => j !== i));
  }

  async submit(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    const fd = new FormData();
    fd.append('datos', new Blob([JSON.stringify(this.form.getRawValue())], { type: 'application/json' }));
    for (const f of this.files()) fd.append('imagenes', f.file, f.file.name);
    try {
      const t = await firstValueFrom(this.http.post<TicketDetalle>('/api/v1/tickets', fd));
      await this.router.navigate(['/tickets', t.resumen.id]);
    } catch (e) {
      this.error.set(errorMessage(e));
    } finally {
      this.loading.set(false);
    }
  }

  ngOnDestroy(): void { this.files().forEach(f => URL.revokeObjectURL(f.url)); }
}
