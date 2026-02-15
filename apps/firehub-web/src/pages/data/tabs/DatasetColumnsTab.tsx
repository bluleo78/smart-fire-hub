import React, { useCallback, lazy, Suspense } from 'react';
import { useDeleteColumn, useReorderColumns } from '../../../hooks/queries/useDatasets';
import type { DatasetDetailResponse, DatasetColumnResponse } from '../../../types/dataset';
import { Button } from '../../../components/ui/button';
import { Badge } from '../../../components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../../components/ui/table';
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
import { ChevronUp, ChevronDown, Pencil, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../../types/auth';
import axios from 'axios';

const EditColumnDialog = lazy(() =>
  import('../components/EditColumnDialog').then((m) => ({ default: m.EditColumnDialog }))
);
const AddColumnDialog = lazy(() =>
  import('../components/AddColumnDialog').then((m) => ({ default: m.AddColumnDialog }))
);

interface DatasetColumnsTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

function getDataTypeBadge(dataType: string, maxLength?: number | null) {
  const colorMap: Record<string, 'default' | 'secondary' | 'outline'> = {
    TEXT: 'default',
    VARCHAR: 'default',
    INTEGER: 'secondary',
    DECIMAL: 'secondary',
    BOOLEAN: 'outline',
    DATE: 'outline',
    TIMESTAMP: 'outline',
  };
  const displayType = dataType === 'VARCHAR' && maxLength ? `VARCHAR(${maxLength})` : dataType;
  return <Badge variant={colorMap[dataType] || 'default'}>{displayType}</Badge>;
}

export const DatasetColumnsTab = React.memo(function DatasetColumnsTab({
  dataset,
  datasetId,
}: DatasetColumnsTabProps) {
  const [addColumnOpen, setAddColumnOpen] = React.useState(false);
  const [editColumnOpen, setEditColumnOpen] = React.useState(false);
  const [deleteColumnOpen, setDeleteColumnOpen] = React.useState(false);
  const [selectedColumn, setSelectedColumn] = React.useState<DatasetColumnResponse | null>(null);
  const [localColumns, setLocalColumns] = React.useState<DatasetColumnResponse[] | null>(null);

  const deleteColumn = useDeleteColumn(datasetId);
  const reorderColumns = useReorderColumns(datasetId);

  // Use localColumns for optimistic UI, fall back to dataset.columns
  const columns = localColumns ?? dataset.columns;

  // Reset local state when dataset changes
  React.useEffect(() => {
    setLocalColumns(null);
  }, [dataset.columns]);

  const handleMoveColumn = useCallback(async (index: number, direction: 'up' | 'down') => {
    const currentColumns = localColumns ?? dataset.columns;
    const newIndex = direction === 'up' ? index - 1 : index + 1;
    if (newIndex < 0 || newIndex >= currentColumns.length) return;

    const reordered = [...currentColumns];
    [reordered[index], reordered[newIndex]] = [reordered[newIndex], reordered[index]];
    setLocalColumns(reordered);

    try {
      await reorderColumns.mutateAsync(reordered.map(c => c.id));
    } catch (error) {
      setLocalColumns(null);
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '필드 순서 변경에 실패했습니다.');
      } else {
        toast.error('필드 순서 변경에 실패했습니다.');
      }
    }
  }, [localColumns, dataset.columns, reorderColumns]);

  const handleDeleteColumn = useCallback(async () => {
    if (!selectedColumn) return;

    try {
      await deleteColumn.mutateAsync(selectedColumn.id);
      toast.success('필드가 삭제되었습니다.');
      setDeleteColumnOpen(false);
      setSelectedColumn(null);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '필드 삭제에 실패했습니다.');
      } else {
        toast.error('필드 삭제에 실패했습니다.');
      }
    }
  }, [selectedColumn, deleteColumn]);

  const handleEditClick = useCallback((col: DatasetColumnResponse) => {
    setSelectedColumn(col);
    setEditColumnOpen(true);
  }, []);

  const handleDeleteClick = useCallback((col: DatasetColumnResponse) => {
    setSelectedColumn(col);
    setDeleteColumnOpen(true);
  }, []);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">필드 목록 ({dataset.columns.length}개)</h2>
        {addColumnOpen && (
          <Suspense fallback={null}>
            <AddColumnDialog
              open={addColumnOpen}
              onOpenChange={setAddColumnOpen}
              datasetId={datasetId}
            />
          </Suspense>
        )}
        {!addColumnOpen && (
          <Button onClick={() => setAddColumnOpen(true)}>필드 추가</Button>
        )}
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>순서</TableHead>
              <TableHead>필드명</TableHead>
              <TableHead>표시명</TableHead>
              <TableHead>데이터 타입</TableHead>
              <TableHead>NULL</TableHead>
              <TableHead>인덱스</TableHead>
              <TableHead>설명</TableHead>
              <TableHead>작업</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {columns.map((col, index) => (
              <TableRow key={col.id}>
                <TableCell>{index + 1}</TableCell>
                <TableCell className="font-mono text-sm">{col.columnName}</TableCell>
                <TableCell>{col.displayName || '-'}</TableCell>
                <TableCell>{getDataTypeBadge(col.dataType, col.maxLength)}</TableCell>
                <TableCell>{col.isNullable ? '허용' : '불허'}</TableCell>
                <TableCell>{col.isIndexed ? '예' : '아니오'}</TableCell>
                <TableCell className="max-w-xs truncate">{col.description || '-'}</TableCell>
                <TableCell>
                  <div className="flex items-center gap-0.5">
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7"
                      onClick={() => handleMoveColumn(index, 'up')}
                      disabled={index === 0 || reorderColumns.isPending}
                    >
                      <ChevronUp className="h-4 w-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7"
                      onClick={() => handleMoveColumn(index, 'down')}
                      disabled={index === columns.length - 1 || reorderColumns.isPending}
                    >
                      <ChevronDown className="h-4 w-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => handleEditClick(col)}
                    >
                      <Pencil className="h-4 w-4" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => handleDeleteClick(col)}
                      disabled={columns.length <= 1}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {/* Edit Column Dialog */}
      {editColumnOpen && (
        <Suspense fallback={null}>
          <EditColumnDialog
            open={editColumnOpen}
            onOpenChange={setEditColumnOpen}
            datasetId={datasetId}
            column={selectedColumn}
            hasData={dataset.rowCount > 0}
          />
        </Suspense>
      )}

      {/* Delete Column Confirmation Dialog */}
      <AlertDialog open={deleteColumnOpen} onOpenChange={setDeleteColumnOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>필드 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              '{selectedColumn?.columnName}' 필드를 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteColumn} variant="destructive">
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
});
