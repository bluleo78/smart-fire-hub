import { useQueryClient } from '@tanstack/react-query';
import { KeyRound } from 'lucide-react';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';

import { datasetsApi } from '../../../api/datasets';
import { Button } from '../../../components/ui/button';
import { Checkbox } from '../../../components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../../components/ui/dialog';
import { Label } from '../../../components/ui/label';
import { handleApiError } from '../../../lib/api-error';
import type { DatasetColumnResponse } from '../../../types/dataset';

interface PrimaryKeysDialogProps {
  datasetId: number;
  columns: DatasetColumnResponse[];
  hasData: boolean;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * 기본 키(PK) 컬럼 집합을 한 번에 갱신하는 다이얼로그 (#117).
 *
 * 단일 컬럼 PUT 으로는 데이터 존재 시 복합 PK 의 중간 상태가 unique 검증을
 * 통과하지 못하므로, 최종 PK 집합을 한 번에 백엔드에 전달하여 트랜잭션
 * 안에서 일괄 갱신한다. 백엔드가 NOT NULL · 복합 unique 검증과 unique
 * index 재생성을 처리하며, 위반 시 명확한 예외 메시지를 반환한다.
 */
export function PrimaryKeysDialog({
  datasetId,
  columns,
  hasData,
  open,
  onOpenChange,
}: PrimaryKeysDialogProps) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [submitting, setSubmitting] = useState(false);

  // 다이얼로그가 열릴 때 현재 PK 컬럼 집합으로 초기화한다.
  useEffect(() => {
    if (open) {
      setSelected(new Set(columns.filter((c) => c.isPrimaryKey).map((c) => c.id)));
    }
  }, [open, columns]);

  const toggle = (columnId: number, isNullable: boolean) => {
    if (isNullable) {
      // PK 는 NOT NULL 컬럼에서만 가능 — 토글 시도 시 안내
      toast.error('NULL 허용 컬럼은 기본 키로 지정할 수 없습니다.');
      return;
    }
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(columnId)) next.delete(columnId);
      else next.add(columnId);
      return next;
    });
  };

  const onSubmit = async () => {
    setSubmitting(true);
    try {
      // 컬럼 표시 순서를 유지하기 위해 columns 순서대로 ID 배열 구성
      const columnIds = columns.filter((c) => selected.has(c.id)).map((c) => c.id);
      await datasetsApi.updatePrimaryKeys(datasetId, columnIds);
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      toast.success('기본 키가 갱신되었습니다.');
      onOpenChange(false);
    } catch (error) {
      handleApiError(error, '기본 키 갱신에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <KeyRound className="h-4 w-4" />
            기본 키 일괄 설정
          </DialogTitle>
          <DialogDescription>
            기본 키로 사용할 컬럼들을 선택하세요. 여러 개 선택 시 복합 키로 동작합니다.
            {hasData && (
              <span className="mt-1 block text-destructive">
                데이터가 있는 데이터셋은 선택한 컬럼 조합이 unique 해야 적용됩니다.
              </span>
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-2">
          {columns.map((col) => {
            const isPk = selected.has(col.id);
            const disabled = col.isNullable;
            return (
              <div
                key={col.id}
                className="flex items-center justify-between rounded-md border px-3 py-2"
              >
                <div className="flex items-center gap-2">
                  <Checkbox
                    id={`pk-col-${col.id}`}
                    checked={isPk}
                    disabled={disabled}
                    onCheckedChange={() => toggle(col.id, col.isNullable)}
                    aria-label={`${col.columnName} 기본 키 토글`}
                  />
                  <Label htmlFor={`pk-col-${col.id}`} className="cursor-pointer">
                    <span className="font-medium">{col.columnName}</span>
                    {col.displayName && (
                      <span className="ml-1 text-muted-foreground">({col.displayName})</span>
                    )}
                  </Label>
                </div>
                <div className="flex gap-2 text-xs text-muted-foreground">
                  <span>{col.dataType}</span>
                  {col.isNullable && (
                    <span className="text-amber-600">NULL 허용 — PK 불가</span>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={submitting}>
            취소
          </Button>
          <Button onClick={onSubmit} disabled={submitting}>
            {submitting ? '적용 중...' : '적용'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
