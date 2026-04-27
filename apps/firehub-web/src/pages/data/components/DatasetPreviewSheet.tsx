import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { datasetsApi } from '../../../api/datasets';
import { Button } from '../../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '../../../components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../../components/ui/select';
import { Skeleton } from '../../../components/ui/skeleton';

interface DatasetPreviewSheetProps {
  datasetId: number;
  datasetName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** 미리보기 다이얼로그에서 선택 가능한 샘플 크기 옵션 (#111) */
const SAMPLE_SIZES = [5, 10, 50, 100] as const;

/**
 * 데이터셋 미리보기 다이얼로그
 *
 * 이슈 #111 개선:
 * - 기존: size=5 하드코딩 → 데이터 분포 파악에 정보량 부족.
 * - 변경: 샘플 크기 셀렉트(5/10/50/100) 추가, 컬럼 헤더에 dataType 보조 표시.
 *
 * 컬럼 통계(distinct/null 비율 등)는 별도 `/datasets/:id/stats` 엔드포인트가 있으나,
 * 미리보기 다이얼로그의 가벼운 컨텍스트와 부합하지 않아 본 다이얼로그에선 제공하지 않는다.
 * 자세한 통계는 "상세 보기" → 데이터 탭의 ColumnStats 화면에서 확인할 수 있다.
 */
export function DatasetPreviewSheet({ datasetId, datasetName, open, onOpenChange }: DatasetPreviewSheetProps) {
  const navigate = useNavigate();
  /** 사용자가 선택한 샘플 크기 (#111) — 기본 5 유지하여 기존 UX 변경 최소화 */
  const [sampleSize, setSampleSize] = useState<number>(5);

  const { data: previewData, isLoading } = useQuery({
    queryKey: ['datasetPreview', datasetId, sampleSize],
    queryFn: () => datasetsApi.getDatasetData(datasetId, { size: sampleSize, page: 0, includeTotalCount: true }).then(r => r.data),
    enabled: open && !!datasetId,
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl max-h-[80vh] overflow-auto">
        <DialogHeader>
          <DialogTitle>미리보기: {datasetName}</DialogTitle>
          <DialogDescription className="sr-only">데이터셋의 데이터를 미리봅니다.</DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: sampleSize > 5 ? 5 : sampleSize }).map((_, i) => (
              <Skeleton key={i} className="h-8 w-full" />
            ))}
          </div>
        ) : previewData && previewData.columns.length > 0 ? (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-4">
              <div className="text-sm text-muted-foreground">
                총 {previewData.totalElements >= 0 ? previewData.totalElements.toLocaleString() : '?'}행 중 상위 {previewData.rows.length.toLocaleString()}행
              </div>
              {/* 샘플 크기 셀렉트 (#111) — 5/10/50/100 행 선택 */}
              <div className="flex items-center gap-2 text-sm">
                <label htmlFor="preview-sample-size" className="text-muted-foreground">샘플 크기</label>
                <Select
                  value={String(sampleSize)}
                  onValueChange={(v) => setSampleSize(Number(v))}
                >
                  <SelectTrigger
                    id="preview-sample-size"
                    aria-label="샘플 크기"
                    className="w-[100px]"
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {SAMPLE_SIZES.map((s) => (
                      <SelectItem key={s} value={String(s)}>{s}행</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="overflow-x-auto rounded-md border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b bg-muted/50">
                    {previewData.columns.map((col) => (
                      <th key={col.columnName} className="px-3 py-2 text-left font-medium text-muted-foreground whitespace-nowrap">
                        {/* 컬럼명 + 타입 보조 표시 (#111) — 사용자가 데이터 형태를 빠르게 파악 */}
                        <div className="flex flex-col">
                          <span>{col.displayName || col.columnName}</span>
                          <span className="text-[10px] uppercase tracking-wide text-muted-foreground/70 font-normal">
                            {col.dataType}
                          </span>
                        </div>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {previewData.rows.map((row, i) => (
                    <tr key={i} className="border-b last:border-0 hover:bg-muted/30">
                      {previewData.columns.map((col) => (
                        <td key={col.columnName} className="px-3 py-2 whitespace-nowrap max-w-[200px] truncate">
                          {row[col.columnName] == null ? (
                            // null 값은 시각적으로 구분되는 dash로 표시 (빈 셀과 달리 null임을 명시)
                            <span className="text-muted-foreground/50 italic text-xs select-none">-</span>
                          ) : String(row[col.columnName])}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex justify-end">
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  onOpenChange(false);
                  navigate(`/data/datasets/${datasetId}`);
                }}
              >
                상세 보기
              </Button>
            </div>
          </div>
        ) : (
          <div className="text-center text-muted-foreground py-8">
            데이터를 불러올 수 없습니다.
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
