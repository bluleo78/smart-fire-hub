export function WidgetSkeleton({ label }: { label: string }) {
  return (
    <div className="my-1 flex h-20 items-center justify-center rounded-lg border border-border bg-muted">
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <div className="h-4 w-4 animate-spin rounded-full border-2 border-muted-foreground border-t-transparent" />
        {label} 로딩 중...
      </div>
    </div>
  );
}
