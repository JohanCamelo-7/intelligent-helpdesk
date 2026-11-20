import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Catalogo } from './models';

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private http = inject(HttpClient);
  readonly catalogo = signal<Catalogo | null>(null);

  async load(force = false): Promise<Catalogo> {
    const c = this.catalogo();
    if (c && !force) return c;
    const fresh = await firstValueFrom(this.http.get<Catalogo>('/api/v1/catalogos'));
    this.catalogo.set(fresh);
    return fresh;
  }
}
