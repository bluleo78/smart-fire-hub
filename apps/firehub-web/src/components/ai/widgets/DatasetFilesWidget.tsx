import { useQuery } from '@tanstack/react-query';

import { datasetsApi } from '../../../api/datasets';
import { useObjectList } from '../../../hooks/queries/useObjects';
import { formatObjectDate, formatObjectSize, openObjectInNewTab } from '../../../lib/objectFile';
import type { WidgetProps } from './types';
import { WidgetShell } from './WidgetShell';

/** show_dataset_files 도구 입력 — 표시할 FILE 데이터셋 ID만 받는다(Reference 패턴). */
interface ShowDatasetFilesInput {
  datasetId: number;
}

/**
 * FILE(오브젝트) 데이터셋의 파일 목록을 AI 챗에 인라인 카드로 표시하는 위젯.
 *
 * Reference 패턴: 에이전트는 datasetId 만 전달하고, 실제 파일 목록은 프론트엔드가 직접
 * 조회한다. FILE 데이터셋은 파일을 DB 행으로 개별 관리하지 않으므로 목록은 오브젝트 스토리지
 * ListObjects(토큰 페이지네이션)로 실시간 계산된다. 각 파일 클릭 시 짧은 만료의 presigned
 * URL을 발급해 새 탭에서 열기/다운로드한다(에이전트가 바이트를 직접 다루지 않음).
 */
export default function DatasetFilesWidget({
  input,
  onNavigate,
  displayMode,
}: WidgetProps<ShowDatasetFilesInput>) {
  const datasetId = Number(input.datasetId);

  // 카드 제목용 데이터셋 이름. 메타 조회가 실패해도 목록 표시는 계속한다(제목만 폴백).
  const { data: dataset } = useQuery({
    queryKey: ['dataset', datasetId],
    queryFn: () => datasetsApi.getDatasetById(datasetId).then((r) => r.data),
    staleTime: 30_000,
    enabled: !!datasetId,
  });

  const { data, isLoading, isError, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useObjectList(datasetId);
  // 무한쿼리 페이지들을 단일 목록으로 평탄화한다.
  const items = data?.pages.flatMap((p) => p.objects) ?? [];

  const title = dataset?.name ?? '파일 목록';

  if (isLoading) {
    return (
      <WidgetShell title={title} icon="📁" displayMode={displayMode} onNavigate={onNavigate}>
        <div className="flex items-center justify-center py-6 text-sm text-muted-foreground">
          불러오는 중...
        </div>
      </WidgetShell>
    );
  }

  if (isError) {
    return (
      <WidgetShell title={title} icon="📁" displayMode={displayMode} onNavigate={onNavigate}>
        <div className="flex items-center justify-center py-6 text-sm text-destructive">
          파일 목록을 불러올 수 없습니다.
        </div>
      </WidgetShell>
    );
  }

  return (
    <WidgetShell
      title={title}
      icon="📁"
      // 페이지네이션으로 전체 수를 모를 수 있어, 더 있으면 'N+개'로 표기한다.
      subtitle={`파일 ${items.length}${hasNextPage ? '+' : ''}개`}
      navigateTo={datasetId ? `/data/datasets/${datasetId}` : undefined}
      onNavigate={onNavigate}
      displayMode={displayMode}
    >
      {items.length === 0 ? (
        <div className="px-3 py-4 text-center text-xs text-muted-foreground">파일이 없습니다.</div>
      ) : (
        <div>
          {/* S3 스타일 목록: 이름 / 크기. 행 클릭으로 열기/다운로드.
              좁은 챗 사이드 패널에서 이름이 0폭으로 눌리지 않도록 고정폭 그리드 대신
              flex(이름=flex-1 truncate + 크기=shrink-0)를 쓴다. 수정일은 tooltip(title)로 제공한다. */}
          <div className="flex items-center gap-2 border-b border-border bg-muted/30 px-3 py-1.5 text-xs font-medium text-muted-foreground">
            <span className="min-w-0 flex-1">이름</span>
            <span className="shrink-0">크기</span>
          </div>
          {items.map((o) => (
            <button
              key={o.key}
              type="button"
              onClick={() => openObjectInNewTab(datasetId, o.key)}
              title={`${o.key} · ${formatObjectDate(o.lastModified)}`}
              className="flex w-full items-center gap-2 border-b border-border/50 px-3 py-1.5 text-left text-xs last:border-0 hover:bg-muted/20"
            >
              <span className="min-w-0 flex-1 truncate">{o.name}</span>
              <span className="shrink-0 text-muted-foreground">{formatObjectSize(o.size)}</span>
            </button>
          ))}
          {hasNextPage && (
            <div className="p-2 text-center">
              <button
                type="button"
                onClick={() => fetchNextPage()}
                disabled={isFetchingNextPage}
                className="rounded-md border px-3 py-1 text-xs hover:bg-accent"
              >
                {isFetchingNextPage ? '불러오는 중...' : '더 보기'}
              </button>
            </div>
          )}
        </div>
      )}
    </WidgetShell>
  );
}
