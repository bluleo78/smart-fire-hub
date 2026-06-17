import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

interface DatasetListItem {
  id: number;
  name: string;
  // 데이터셋 유형이 저장 방식(storageType) + 출처(originType)로 분리됨
  storageType?: string;
  originType?: string;
  rowCount?: number;
  updatedAt?: string;
}

interface ShowDatasetListInput {
  items: DatasetListItem[];
}

/** 저장 방식 라벨 — DatasetListPage와 동일하게 테이블/문서로 표시 */
function storageLabel(storageType?: string): string {
  return storageType === 'DOCUMENT' ? '문서' : '테이블';
}

/** 출처 라벨 — 원본/파생/임시 */
function originLabel(originType?: string): string {
  if (originType === 'DERIVED') return '파생';
  if (originType === 'TEMP') return '임시';
  return '원본';
}

function formatDate(dateStr: string | undefined): string {
  if (!dateStr) return '-';
  return new Date(dateStr).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
}

export default function DatasetListWidget({ input, onNavigate, displayMode }: WidgetProps<ShowDatasetListInput>) {
  const items = input.items ?? [];

  return (
    <WidgetShell
      title={`데이터셋 ${items.length}건`}
      icon="📦"
      displayMode={displayMode}
      onNavigate={onNavigate}
    >
      {items.length === 0 ? (
        <div className="flex items-center justify-center py-6 text-sm text-muted-foreground">데이터셋이 없습니다.</div>
      ) : (
        <div className="divide-y divide-border/50">
          {items.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigate?.(`/data/datasets/${item.id}`)}
              className="w-full text-left px-3 py-2 hover:bg-muted/20 transition-colors duration-150"
            >
              <div className="flex items-center gap-2">
                <span className="flex-1 truncate text-sm font-medium">{item.name}</span>
                {/* 저장 방식 + 출처를 두 배지로 분리 표시 — DatasetListPage와 일관 */}
                {item.storageType && (
                  <span className="shrink-0 rounded-full px-2 py-0.5 text-xs font-medium bg-secondary/10 text-secondary-foreground">
                    {storageLabel(item.storageType)}
                  </span>
                )}
                {item.originType && (
                  <span className="shrink-0 rounded-full px-2 py-0.5 text-xs font-medium bg-primary/10 text-primary">
                    {originLabel(item.originType)}
                  </span>
                )}
              </div>
              <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
                {item.rowCount != null && <span>{item.rowCount.toLocaleString('ko-KR')}건</span>}
                {item.updatedAt && <span>수정: {formatDate(item.updatedAt)}</span>}
              </div>
            </button>
          ))}
        </div>
      )}
    </WidgetShell>
  );
}
