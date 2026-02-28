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
import { useColumnStatsMap } from '../../../hooks/useColumnStatsMap';
import type { DatasetColumnResponse,DatasetDetailResponse } from '../../../types/dataset';
import { ColumnExpandedStats,NullProgressBar } from '../components/ColumnStats';
import { DataTypeBadge } from '../components/DataTypeBadge';
import { DescriptionCell } from '../components/DescriptionCell';
import { useColumnManager } from '../hooks/useColumnManager';

const ColumnDialog = lazy(() =>
  import('../components/ColumnDialog').then((m) => ({ default: m.ColumnDialog }))
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

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">필드 목록 ({dataset.columns.length}개)</h2>
        <Button onClick={() => setAddColumnOpen(true)}>필드 추가</Button>
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

              return (
                <React.Fragment key={col.id}>
                  <TableRow className={isExpanded ? 'bg-muted/20' : undefined}>
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
                            className="inline-flex items-center gap-0.5 text-amber-600"
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
                        <NullProgressBar percent={stats.nullPercent} />
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
                      <div className="flex items-center gap-0.5">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          onClick={() => handlers.moveColumn(index, 'up')}
                          disabled={index === 0 || isReorderPending}
                        >
                          <ChevronUp className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          onClick={() => handlers.moveColumn(index, 'down')}
                          disabled={index === localColumns.length - 1 || isReorderPending}
                        >
                          <ChevronDown className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => handlers.editClick(col)}
                        >
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => handlers.deleteClick(col)}
                          disabled={localColumns.length <= 1}
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
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
