import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../../components/ui/alert-dialog';
import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../../components/ui/card';
import { Checkbox } from '../../../components/ui/checkbox';
import { Progress } from '../../../components/ui/progress';
import { StatusBadge, type StatusBadgeType } from '../../../components/ui/status-badge';
import { useReindexSearchIndex, useSearchIndex, useUpdateSearchIndex } from '../../../hooks/queries/useDatasetSearchIndex';
import { handleApiError } from '../../../lib/api-error';
import { formatDate } from '../../../lib/formatters';
import type { DatasetDetailResponse, SearchIndexStatus } from '../../../types/dataset';

interface DatasetSearchTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

/** 검색 대상으로 지정할 수 있는 타입(백엔드 SEARCHABLE_TYPES 와 동일). */
const SEARCHABLE_TYPES = new Set(['TEXT', 'VARCHAR']);

/**
 * 상태 배지 문구·의미 타입.
 * 디자인 시스템 규칙상 Badge variant 를 직접 고르지 않고 의미 기반 StatusBadge 를 쓴다
 * (꺼짐=inactive 회색, 색인 중=info 파랑, 사용 가능=success 녹색, 오류=error 빨강).
 */
const STATUS_LABEL: Record<SearchIndexStatus['status'], { text: string; type: StatusBadgeType }> = {
  OFF: { text: '꺼짐', type: 'inactive' },
  SYNCING: { text: '색인 중', type: 'info' },
  IDLE: { text: '사용 가능', type: 'success' },
  ERROR: { text: '오류', type: 'error' },
};

/**
 * 데이터셋 상세 "검색" 탭.
 * ① 색인 상태(AI 챗이 이 데이터셋의 행을 내용으로 찾을 수 있는지) ② 검색 대상 필드 선택.
 * 합치는 순서는 필드 순서를 따른다(별도 순서 UI 없음 — 설계 결정).
 */
export function DatasetSearchTab({ dataset, datasetId }: DatasetSearchTabProps) {
  const { data: status, isLoading } = useSearchIndex(datasetId);
  const update = useUpdateSearchIndex(datasetId);
  const reindex = useReindexSearchIndex(datasetId);

  const columns = useMemo(
    () => [...dataset.columns].sort((a, b) => a.columnOrder - b.columnOrder),
    [dataset.columns],
  );
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [confirming, setConfirming] = useState(false);

  // 서버에 저장된 필드 목록이 바뀔 때만(최초 로드·저장 후) 선택을 동기화한다.
  // status 객체 전체에 걸면 색인 중 5초 폴링마다(indexedRows 변화) 사용자가 고치던 체크가 초기화된다.
  const savedFieldsKey = status?.fields.join(',') ?? '';
  useEffect(() => {
    // 서버에 저장된 값으로 편집 상태를 시드하는 의도적 동기화 — 키(저장 필드 문자열)가 바뀔 때만 실행된다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelected(new Set(savedFieldsKey ? savedFieldsKey.split(',') : []));
  }, [savedFieldsKey]);

  const dirty = useMemo(() => {
    const saved = new Set(status?.fields ?? []);
    return saved.size !== selected.size || [...selected].some((f) => !saved.has(f));
  }, [status, selected]);

  const toggle = (name: string, checked: boolean) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (checked) next.add(name);
      else next.delete(name);
      return next;
    });

  const save = async () => {
    setConfirming(false);
    // 필드 순서대로 보낸다 — 서버는 column_order 로 합치지만 payload 도 예측 가능하게 유지
    const fields = columns.map((c) => c.columnName).filter((n) => selected.has(n));
    try {
      await update.mutateAsync(fields);
      toast.success(fields.length ? '검색 설정을 저장했습니다. 색인을 시작합니다.' : '검색을 껐습니다.');
    } catch (error) {
      handleApiError(error, '검색 설정 저장에 실패했습니다.');
    }
  };

  const startReindex = () =>
    reindex.mutate(undefined, {
      onSuccess: () => toast.success('다시 색인을 시작했습니다.'),
      onError: (error) => handleApiError(error, '다시 색인 요청에 실패했습니다.'),
    });

  if (isLoading || !status) {
    return <div className="text-sm text-muted-foreground">불러오는 중...</div>;
  }

  const label = STATUS_LABEL[status.status];
  const percent = status.totalRows > 0 ? Math.round((status.indexedRows / status.totalRows) * 100) : 0;

  return (
    <div className="space-y-4" data-testid="search-tab">
      {/* ① 색인 상태 */}
      <Card>
        <CardHeader className="flex flex-row items-start justify-between space-y-0">
          <div>
            <CardTitle>색인 상태</CardTitle>
            <CardDescription>AI 챗이 이 데이터셋의 행을 내용으로 찾을 수 있는지 보여줍니다.</CardDescription>
          </div>
          {status.enabled && (
            <Button variant="outline" size="sm" onClick={startReindex} disabled={reindex.isPending}>
              다시 색인
            </Button>
          )}
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-3 text-sm">
          <StatusBadge type={label.type} data-testid="search-index-status">
            {label.text}
          </StatusBadge>
          {status.enabled && (
            <>
              <Progress value={percent} className="w-48" aria-label="색인 진행률" />
              <span>
                {status.indexedRows.toLocaleString()} / {status.totalRows.toLocaleString()}행
              </span>
              {status.lastSyncedAt && (
                <span className="text-muted-foreground">
                  · 마지막 동기화 {formatDate(status.lastSyncedAt)}
                </span>
              )}
              {status.embeddingModel && <span className="text-muted-foreground">· 모델 {status.embeddingModel}</span>}
            </>
          )}
          {status.status === 'ERROR' && status.lastError && (
            <p className="w-full text-destructive">{status.lastError}</p>
          )}
        </CardContent>
      </Card>

      {/* ② 검색 대상 필드 */}
      <Card>
        <CardHeader>
          <CardTitle>검색 대상 필드</CardTitle>
          <CardDescription>체크한 필드의 내용을 합쳐서 검색합니다. TEXT·VARCHAR 필드만 선택할 수 있습니다.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-2">
          {columns.map((col) => {
            const allowed = SEARCHABLE_TYPES.has(col.dataType);
            const labelText = col.displayName || col.columnName;
            return (
              <label
                key={col.id}
                className={`flex items-center gap-3 rounded-md px-2 py-1.5 ${allowed ? '' : 'text-muted-foreground'}`}
              >
                <Checkbox
                  aria-label={labelText}
                  checked={selected.has(col.columnName)}
                  disabled={!allowed}
                  onCheckedChange={(v) => toggle(col.columnName, v === true)}
                />
                <span>{labelText}</span>
                <span className="font-mono text-xs text-muted-foreground">{col.columnName}</span>
                <Badge variant="outline" className="text-[10px]">
                  {col.dataType}
                </Badge>
              </label>
            );
          })}
          <div className="flex justify-end pt-2">
            <Button onClick={() => setConfirming(true)} disabled={!dirty || update.isPending}>
              {update.isPending ? '저장 중...' : '저장'}
            </Button>
          </div>
        </CardContent>
      </Card>

      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>검색 설정을 저장합니다</AlertDialogTitle>
            <AlertDialogDescription>
              {selected.size > 0
                ? '검색 대상 필드가 바뀌면 전체 행을 다시 색인합니다. 색인이 끝날 때까지 검색 결과가 일부만 나올 수 있습니다.'
                : '모든 필드를 해제하면 검색이 꺼지고 색인이 삭제됩니다.'}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={save}>저장하고 다시 색인</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
