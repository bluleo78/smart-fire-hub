import { Share2 } from 'lucide-react';
import { useMemo, useState } from 'react';
import { toast } from 'sonner';

import { AnalyticsDeleteConfirmDialog } from '@/components/analytics/AnalyticsDeleteConfirmDialog';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { SearchInput } from '@/components/ui/search-input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import {
  useAdminDeleteAnalyticsItem,
  useAdminUnshareAnalyticsItem,
  useWorkspaceAnalyticsItems,
} from '@/hooks/queries/useAnalytics';
import { handleApiError } from '@/lib/api-error';
import { formatDateShort, formatRelativeTime } from '@/lib/formatters';
import { iGa } from '@/lib/utils';
import type { AnalyticsAdminItem, AnalyticsAdminKind } from '@/types/analytics';

/** 종류별 문구 — 대시보드·차트·쿼리가 다른 곳은 여기에만 둔다. */
const COPY: Record<AnalyticsAdminKind, { entity: string; tableLabel: string; empty: string; unshareBody: string }> = {
  dashboards: {
    entity: '대시보드',
    tableLabel: '워크스페이스 전체 대시보드',
    empty: '워크스페이스에 대시보드가 없습니다.',
    unshareBody: '다른 사람의 대시보드 목록과 화면에서 사라집니다. 위젯이 쓰는 차트·쿼리의 공유 여부는 바뀌지 않습니다.',
  },
  charts: {
    entity: '차트',
    tableLabel: '워크스페이스 전체 차트',
    empty: '워크스페이스에 차트가 없습니다.',
    unshareBody: '다른 사람의 차트 목록에서 사라지고, 이 차트를 담은 다른 사람의 대시보드 위젯은 「볼 수 없는 차트」로 가려집니다.',
  },
  queries: {
    entity: '쿼리',
    tableLabel: '워크스페이스 전체 쿼리',
    empty: '워크스페이스에 저장된 쿼리가 없습니다.',
    unshareBody: '다른 사람의 쿼리 목록에서 사라져 열거나 실행할 수 없게 됩니다. 이 쿼리로 만든 차트의 공유 여부는 바뀌지 않습니다.',
  },
};

/** 쿼리·차트 삭제는 CASCADE 영향 범위를 보여 주는 창을, 대시보드는 위젯만 함께 사라지므로 일반 삭제 창을 쓴다. */
const IMPACT_KIND: Partial<Record<AnalyticsAdminKind, 'query' | 'chart'>> = {
  queries: 'query',
  charts: 'chart',
};

/** 작성자 표시 — 계정이 지워졌으면 이름이 없다. */
function ownerLabel(item: AnalyticsAdminItem): string {
  return item.ownerName ?? '알 수 없는 사용자';
}

/** 공유 해제 확인 창 — 되돌릴 수 있는 변경이라 destructive 가 아닌 기본 확인 버튼을 쓴다. */
function UnshareConfirmDialog({
  kind,
  item,
  disabled,
  onConfirm,
}: {
  kind: AnalyticsAdminKind;
  item: AnalyticsAdminItem;
  disabled: boolean;
  onConfirm: () => void;
}) {
  const copy = COPY[kind];
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button size="sm" variant="outline" aria-label={`${item.name} 공유 해제`} disabled={disabled}>
          공유 해제
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{copy.entity} 공유 해제</AlertDialogTitle>
          <AlertDialogDescription>
            &quot;{item.name}&quot; {copy.entity}(작성자: {ownerLabel(item)})의 공유를 해제하시겠습니까? {copy.unshareBody}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm}>공유 해제</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * WorkspaceAnalyticsTable — 워크스페이스 전체 대시보드·차트·저장 쿼리 (WD-199, analytics:admin 전용).
 *
 * 작성자가 정지·제거되면 그가 공유한 분석 자산은 다른 사람에게 계속 보이고 실행되는데, 수정·삭제가 작성자 한정이라 아무도 정리할 수
 * 없었다. 관리자는 여기서 남의 자산을 공유 해제하거나 삭제만 할 수 있다(WD-192 스마트 작업 「워크스페이스 전체」와 같은 방식).
 * - 서버가 최소 정보(이름·작성자·작성자 활성 여부·공유 여부·수정일)만 준다 — SQL·차트 설정·위젯·설명은 없다.
 * - 행을 눌러 상세로 가지 않는다 — 남의 비공개 자산은 서버가 404 로 막는다(열람·편집·실행 불가).
 * - 공유하기는 없다 — 남의 자산을 다른 사람에게 새로 공개하는 것은 작성자만 정한다.
 * - 목록은 페이지 없이 한 번에 받으므로 검색은 화면에서 이름·작성자로 거른다.
 */
export function WorkspaceAnalyticsTable({ kind }: { kind: AnalyticsAdminKind }) {
  const copy = COPY[kind];
  const impactKind = IMPACT_KIND[kind];
  const { data: items = [], isLoading, isError } = useWorkspaceAnalyticsItems(kind);
  const unshareMutation = useAdminUnshareAnalyticsItem(kind);
  const deleteMutation = useAdminDeleteAnalyticsItem(kind);
  const [search, setSearch] = useState('');

  const filtered = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!keyword) return items;
    return items.filter(
      (item) => item.name.toLowerCase().includes(keyword) || (item.ownerName ?? '').toLowerCase().includes(keyword),
    );
  }, [items, search]);

  const handleUnshare = (item: AnalyticsAdminItem) => {
    unshareMutation.mutate(item.id, {
      onSuccess: () => toast.success(`${copy.entity} "${item.name}"의 공유를 해제했습니다.`),
      onError: (err) => handleApiError(err, `${copy.entity} 공유 해제에 실패했습니다.`),
    });
  };

  const handleDelete = (item: AnalyticsAdminItem) => {
    deleteMutation.mutate(item.id, {
      onSuccess: () => toast.success(`${copy.entity} "${item.name}"${iGa(item.name)} 삭제되었습니다.`),
      onError: (err) => handleApiError(err, `${copy.entity} 삭제에 실패했습니다.`),
    });
  };

  if (isError) {
    return (
      <div
        className="rounded-lg border border-dashed flex flex-col items-center justify-center py-16 gap-1 text-center"
        data-testid="workspace-analytics-error"
      >
        <p className="text-sm font-medium">워크스페이스 {copy.entity} 목록을 불러오지 못했습니다</p>
        <p className="text-xs text-muted-foreground">잠시 후 다시 시도해 주세요.</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">
        워크스페이스의 모든 {copy.entity}입니다. 다른 사람의 {copy.entity}는 공유 해제하거나 삭제만 할 수 있습니다.
      </p>
      <div className="flex items-center gap-3 flex-wrap">
        <SearchInput placeholder="이름·작성자 검색..." value={search} onChange={setSearch} />
      </div>
      <div className="rounded-md border">
        <Table aria-label={copy.tableLabel}>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>작성자</TableHead>
              <TableHead>공유</TableHead>
              <TableHead>수정일</TableHead>
              <TableHead className="w-[160px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={5} rows={5} />
            ) : filtered.length > 0 ? (
              filtered.map((item) => (
                <TableRow key={item.id} data-testid={`workspace-analytics-${kind}-${item.id}`}>
                  <TableCell className="font-medium">
                    <span className="block truncate max-w-xs" title={item.name}>
                      {item.name}
                    </span>
                  </TableCell>
                  <TableCell className="text-sm">
                    <div className="flex items-center gap-1.5">
                      <span>{ownerLabel(item)}</span>
                      {!item.ownerActive && (
                        // 정지·제거·계정 비활성 작성자 — 정리 대상을 고르는 근거라 눈에 띄게 둔다
                        <Badge variant="outline" className="text-xs py-0 text-destructive border-destructive/40">
                          비활성 작성자
                        </Badge>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
                    {item.isShared ? (
                      <Badge variant="secondary" className="text-xs gap-1 py-0">
                        <Share2 className="h-2.5 w-2.5" />
                        공유
                      </Badge>
                    ) : (
                      <span className="text-sm text-muted-foreground">비공개</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <span className="text-sm text-muted-foreground" title={formatDateShort(item.updatedAt)}>
                      {formatRelativeTime(item.updatedAt)}
                    </span>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center justify-end gap-1">
                      {item.isShared && (
                        <UnshareConfirmDialog
                          kind={kind}
                          item={item}
                          disabled={unshareMutation.isPending}
                          onConfirm={() => handleUnshare(item)}
                        />
                      )}
                      {impactKind ? (
                        <AnalyticsDeleteConfirmDialog
                          admin
                          kind={impactKind}
                          id={item.id}
                          itemName={item.name}
                          extraDetail={`작성자: ${ownerLabel(item)}`}
                          onConfirm={() => handleDelete(item)}
                          trigger={
                            <Button
                              size="sm"
                              variant="ghost"
                              className="text-destructive"
                              aria-label={`${item.name} 삭제`}
                              disabled={deleteMutation.isPending}
                            >
                              삭제
                            </Button>
                          }
                        />
                      ) : (
                        <DeleteConfirmDialog
                          entityName={copy.entity}
                          itemName={item.name}
                          extraDetail={`작성자: ${ownerLabel(item)}`}
                          onConfirm={() => handleDelete(item)}
                          trigger={
                            <Button
                              size="sm"
                              variant="ghost"
                              className="text-destructive"
                              aria-label={`${item.name} 삭제`}
                              disabled={deleteMutation.isPending}
                            >
                              삭제
                            </Button>
                          }
                        />
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={5}
                message={copy.empty}
                searchKeyword={search || undefined}
                onResetSearch={search ? () => setSearch('') : undefined}
              />
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
