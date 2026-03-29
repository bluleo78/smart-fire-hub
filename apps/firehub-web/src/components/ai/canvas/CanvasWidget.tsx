import { Suspense, useState } from 'react';
import { X } from 'lucide-react';
import type { CanvasWidget as CanvasWidgetType } from '../../../types/ai';
import { getWidget } from '../widgets/WidgetRegistry';
import { WidgetErrorBoundary } from '../widgets/WidgetErrorBoundary';
import { useNavigate } from 'react-router-dom';

interface CanvasWidgetProps {
  widget: CanvasWidgetType;
  onRemove: (widgetId: string) => void;
}

function WidgetErrorFallback({ toolName }: { toolName: string }) {
  return (
    <div className="flex items-center justify-center p-4 text-sm text-muted-foreground">
      {toolName} 위젯을 표시할 수 없습니다.
    </div>
  );
}

export function CanvasWidget({ widget, onRemove }: CanvasWidgetProps) {
  const navigate = useNavigate();
  const [removing, setRemoving] = useState(false);
  const entry = getWidget(widget.toolName);

  const handleRemove = () => {
    setRemoving(true);
    // allow animation to complete before removal
    setTimeout(() => onRemove(widget.id), 200);
  };

  const widthClass =
    widget.layout.width === 'third' ? 'col-span-1' :
    widget.layout.width === 'half' ? 'col-span-1' :
    'col-span-full';

  return (
    <div
      className={`relative rounded-lg border border-border bg-background overflow-hidden ${widthClass}`}
      style={{
        animation: removing
          ? 'canvas-widget-out 200ms ease-in forwards'
          : 'canvas-widget-in 300ms ease-out',
      }}
    >
      <button
        type="button"
        onClick={handleRemove}
        className="absolute top-1.5 right-1.5 z-10 flex h-5 w-5 items-center justify-center rounded-full bg-background/80 text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
        aria-label="위젯 닫기"
      >
        <X className="h-3 w-3" />
      </button>
      {entry ? (
        <WidgetErrorBoundary>
          <Suspense fallback={<div className="h-20 animate-pulse bg-muted/30 rounded" />}>
            <entry.component
              input={widget.input}
              onNavigate={(path) => navigate(path)}
              displayMode="native"
            />
          </Suspense>
        </WidgetErrorBoundary>
      ) : (
        <WidgetErrorFallback toolName={widget.toolName} />
      )}
    </div>
  );
}
