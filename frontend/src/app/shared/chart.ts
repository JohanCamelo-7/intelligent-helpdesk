import { Component, ElementRef, OnDestroy, effect, input, viewChild } from '@angular/core';
import Chart from 'chart.js/auto';
import type { ChartConfiguration, ChartType } from 'chart.js';

/** Paleta categórica validada (orden fijo; el color sigue a la serie, nunca a su posición). */
export const SERIES = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'];
const GRID = '#e8e9ec';
const TEXT = '#5d5f66';

export interface ChartSpec {
  type: ChartType;
  labels: string[];
  datasets: { label: string; data: (number | null)[]; color?: string; stack?: string }[];
  horizontal?: boolean;
  stacked?: boolean;
  valueFormat?: (v: number) => string;
}

/** Envoltorio de Chart.js con marcas finas, una sola escala, leyenda solo con 2+ series y tooltip al pasar. */
@Component({
  selector: 'app-chart',
  template: `<div class="wrap" [style.height.px]="height()"><canvas #canvas role="img" [attr.aria-label]="label()"></canvas></div>`,
  styles: [`.wrap { position: relative; width: 100%; }`],
})
export class ChartComponent implements OnDestroy {
  readonly spec = input.required<ChartSpec>();
  readonly height = input(260);
  readonly label = input('Gráfica');
  private canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private chart?: Chart;

  constructor() {
    effect(() => {
      const s = this.spec();
      const cfg = this.config(s);
      this.chart?.destroy();
      this.chart = new Chart(this.canvas().nativeElement, cfg);
    });
  }

  private config(s: ChartSpec): ChartConfiguration {
    const line = s.type === 'line';
    const fmt = s.valueFormat ?? ((v: number) => v.toLocaleString('es-CO', { maximumFractionDigits: 1 }));
    const valueAxis = { beginAtZero: true, grid: { color: GRID }, border: { display: false },
      ticks: { color: TEXT, callback: (v: number | string) => fmt(Number(v)) }, stacked: s.stacked };
    const catAxis = { grid: { display: false }, border: { color: GRID }, ticks: { color: TEXT, autoSkip: true, maxRotation: 0 }, stacked: s.stacked };
    return {
      type: s.type,
      data: {
        labels: s.labels,
        datasets: s.datasets.map((d, i) => {
          const color = d.color ?? SERIES[i % SERIES.length];
          return line
            ? { label: d.label, data: d.data, borderColor: color, backgroundColor: color, borderWidth: 2, pointRadius: 0,
                pointHoverRadius: 5, pointHoverBorderColor: '#fff', pointHoverBorderWidth: 2, tension: 0.25, spanGaps: true }
            : { label: d.label, data: d.data, backgroundColor: color, borderRadius: 4, borderSkipped: 'start',
                borderColor: '#fff', borderWidth: s.stacked ? 1 : 0, maxBarThickness: 28, stack: d.stack };
        }),
      } as any,
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: s.horizontal ? 'y' : 'x',
        interaction: line ? { mode: 'index', intersect: false } : { mode: 'nearest', intersect: true },
        plugins: {
          legend: { display: s.datasets.length > 1, position: 'top', align: 'start',
            labels: { color: TEXT, usePointStyle: true, boxWidth: 8, boxHeight: 8 } },
          tooltip: { callbacks: { label: (c: any) => ` ${c.dataset.label}: ${fmt(Number(c.raw ?? 0))}` } },
        },
        scales: s.horizontal ? { x: valueAxis, y: catAxis } : { x: catAxis, y: valueAxis },
      } as any,
    };
  }

  ngOnDestroy(): void { this.chart?.destroy(); }
}
