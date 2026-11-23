import { ExternalLink } from 'lucide-react';

import { cn } from '../../../lib/utils';
import type { WidgetShellProps } from './types';

const MAX_HEIGHT: Record<string, string> = {
  side: 'max-h-[250px]',
  floating: 'max-h-[250px]',
  fullscreen: 'max-h-[450px]',
  native: 'max-h-none',
};

export function WidgetShell({
  title, icon, subtitle, actions, navigateTo, onNavigate, displayMode, children,
}: WidgetShellProps) {
  return (
    // data-testid: 시험이 홈 화면 목록과 섞이지 않게 위젯 카드 범위를 잡는 표지 (WD-1027)
    <div data-testid="widget-shell" className="my-1 overflow-hidden rounded-lg border border-border">
      {/* WD-755: 머리 띠는 본문과 갈라지는 구조 띠라 알파 없는 단색 bg-muted(§1-7 — /50 은 상태 표시에만).
          그래서 띠 안 동작 버튼(actions)의 hover·열림은 bg-muted 가 아니라 띠보다 밝은 bg-card 로 둔다(TableWidget·ExportDropdown). */}
      <div className="flex items-center justify-between gap-2 border-b border-border bg-muted px-3 py-1.5">
        <div className="flex items-center gap-2 min-w-0">
          <span className="shrink-0">{icon}</span>
          {/* 긴 위젯 제목이 말줄임돼도 마우스를 올리면 전체가 보이게 (WD-1027) */}
          <span className="truncate font-medium text-sm" title={title}>{title}</span>
          {subtitle && <span className="shrink-0 text-xs text-muted-foreground">{subtitle}</span>}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {actions}
          {navigateTo && onNavigate && (
            <button
              onClick={() => onNavigate(navigateTo)}
              className="flex items-center gap-1 text-xs text-primary hover:underline"
            >
              상세 보기
              <ExternalLink className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>
      <div className={cn('overflow-auto', MAX_HEIGHT[displayMode] || MAX_HEIGHT.side)}>
        {children}
      </div>
    </div>
  );
}
