interface CanvasPlaceholderCardProps {
  label: string;
  icon: string;
}

export function CanvasPlaceholderCard({ label, icon }: CanvasPlaceholderCardProps) {
  return (
    <div className="my-1 flex items-center gap-2 rounded border border-border/50 bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
      <span>{icon}</span>
      <span className="font-medium">{label}</span>
      <span className="ml-auto text-primary/70">캔버스에 추가됨</span>
    </div>
  );
}
