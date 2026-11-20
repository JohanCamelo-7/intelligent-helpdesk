export const ESTADOS: Record<string, string> = {
  PENDIENTE: 'Pendiente', EN_CURSO: 'En curso', FINALIZADA: 'Finalizada', CANCELADA: 'Cancelada',
};
export const PRIORIDADES: Record<string, string> = { BAJA: 'Baja', MEDIA: 'Media', ALTA: 'Alta', CRITICA: 'Crítica' };
export const IMPACTOS: Record<string, string> = { INDIVIDUAL: 'Solo a mí', AREA: 'A mi área', SEDE: 'A toda la sede' };

/** Minutos -> "45 min", "3,5 h", "2,1 días". */
export function duracion(min: number | null | undefined): string {
  if (min === null || min === undefined || Number.isNaN(min)) return '—';
  if (min < 60) return `${Math.round(min)} min`;
  if (min < 60 * 24) return `${(min / 60).toLocaleString('es-CO', { maximumFractionDigits: 1 })} h`;
  return `${(min / 1440).toLocaleString('es-CO', { maximumFractionDigits: 1 })} días`;
}

export function pct(v: number | null | undefined): string {
  return v === null || v === undefined ? '—' : `${(v * 100).toLocaleString('es-CO', { maximumFractionDigits: 0 })} %`;
}

export function num(v: number | null | undefined, digits = 0): string {
  return v === null || v === undefined ? '—' : Number(v).toLocaleString('es-CO', { maximumFractionDigits: digits });
}

export function fecha(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('es-CO', { dateStyle: 'medium', timeStyle: 'short' });
}

export function isoDate(d: Date): string {
  const tz = d.getTimezoneOffset() * 60000;
  return new Date(d.getTime() - tz).toISOString().slice(0, 10);
}
