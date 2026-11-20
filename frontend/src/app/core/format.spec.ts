import { describe, expect, it } from 'vitest';
import { duracion, pct } from './format';

describe('formatos', () => {
  it('formatea duraciones en minutos, horas y días', () => {
    expect(duracion(45)).toBe('45 min');
    expect(duracion(90)).toBe('1,5 h');
    expect(duracion(2880)).toBe('2 días');
    expect(duracion(null)).toBe('—');
  });
  it('formatea porcentajes', () => {
    expect(pct(0.756)).toBe('76 %');
    expect(pct(undefined)).toBe('—');
  });
});
