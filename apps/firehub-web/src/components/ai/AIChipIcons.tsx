export function SideIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" className="shrink-0">
      <rect x="1" y="1" width="14" height="14" rx="2" stroke="currentColor" strokeWidth="1.5" fill="none" />
      <line x1="10" y1="1" x2="10" y2="15" stroke="currentColor" strokeWidth="1.5" />
    </svg>
  );
}

export function FloatingIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" className="shrink-0">
      <rect x="3" y="3" width="10" height="10" rx="2" stroke="currentColor" strokeWidth="1.5" fill="none" />
      <rect x="1" y="1" width="14" height="14" rx="2" stroke="currentColor" strokeWidth="1" fill="none" opacity="0.3" />
    </svg>
  );
}

export function FullscreenIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" className="shrink-0">
      <rect x="1" y="1" width="14" height="14" rx="2" stroke="currentColor" strokeWidth="1.5" fill="currentColor" fillOpacity="0.15" />
    </svg>
  );
}
