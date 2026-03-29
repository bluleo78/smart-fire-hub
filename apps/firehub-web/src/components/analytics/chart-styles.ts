export const TOOLTIP_CONTENT_STYLE = {
  background: 'hsl(var(--popover))',
  border: '1px solid hsl(var(--border))',
  borderRadius: 6,
  fontSize: 12,
  color: 'hsl(var(--popover-foreground))',
} as const;

export const BAR_CURSOR_STYLE = { fill: 'hsl(var(--muted))', opacity: 0.5 } as const;

export const LINE_CURSOR_STYLE = { stroke: 'hsl(var(--muted-foreground))', strokeWidth: 1 } as const;
