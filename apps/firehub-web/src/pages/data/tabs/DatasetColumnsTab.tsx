import {
  ChevronDown,
  ChevronRight,
  ChevronUp,
  KeyRound,
  Pencil,
  Trash2,
} from 'lucide-react';
import React, { lazy, Suspense } from 'react';

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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../../components/ui/table';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../../../components/ui/tooltip';
import { useColumnStatsMap } from '../../../hooks/useColumnStatsMap';
import type { DatasetColumnResponse,DatasetDetailResponse } from '../../../types/dataset';
import { ColumnExpandedStats,NullProgressBar } from '../components/ColumnStats';
import { DataTypeBadge } from '../components/DataTypeBadge';
import { DescriptionCell } from '../components/DescriptionCell';
import { useColumnManager } from '../hooks/useColumnManager';

const ColumnDialog = lazy(() =>
  import('../components/ColumnDialog').then((m) => ({ default: m.ColumnDialog }))
);
// 기본 키 일괄 설정 다이얼로그 (#117) — 복합 PK 변경 시 단일 토글로는 중간 상태가 unique 하지 않을 수 있어 별도 UI 필요.
const PrimaryKeysDialog = lazy(() =>
  import('../components/PrimaryKeysDialog').then((m) => ({ default: m.PrimaryKeysDialog }))
);

interface DatasetColumnsTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

// Total column count for colSpan on expanded row
const TOTAL_COLS = 11;

export const DatasetColumnsTab = React.memo(function DatasetColumnsTab({
  dataset,
  datasetId,
}: DatasetColumnsTabProps) {
  const hasData = dataset.rowCount > 0;
  const statsMap = useColumnStatsMap(datasetId, hasData);
  // 기본 키 일괄 설정 다이얼로그 오픈 상태 (#117)
  const [primaryKeysOpen, setPrimaryKeysOpen] = React.useState(false);

  const {
    localColumns,
    expandedColumnId,
    selectedColumn,
    editingColumnId,
    addColumnOpen,
    setAddColumnOpen,
    editColumnOpen,
    setEditColumnOpen,
    deleteColumnOpen,
    setDeleteColumnOpen,
    setEditingColumnId,
    isReorderPending,
    handlers,
  } = useColumnManager({ dataset, datasetId });

  // (#91) 100% NULL인 컬럼 수를 집계 — 헤더에 "이상 N개" 보조 메트릭으로 노출하고, 행도 시각 강조한다.
  const emptyColumnCount = React.useMemo(() => {
    let count = 0;
    for (const col of localColumns) {
      const stats = statsMap.get(col.columnName);
      if (stats && stats.nullPercent >= 100) count += 1;
    }
    return count;
  }, [localColumns, statsMap]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl leading-7 font-semibold">
          필드 목록 ({dataset.columns.length}개)
          {emptyColumnCount > 0 && (
            <span
              className="ml-2 text-sm font-normal text-destructive"
              data-testid="empty-column-count"
            >
              (이상 {emptyColumnCount}개)
            </span>
          )}
        </h2>
        <div className="flex items-center gap-2">
          {/* 기본 키 일괄 설정 — 복합 PK 변경 진입점 (#117) */}
          <Button variant="outline" onClick={() => setPrimaryKeysOpen(true)}>
            <KeyRound className="mr-1 h-4 w-4" />
            기본 키 설정
          </Button>
          <Button onClick={() => setAddColumnOpen(true)}>필드 추가</Button>
        </div>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-6" />
              <TableHead>순서</TableHead>
              <TableHead>필드명</TableHead>
              <TableHead>표시명</TableHead>
              <TableHead>데이터 타입</TableHead>
              <TableHead>NULL</TableHead>
              <TableHead>인덱스</TableHead>
              <TableHead>설명</TableHead>
              <TableHead>Null %</TableHead>
              <TableHead>Distinct</TableHead>
              <TableHead>작업</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {localColumns.map((col: DatasetColumnResponse, index: number) => {
              const stats = statsMap.get(col.columnName);
              const isExpanded = expandedColumnId === col.id;
              const isEditingDesc = editingColumnId === col.id;

              // (#91) NULL 비율 100% → 행 좌측에 destructive 보더 + 미세한 배경, "비어있음" 배지 노출
              const isAllNull = !!stats && stats.nullPercent >= 100;
              const rowClass = [
                isExpanded ? 'bg-muted/20' : '',
                isAllNull ? 'border-l-4 border-l-destructive bg-destructive/5' : '',
              ]
                .filter(Boolean)
                .join(' ');

              return (
                <React.Fragment key={col.id}>
                  <TableRow
                    className={rowClass || undefined}
                    data-testid={isAllNull ? 'empty-column-row' : undefined}
                  >
                    <TableCell className="w-6 p-1">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-6 w-6"
                        onClick={() => handlers.toggleExpand(col.id)}
                        disabled={!hasData}
                      >
                        <ChevronRight
                          className={`h-3.5 w-3.5 transition-transform ${
                            isExpanded ? 'rotate-90' : ''
                          } ${!hasData ? 'text-muted-foreground/30' : ''}`}
                        />
                      </Button>
                    </TableCell>
                    <TableCell>{index + 1}</TableCell>
                    <TableCell className="font-mono text-sm">
                      <div className="flex items-center gap-1.5">
                        {col.isPrimaryKey && (
                          <span
                            className="inline-flex items-center gap-0.5 text-warning"
                            title="기본 키"
                          >
                            <KeyRound className="h-3 w-3" />
                            <span className="text-xs font-semibold">PK</span>
                          </span>
                        )}
                        {col.columnName}
                      </div>
                    </TableCell>
                    <TableCell>{col.displayName || '-'}</TableCell>
                    <TableCell>
                      <DataTypeBadge dataType={col.dataType} maxLength={col.maxLength} />
                    </TableCell>
                    <TableCell>{col.isNullable ? '허용' : '불허'}</TableCell>
                    <TableCell>{col.isIndexed ? '예' : '아니오'}</TableCell>
                    <TableCell className="max-w-[160px]">
                      <DescriptionCell
                        col={col}
                        datasetId={datasetId}
                        isEditing={isEditingDesc}
                        onStartEdit={() => setEditingColumnId(col.id)}
                        onEndEdit={() => setEditingColumnId(null)}
                      />
                    </TableCell>
                    <TableCell>
                      {stats ? (
                        <div className="flex items-center gap-2">
                          <NullProgressBar percent={stats.nullPercent} />
                          {isAllNull && (
                            <Badge variant="destructive" className="shrink-0 text-[10px]">
                              비어있음
                            </Badge>
                          )}
                        </div>
                      ) : (
                        <span className="text-muted-foreground text-sm">-</span>
                      )}
                    </TableCell>
                    <TableCell>
                      {stats ? (
                        <Badge variant="outline">{stats.distinctCount.toLocaleString()}</Badge>
                      ) : (
                        <span className="text-muted-foreground text-sm">-</span>
                      )}
                    </TableCell>
                    <TableCell>
                      {/* 행 액션 아이콘 — 스크린리더와 마우스 사용자를 위해 aria-label과 시각적 Tooltip을 함께 제공 */}
                      <TooltipProvider>
                        <div className="flex items-center gap-0.5">
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                className="h-7 w-7"
                                aria-label="컬럼 위로 이동"
                                onClick={() => handlers.moveColumn(index, 'up')}
                                disabled={index === 0 || isReorderPending}
                              >
                                <ChevronUp className="h-4 w-4" />
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent>위로 이동</TooltipContent>
                          </Tooltip>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                className="h-7 w-7"
                                aria-label="컬럼 아래로 이동"
                                onClick={() => handlers.moveColumn(index, 'down')}
                                disabled={index === localColumns.length - 1 || isReorderPending}
                              >
                                <ChevronDown className="h-4 w-4" />
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent>아래로 이동</TooltipContent>
                          </Tooltip>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                aria-label="컬럼 편집"
                                onClick={() => handlers.editClick(col)}
                              >
                                <Pencil className="h-4 w-4" />
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent>편집</TooltipContent>
                          </Tooltip>
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                aria-label="컬럼 삭제"
                                onClick={() => handlers.deleteClick(col)}
                                disabled={localColumns.length <= 1}
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </TooltipTrigger>
                            <TooltipContent>삭제</TooltipContent>
                          </Tooltip>
                        </div>
                      </TooltipProvider>
                    </TableCell>
                  </TableRow>

                  {isExpanded && stats && (
                    <TableRow>
                      <TableCell colSpan={TOTAL_COLS} className="p-0">
                        <ColumnExpandedStats stats={stats} dataType={col.dataType} />
                      </TableCell>
                    </TableRow>
                  )}
                </React.Fragment>
              );
            })}
          </TableBody>
        </Table>
      </div>

      <Suspense fallback={null}>
        <ColumnDialog
          mode="add"
          datasetId={datasetId}
          open={addColumnOpen}
          onOpenChange={setAddColumnOpen}
        />
        <ColumnDialog
          mode="edit"
          datasetId={datasetId}
          column={selectedColumn}
          open={editColumnOpen}
          onOpenChange={setEditColumnOpen}
          hasData={dataset.rowCount > 0}
        />
        <PrimaryKeysDialog
          datasetId={datasetId}
          columns={localColumns}
          hasData={hasData}
          open={primaryKeysOpen}
          onOpenChange={setPrimaryKeysOpen}
        />
      </Suspense>

      <AlertDialog open={deleteColumnOpen} onOpenChange={setDeleteColumnOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>필드 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              '{selectedColumn?.columnName}' 필드를 삭제하시겠습니까? 이 작업은 되돌릴 수
              없습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handlers.deleteColumn} variant="destructive">
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
});
