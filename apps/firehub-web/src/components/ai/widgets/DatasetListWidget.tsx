import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

interface DatasetListItem {
  id: number;
  name: string;
  datasetType?: string;
  rowCount?: number;
  updatedAt?: string;
}

interface ShowDatasetListInput {
  items: DatasetListItem[];
}

const TYPE_LABEL: Record<string, string> = {
  SOURCE: '원본',
  DERIVED: '파생',
  TEMP: '임시',
};

/* 데이터셋 타입별 배지 색상 — 시맨틱 토큰 사용 */
const TYPE_CLASS: Record<string, string> = {
  SOURCE: 'bg-info/10 text-info',
  DERIVED: 'bg-success/10 text-success',
  TEMP: 'bg-warning/10 text-warning',
};

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
                {item.datasetType && (
                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${TYPE_CLASS[item.datasetType] ?? 'bg-muted text-muted-foreground'}`}>
                    {TYPE_LABEL[item.datasetType] ?? item.datasetType}
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
