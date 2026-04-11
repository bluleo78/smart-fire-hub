import { useCallback, useMemo, useState } from 'react';

import type { AIMessage, CanvasLayout, CanvasPage, CanvasWidget } from '../types/ai';

function generatePageId() {
  return `page-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function groupWidgetsIntoPages(widgets: CanvasWidget[]): CanvasPage[] {
  if (widgets.length === 0) return [];

  const pageMap = new Map<string, CanvasWidget[]>();
  const pageOrder: string[] = [];

  for (const widget of widgets) {
    const label = widget.layout.pageLabel || '복원된 세션';
    if (!pageMap.has(label)) {
      pageMap.set(label, []);
      pageOrder.push(label);
    }
    pageMap.get(label)!.push(widget);
  }

  return pageOrder.map((label) => ({
    id: generatePageId(),
    label,
    widgets: pageMap.get(label)!,
  }));
}

export function useCanvasState() {
  const [pages, setPages] = useState<CanvasPage[]>([]);
  const [activePageIndex, setActivePageIndex] = useState(0);

  const activePage = pages[activePageIndex] ?? null;

  const addWidget = useCallback((widget: CanvasWidget) => {
    setPages((prev) => {
      // ID-based dedup guard
      if (prev.some((p) => p.widgets.some((w) => w.id === widget.id))) return prev;

      const layout = widget.layout;

      if (layout.page === 'new') {
        const newPage: CanvasPage = {
          id: generatePageId(),
          label: layout.pageLabel || `페이지 ${prev.length + 1}`,
          widgets: [widget],
        };
        setActivePageIndex(prev.length);
        return [...prev, newPage];
      }

      // Replace widget if replace ID is specified
      if (layout.replace) {
        return prev.map((page) => ({
          ...page,
          widgets: page.widgets.map((w) =>
            w.id === layout.replace ? widget : w
          ),
        }));
      }

      // Add to current page (or create first page)
      if (prev.length === 0) {
        const firstPage: CanvasPage = {
          id: generatePageId(),
          label: layout.pageLabel || '페이지 1',
          widgets: [widget],
        };
        setActivePageIndex(0);
        return [firstPage];
      }

      // Add widget to the active page (captured via closure at call time)
      // We can't access activePageIndex here directly because of stale closure,
      // so we add to the last page as default for 'current'
      return prev.map((page, idx) => {
        if (idx !== prev.length - 1) return page;
        return { ...page, widgets: [...page.widgets, widget] };
      });
    });
  }, []);

  const removeWidget = useCallback((pageId: string, widgetId: string) => {
    setPages((prev) => {
      const updated = prev.map((page) => {
        if (page.id !== pageId) return page;
        return { ...page, widgets: page.widgets.filter((w) => w.id !== widgetId) };
      }).filter((page) => page.widgets.length > 0);

      return updated;
    });
    setActivePageIndex((prev) => Math.max(0, prev));
  }, []);

  const goToPage = useCallback((index: number) => {
    setActivePageIndex(index);
  }, []);

  const resetCanvas = useCallback(() => {
    setPages([]);
    setActivePageIndex(0);
  }, []);

  const restoreFromMessages = useCallback((messages: AIMessage[]) => {
    const widgets: CanvasWidget[] = [];
    for (const msg of messages) {
      if (msg.toolCalls) {
        for (const tc of msg.toolCalls) {
          const canvasLayout = tc.input?.canvas as CanvasLayout | undefined;
          if (canvasLayout) {
            widgets.push({
              id: tc.id || `restored-${widgets.length}`,
              toolName: tc.name,
              input: tc.input || {},
              layout: canvasLayout,
              timestamp: msg.timestamp || new Date().toISOString(),
            });
          }
        }
      }
    }
    setPages(groupWidgetsIntoPages(widgets));
    setActivePageIndex(0);
  }, []);

  return useMemo(() => ({
    pages,
    activePageIndex,
    activePage,
    addWidget,
    removeWidget,
    goToPage,
    resetCanvas,
    restoreFromMessages,
  }), [pages, activePageIndex, activePage, addWidget, removeWidget, goToPage, resetCanvas, restoreFromMessages]);
}
