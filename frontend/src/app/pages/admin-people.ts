import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { firstValueFrom } from 'rxjs';
import { errorMessage } from '../core/auth.interceptor';
import { CatalogService } from '../core/catalog.service';
import { Tecnico, Usuario } from '../core/models';

@Component({
  selector: 'app-admin-people',
  imports: [ReactiveFormsModule, FormsModule, MatTabsModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatSlideToggleModule],
  template: `
    <div class="page">
      <div class="page-header"><h1>Usuarios y técnicos</h1></div>
      <mat-tab-group>
        <mat-tab label="Técnicos">
          <div class="grid grid-2 gap">
            <div class="card">
              <h3 class="section-title">Técnicos</h3>
              <div class="table-wrap"><table class="simple">
                <thead><tr><th>Nombre</th><th>Nivel</th><th>Especialidades</th><th class="num">Carga</th><th class="num">Resueltos</th><th>Disponible</th></tr></thead>
                <tbody>@for (t of tecnicos(); track t.id) {
                  <tr><td>{{ t.nombre }}<div class="small muted">{{ t.email }}</div></td>
                    <td><mat-select [ngModel]="t.nivel" (ngModelChange)="actualizarTec(t, {nivel: $event})" class="mini">
                      @for (n of niveles; track n) { <mat-option [value]="n">{{ n }}</mat-option> }</mat-select></td>
                    <td class="small">{{ t.especialidades.join(', ') }}</td>
                    <td class="num">{{ t.carga }}/{{ t.cargaMaxima }}</td><td class="num">{{ t.resueltos }}</td>
                    <td><mat-slide-toggle [ngModel]="t.disponible" (ngModelChange)="actualizarTec(t, {disponible: $event})" /></td></tr>
                } @empty { <tr><td colspan="6" class="empty">Sin técnicos</td></tr> }</tbody></table></div>
            </div>
            <form class="card" [formGroup]="tecForm" (ngSubmit)="crearTecnico()">
              <h3 class="section-title">Nuevo técnico</h3>
              <mat-form-field appearance="outline" class="full"><mat-label>Nombre</mat-label><input matInput formControlName="nombre"></mat-form-field>
              <div class="row">
                <mat-form-field appearance="outline"><mat-label>Correo</mat-label><input matInput formControlName="email" type="email"></mat-form-field>
                <mat-form-field appearance="outline"><mat-label>Contraseña inicial</mat-label><input matInput formControlName="password" type="password"></mat-form-field>
              </div>
              <div class="row">
                <mat-form-field appearance="outline"><mat-label>Nivel</mat-label><mat-select formControlName="nivel">
                  @for (n of niveles; track n) { <mat-option [value]="n">{{ n }}</mat-option> }</mat-select></mat-form-field>
                <mat-form-field appearance="outline"><mat-label>Carga máxima</mat-label><input matInput type="number" formControlName="cargaMaxima"></mat-form-field>
                <mat-form-field appearance="outline"><mat-label>Sede</mat-label><mat-select formControlName="sedeId">
                  @for (s of catalog.catalogo()?.sedes ?? []; track s.id) { <mat-option [value]="s.id">{{ s.nombre }}</mat-option> }</mat-select></mat-form-field>
              </div>
              <mat-form-field appearance="outline" class="full"><mat-label>Especialidades</mat-label>
                <mat-select formControlName="especialidadIds" multiple>
                  @for (e of catalog.catalogo()?.especialidades ?? []; track e.id) { <mat-option [value]="e.id">{{ e.nombre }}</mat-option> }
                </mat-select></mat-form-field>
              <button mat-flat-button [disabled]="tecForm.invalid">Crear técnico</button>
            </form>
          </div>
        </mat-tab>
        <mat-tab label="Usuarios">
          <div class="grid grid-2 gap">
            <div class="card">
              <h3 class="section-title">Usuarios</h3>
              <div class="table-wrap"><table class="simple">
                <thead><tr><th>Nombre</th><th>Rol</th><th>Cargo</th><th>Activo</th></tr></thead>
                <tbody>@for (u of usuarios(); track u.id) {
                  <tr><td>{{ u.nombre }}<div class="small muted">{{ u.email }}</div></td><td>{{ u.rol }}</td><td>{{ u.cargo }}</td>
                    <td><mat-slide-toggle [ngModel]="u.activo" (ngModelChange)="activo(u, $event)" /></td></tr>
                }</tbody></table></div>
            </div>
            <form class="card" [formGroup]="userForm" (ngSubmit)="crearUsuario()">
              <h3 class="section-title">Nuevo usuario</h3>
              <mat-form-field appearance="outline" class="full"><mat-label>Nombre</mat-label><input matInput formControlName="nombre"></mat-form-field>
              <div class="row">
                <mat-form-field appearance="outline"><mat-label>Correo</mat-label><input matInput formControlName="email" type="email"></mat-form-field>
                <mat-form-field appearance="outline"><mat-label>Contraseña inicial</mat-label><input matInput formControlName="password" type="password"></mat-form-field>
              </div>
              <div class="row">
                <mat-form-field appearance="outline"><mat-label>Cargo</mat-label><input matInput formControlName="cargo"></mat-form-field>
                <mat-form-field appearance="outline"><mat-label>Área</mat-label><input matInput formControlName="area"></mat-form-field>
              </div>
              <div class="row">
                <mat-form-field appearance="outline"><mat-label>Sede</mat-label><mat-select formControlName="sedeId">
                  @for (s of catalog.catalogo()?.sedes ?? []; track s.id) { <mat-option [value]="s.id">{{ s.nombre }}</mat-option> }</mat-select></mat-form-field>
                <mat-form-field appearance="outline"><mat-label>Rol</mat-label><mat-select formControlName="rol">
                  <mat-option value="USER">Usuario</mat-option><mat-option value="ADMIN">Administrador</mat-option></mat-select></mat-form-field>
              </div>
              <p class="small muted">Contraseña: mínimo 8 caracteres con letras y números. Para técnicos usa la otra pestaña.</p>
              <button mat-flat-button [disabled]="userForm.invalid">Crear usuario</button>
            </form>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`.gap { margin-top: 16px; } form.card { display: flex; flex-direction: column; } .mini { width: 130px; }`],
})
export class AdminPeoplePage {
  private http = inject(HttpClient);
  private snack = inject(MatSnackBar);
  readonly catalog = inject(CatalogService);
  private fb = inject(FormBuilder).nonNullable;
  readonly usuarios = signal<Usuario[]>([]);
  readonly tecnicos = signal<Tecnico[]>([]);
  readonly niveles = ['JUNIOR', 'SEMI_SENIOR', 'SENIOR'];
  readonly tecForm = this.fb.group({
    nombre: ['', Validators.required], email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]], nivel: ['SEMI_SENIOR'], cargaMaxima: [12],
    sedeId: [null as number | null], especialidadIds: [[] as number[], Validators.required], cargo: ['Técnico de soporte'],
  });
  readonly userForm = this.fb.group({
    nombre: ['', Validators.required], email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]], cargo: ['', Validators.required], area: [''],
    sedeId: [null as number | null], rol: ['USER'],
  });

  constructor() { this.catalog.load(); this.load(); }

  load(): void {
    this.http.get<Usuario[]>('/api/v1/usuarios').subscribe(u => this.usuarios.set(u));
    this.http.get<Tecnico[]>('/api/v1/tecnicos').subscribe(t => this.tecnicos.set(t));
  }

  private async run(p: Promise<unknown>, ok: string): Promise<boolean> {
    try { await p; this.snack.open(ok, 'OK', { duration: 3000 }); this.load(); return true; }
    catch (e) { this.snack.open(errorMessage(e), 'Cerrar', { duration: 6000 }); this.load(); return false; }
  }

  async crearTecnico() {
    if (await this.run(firstValueFrom(this.http.post('/api/v1/tecnicos', this.tecForm.getRawValue())), 'Técnico creado')) this.tecForm.reset();
  }
  async crearUsuario() {
    if (await this.run(firstValueFrom(this.http.post('/api/v1/usuarios', this.userForm.getRawValue())), 'Usuario creado')) this.userForm.reset();
  }
  actualizarTec(t: Tecnico, cambio: object) { this.run(firstValueFrom(this.http.put(`/api/v1/tecnicos/${t.id}`, cambio)), 'Técnico actualizado'); }
  activo(u: Usuario, activo: boolean) { this.run(firstValueFrom(this.http.put(`/api/v1/usuarios/${u.id}`, { activo })), 'Usuario actualizado'); }
}
