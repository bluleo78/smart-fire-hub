/** 큰 숫자를 축약 표기 (80000000 → 80M, 만단위 이하는 그대로) */
export function formatYAxisTick(value: unknown): string {
  const n = Number(value);
  if (!Number.isFinite(n)) return String(value);
  if (Math.abs(n) >= 100_000_000) return `${(n / 100_000_000).toFixed(1).replace(/\.0$/, '')}억`;
  if (Math.abs(n) >= 10_000) return `${(n / 10_000).toFixed(1).replace(/\.0$/, '')}만`;
  return String(n);
}

export const TOOLTIP_CONTENT_STYLE = {
  background: 'hsl(var(--popover))',
  border: '1px solid hsl(var(--border))',
  borderRadius: 6,
  fontSize: 12,
  color: 'hsl(var(--popover-foreground))',
} as const;

export const BAR_CURSOR_STYLE = { fill: 'hsl(var(--muted))', opacity: 0.5 } as const;

export const LINE_CURSOR_STYLE = { stroke: 'hsl(var(--muted-foreground))', strokeWidth: 1 } as const;
