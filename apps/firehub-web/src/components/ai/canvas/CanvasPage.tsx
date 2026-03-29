import type { CanvasPage as CanvasPageType } from '../../../types/ai';
import { CanvasWidget } from './CanvasWidget';

interface CanvasPageProps {
  page: CanvasPageType;
  onRemoveWidget: (pageId: string, widgetId: string) => void;
  direction?: 'left' | 'right' | 'none';
}

function getColCount(page: CanvasPageType): number {
  if (page.widgets.some((w) => w.layout.width === 'third')) return 3;
  if (page.widgets.some((w) => w.layout.width === 'half')) return 2;
  return 1;
}

export function CanvasPage({ page, onRemoveWidget, direction = 'none' }: CanvasPageProps) {
  const animationStyle =
    direction === 'left'
      ? { animation: 'canvas-page-slide-in-left 300ms ease-out' }
      : direction === 'right'
      ? { animation: 'canvas-page-slide-in-right 300ms ease-out' }
      : {};

  if (page.widgets.length === 0) {
    return (
      <div
        className="flex flex-1 flex-col items-center justify-center gap-3 text-center p-8"
        style={animationStyle}
      >
        <div className="text-4xl opacity-30">⬜</div>
        <p className="text-sm text-muted-foreground">
          AI에게 요청하면 여기에 결과가 표시됩니다
        </p>
      </div>
    );
  }

  const colCount = getColCount(page);
  const fullWidgets = page.widgets.filter((w) => w.layout.width === 'full').length;
  const partialWidgets = page.widgets.length - fullWidgets;
  const rowCount = fullWidgets + Math.ceil(partialWidgets / colCount) || 1;

  return (
    <div
      className="flex-1 overflow-hidden p-4"
      style={animationStyle}
    >
      <div
        className="grid gap-3 h-full"
        style={{
          gridTemplateColumns: `repeat(${colCount}, 1fr)`,
          gridTemplateRows: `repeat(${rowCount}, 1fr)`,
        }}
      >
        {page.widgets.map((widget) => (
          <CanvasWidget
            key={widget.id}
            widget={widget}
            onRemove={(widgetId) => onRemoveWidget(page.id, widgetId)}
          />
        ))}
      </div>
    </div>
  );
}
