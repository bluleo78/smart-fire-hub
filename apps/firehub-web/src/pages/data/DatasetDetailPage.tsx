import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import {
  useDataset,
  useCategories,
  useUpdateDataset,
  useAddColumn,
  useDeleteColumn,
  useDatasetData,
  useImports,
  useUploadFile,
} from '../../hooks/queries/useDatasets';
import { dataImportsApi } from '../../api/dataImports';
import { datasetsApi } from '../../api/datasets';
import { updateDatasetSchema, addColumnSchema, updateColumnSchema } from '../../lib/validations/dataset';
import type { UpdateDatasetFormData, AddColumnFormData, UpdateColumnFormData } from '../../lib/validations/dataset';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Badge } from '../../components/ui/badge';
import { Skeleton } from '../../components/ui/skeleton';
import { Card } from '../../components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { Checkbox } from '../../components/ui/checkbox';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '../../components/ui/dialog';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../components/ui/alert-dialog';
import { ColumnTypeSelect } from '../../components/dataset/ColumnTypeSelect';
import { Plus, Edit, ChevronLeft, ChevronRight, Download, Upload, Search, Pencil, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../types/auth';
import type { DatasetColumnResponse } from '../../types/dataset';
import axios from 'axios';
import { FileUploadZone } from '../../components/dataset/FileUploadZone';
import type { ImportResponse } from '../../types/dataImport';

export function DatasetDetailPage() {
  const { id } = useParams();
  const datasetId = Number(id);
  const queryClient = useQueryClient();

  const [isEditing, setIsEditing] = useState(false);
  const [addColumnOpen, setAddColumnOpen] = useState(false);
  const [editColumnOpen, setEditColumnOpen] = useState(false);
  const [deleteColumnOpen, setDeleteColumnOpen] = useState(false);
  const [selectedColumn, setSelectedColumn] = useState<DatasetColumnResponse | null>(null);
  const [dataPage, setDataPage] = useState(0);
  const dataSize = 20;
  const [importDialogOpen, setImportDialogOpen] = useState(false);
  const [dataSearch, setDataSearch] = useState('');

  const { data: dataset, isLoading } = useDataset(datasetId);
  const { data: categoriesData } = useCategories();
  const updateDataset = useUpdateDataset(datasetId);
  const addColumn = useAddColumn(datasetId);
  const deleteColumn = useDeleteColumn(datasetId);
  const [debouncedSearch, setDebouncedSearch] = useState('');

  const { data: dataQueryResult } = useDatasetData(datasetId, { search: debouncedSearch || undefined, page: dataPage, size: dataSize });
  const { data: imports } = useImports(datasetId);
  const uploadFile = useUploadFile(datasetId);

  const categories = categoriesData || [];

  const infoForm = useForm<UpdateDatasetFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(updateDatasetSchema) as any,
    values: dataset
      ? {
          name: dataset.name,
          description: dataset.description || '',
          categoryId: dataset.category?.id,
        }
      : undefined,
  });

  const columnForm = useForm<AddColumnFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(addColumnSchema) as any,
    defaultValues: {
      columnName: '',
      displayName: '',
      dataType: 'TEXT',
      maxLength: undefined,
      isNullable: true,
      isIndexed: false,
      description: '',
    },
  });

  const editForm = useForm<UpdateColumnFormData>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(updateColumnSchema) as any,
    defaultValues: {
      columnName: '',
      displayName: '',
      dataType: 'TEXT',
      maxLength: undefined,
      isNullable: true,
      isIndexed: false,
      description: '',
    },
  });

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(dataSearch);
      setDataPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [dataSearch]);

  useEffect(() => {
    if (selectedColumn && editColumnOpen) {
      editForm.reset({
        columnName: selectedColumn.columnName,
        displayName: selectedColumn.displayName || '',
        dataType: selectedColumn.dataType as UpdateColumnFormData['dataType'],
        maxLength: selectedColumn.maxLength,
        isNullable: selectedColumn.isNullable,
        isIndexed: selectedColumn.isIndexed,
        description: selectedColumn.description || '',
      });
    }
  }, [selectedColumn, editColumnOpen, editForm]);

  const onInfoSubmit = async (data: UpdateDatasetFormData) => {
    try {
      await updateDataset.mutateAsync({
        name: data.name,
        description: data.description || undefined,
        categoryId: data.categoryId || undefined,
      });
      toast.success('데이터셋 정보가 업데이트되었습니다.');
      setIsEditing(false);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '업데이트에 실패했습니다.');
      } else {
        toast.error('업데이트에 실패했습니다.');
      }
    }
  };

  const onAddColumnSubmit = async (data: AddColumnFormData) => {
    try {
      await addColumn.mutateAsync({
        columnName: data.columnName,
        displayName: data.displayName || undefined,
        dataType: data.dataType,
        maxLength: data.maxLength || undefined,
        isNullable: data.isNullable,
        isIndexed: data.isIndexed,
        description: data.description || undefined,
      });
      toast.success('필드가 추가되었습니다.');
      setAddColumnOpen(false);
      columnForm.reset();
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '필드 추가에 실패했습니다.');
      } else {
        toast.error('필드 추가에 실패했습니다.');
      }
    }
  };

  const onEditColumnSubmit = async (data: UpdateColumnFormData) => {
    if (!selectedColumn) return;

    try {
      await datasetsApi.updateColumn(datasetId, selectedColumn.id, {
        columnName: data.columnName,
        displayName: data.displayName || undefined,
        dataType: data.dataType,
        maxLength: data.maxLength,
        isNullable: data.isNullable,
        isIndexed: data.isIndexed,
        description: data.description || undefined,
      });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
      toast.success('필드가 수정되었습니다.');
      setEditColumnOpen(false);
      setSelectedColumn(null);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '필드 수정에 실패했습니다.');
      } else {
        toast.error('필드 수정에 실패했습니다.');
      }
    }
  };

  const handleDeleteColumn = async () => {
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
  };

  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleString('ko-KR');
  };

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  const handleFileUpload = async (file: File) => {
    try {
      await uploadFile.mutateAsync(file);
      toast.success('파일 업로드가 시작되었습니다.');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '업로드에 실패했습니다.');
      } else {
        toast.error('업로드에 실패했습니다.');
      }
    }
  };

  const handleExport = async () => {
    try {
      const response = await dataImportsApi.exportCsv(datasetId);
      const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8' });
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${dataset?.tableName || 'export'}_export.csv`;
      a.click();
      window.URL.revokeObjectURL(url);
      toast.success('CSV 파일이 다운로드되었습니다.');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '내보내기에 실패했습니다.');
      } else {
        toast.error('내보내기에 실패했습니다.');
      }
    }
  };

  const getStatusBadge = (status: ImportResponse['status']) => {
    const variantMap: Record<ImportResponse['status'], 'outline' | 'secondary' | 'default' | 'destructive'> = {
      PENDING: 'outline',
      PROCESSING: 'secondary',
      COMPLETED: 'default',
      FAILED: 'destructive',
    };
    const labelMap: Record<ImportResponse['status'], string> = {
      PENDING: '대기중',
      PROCESSING: '처리중',
      COMPLETED: '완료',
      FAILED: '실패',
    };
    return <Badge variant={variantMap[status]}>{labelMap[status]}</Badge>;
  };

  const getDataTypeBadge = (dataType: string, maxLength?: number | null) => {
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
  };

  if (isLoading || !dataset) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  const totalDataPages = dataQueryResult?.totalPages || 0;

  const rows = dataQueryResult?.rows || [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{dataset.name}</h1>
        <p className="text-sm text-muted-foreground mt-1">
          테이블: <span className="font-mono">{dataset.tableName}</span>
        </p>
      </div>

      <Tabs defaultValue="info">
        <TabsList>
          <TabsTrigger value="info">정보</TabsTrigger>
          <TabsTrigger value="columns">필드</TabsTrigger>
          <TabsTrigger value="data">데이터</TabsTrigger>
          <TabsTrigger value="history">이력</TabsTrigger>
        </TabsList>

        <TabsContent value="info" className="space-y-4">
          <Card className="p-6">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-semibold">기본 정보</h2>
              {!isEditing && (
                <Button variant="outline" size="sm" onClick={() => setIsEditing(true)}>
                  <Edit className="mr-2 h-4 w-4" />
                  수정
                </Button>
              )}
            </div>

            {isEditing ? (
              <form onSubmit={infoForm.handleSubmit(onInfoSubmit)} className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="name">데이터셋 이름 *</Label>
                  <Input id="name" {...infoForm.register('name')} />
                  {infoForm.formState.errors.name && (
                    <p className="text-sm text-destructive">
                      {infoForm.formState.errors.name.message}
                    </p>
                  )}
                </div>

                <div className="space-y-2">
                  <Label htmlFor="description">설명</Label>
                  <Input id="description" {...infoForm.register('description')} />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="categoryId">카테고리</Label>
                  <Select
                    value={infoForm.watch('categoryId')?.toString() || '__none__'}
                    onValueChange={(value) => {
                      infoForm.setValue('categoryId', value === '__none__' ? undefined : Number(value));
                    }}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="카테고리 선택" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="__none__">없음</SelectItem>
                      {categories.map((cat) => (
                        <SelectItem key={cat.id} value={cat.id.toString()}>
                          {cat.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                <div className="flex gap-2">
                  <Button type="submit" disabled={infoForm.formState.isSubmitting}>
                    저장
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => {
                      setIsEditing(false);
                      infoForm.reset();
                    }}
                  >
                    취소
                  </Button>
                </div>
              </form>
            ) : (
              <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
                <div>
                  <p className="text-sm text-muted-foreground">설명</p>
                  <p className="text-sm">{dataset.description || '-'}</p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">카테고리</p>
                  <p className="text-sm">{dataset.category?.name || '-'}</p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">유형</p>
                  <Badge variant={dataset.datasetType === 'SOURCE' ? 'default' : 'secondary'}>
                    {dataset.datasetType === 'SOURCE' ? '원본' : '파생'}
                  </Badge>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">생성자</p>
                  <p className="text-sm">{dataset.createdBy}</p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">행 수</p>
                  <p className="text-sm">{dataset.rowCount.toLocaleString()}</p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">생성일</p>
                  <p className="text-sm">{formatDate(dataset.createdAt)}</p>
                </div>
                <div>
                  <p className="text-sm text-muted-foreground">수정일</p>
                  <p className="text-sm">{formatDate(dataset.updatedAt)}</p>
                </div>
                {dataset.updatedBy && (
                  <div>
                    <p className="text-sm text-muted-foreground">수정자</p>
                    <p className="text-sm">{dataset.updatedBy}</p>
                  </div>
                )}
              </div>
            )}
          </Card>
        </TabsContent>

        <TabsContent value="columns" className="space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold">필드 목록 ({dataset.columns.length}개)</h2>
            <Dialog open={addColumnOpen} onOpenChange={setAddColumnOpen}>
              <DialogTrigger asChild>
                <Button>
                  <Plus className="mr-2 h-4 w-4" />
                  필드 추가
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>필드 추가</DialogTitle>
                </DialogHeader>
                <form onSubmit={columnForm.handleSubmit(onAddColumnSubmit)} className="space-y-4">
                  <div className="space-y-2">
                    <Label htmlFor="columnName">필드명 *</Label>
                    <Input
                      id="columnName"
                      {...columnForm.register('columnName')}
                      placeholder="예: user_id"
                    />
                    {columnForm.formState.errors.columnName && (
                      <p className="text-sm text-destructive">
                        {columnForm.formState.errors.columnName.message}
                      </p>
                    )}
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="displayName">표시명</Label>
                    <Input
                      id="displayName"
                      {...columnForm.register('displayName')}
                      placeholder="예: 사용자 ID"
                    />
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="dataType">데이터 타입 *</Label>
                    <ColumnTypeSelect
                      value={columnForm.watch('dataType')}
                      onChange={(value) => columnForm.setValue('dataType', value as AddColumnFormData['dataType'])}
                    />
                    {columnForm.formState.errors.dataType && (
                      <p className="text-sm text-destructive">
                        {columnForm.formState.errors.dataType.message}
                      </p>
                    )}
                  </div>

                  {columnForm.watch('dataType') === 'VARCHAR' && (
                    <div className="space-y-2">
                      <Label htmlFor="maxLength">최대 길이 *</Label>
                      <Input
                        id="maxLength"
                        type="number"
                        min={1}
                        max={10000}
                        {...columnForm.register('maxLength', { valueAsNumber: true })}
                        placeholder="예: 255"
                      />
                      {columnForm.formState.errors.maxLength && (
                        <p className="text-sm text-destructive">
                          {columnForm.formState.errors.maxLength.message}
                        </p>
                      )}
                    </div>
                  )}

                  <div className="flex items-center space-x-2">
                    <Checkbox
                      id="isNullable"
                      checked={columnForm.watch('isNullable')}
                      onCheckedChange={(checked) =>
                        columnForm.setValue('isNullable', checked as boolean)
                      }
                    />
                    <Label htmlFor="isNullable" className="text-sm font-normal cursor-pointer">
                      NULL 허용
                    </Label>
                  </div>

                  <div className="flex items-center space-x-2">
                    <Checkbox
                      id="isIndexed"
                      checked={columnForm.watch('isIndexed')}
                      onCheckedChange={(checked) =>
                        columnForm.setValue('isIndexed', checked as boolean)
                      }
                    />
                    <Label htmlFor="isIndexed" className="text-sm font-normal cursor-pointer">
                      인덱스 생성
                    </Label>
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="description">설명</Label>
                    <Input
                      id="description"
                      {...columnForm.register('description')}
                      placeholder="필드 설명"
                    />
                  </div>

                  <Button type="submit" className="w-full" disabled={columnForm.formState.isSubmitting}>
                    {columnForm.formState.isSubmitting ? '추가 중...' : '추가'}
                  </Button>
                </form>
              </DialogContent>
            </Dialog>
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
                {dataset.columns.map((col) => (
                  <TableRow key={col.id}>
                    <TableCell>{col.columnOrder}</TableCell>
                    <TableCell className="font-mono text-sm">{col.columnName}</TableCell>
                    <TableCell>{col.displayName || '-'}</TableCell>
                    <TableCell>{getDataTypeBadge(col.dataType, col.maxLength)}</TableCell>
                    <TableCell>{col.isNullable ? '허용' : '불허'}</TableCell>
                    <TableCell>{col.isIndexed ? '예' : '아니오'}</TableCell>
                    <TableCell className="max-w-xs truncate">{col.description || '-'}</TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1">
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => {
                            setSelectedColumn(col);
                            setEditColumnOpen(true);
                          }}
                        >
                          <Pencil className="h-4 w-4" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => {
                            setSelectedColumn(col);
                            setDeleteColumnOpen(true);
                          }}
                          disabled={dataset.columns.length <= 1}
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
        </TabsContent>

        <TabsContent value="data" className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="relative flex-1 max-w-sm">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                type="text"
                placeholder="데이터 검색..."
                value={dataSearch}
                onChange={(e) => setDataSearch(e.target.value)}
                maxLength={200}
                className="pl-9"
              />
            </div>
            <Button variant="outline" onClick={() => setImportDialogOpen(true)}>
              <Upload className="mr-2 h-4 w-4" />
              임포트
            </Button>
            <Button variant="outline" onClick={handleExport}>
              <Download className="mr-2 h-4 w-4" />
              CSV 내보내기
            </Button>
          </div>

          <h2 className="text-lg font-semibold">
            데이터 ({dataQueryResult?.totalElements.toLocaleString() || 0}행)
          </h2>

          {dataQueryResult && rows.length > 0 ? (
            <>
              <div className="rounded-md border overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      {dataQueryResult.columns.map((col) => (
                        <TableHead key={col.id}>
                          {col.displayName || col.columnName}
                        </TableHead>
                      ))}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((row, idx) => (
                      <TableRow key={idx}>
                        {dataQueryResult.columns.map((col) => (
                          <TableCell key={col.id} className="max-w-xs truncate">
                            {row[col.columnName] !== null && row[col.columnName] !== undefined
                              ? String(row[col.columnName])
                              : '-'}
                          </TableCell>
                        ))}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              {totalDataPages > 1 && (
                <div className="flex items-center justify-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setDataPage((p) => Math.max(0, p - 1))}
                    disabled={dataPage === 0}
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </Button>
                  <span className="text-sm">
                    {dataPage + 1} / {totalDataPages}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setDataPage((p) => Math.min(totalDataPages - 1, p + 1))}
                    disabled={dataPage >= totalDataPages - 1}
                  >
                    <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              )}
            </>
          ) : (
            <Card className="p-8">
              <p className="text-center text-muted-foreground">
                {dataSearch ? '검색 결과가 없습니다.' : '데이터가 없습니다.'}
              </p>
            </Card>
          )}
        </TabsContent>

        <TabsContent value="history" className="space-y-4">
          <h2 className="text-lg font-semibold">변경 이력</h2>
          <div className="space-y-6">
            {imports && imports.length > 0 ? (
              imports
                .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
                .map((imp) => (
                  <div key={`import-${imp.id}`} className="flex gap-4">
                    <div className="flex flex-col items-center">
                      <div className="h-2.5 w-2.5 rounded-full bg-primary mt-1.5" />
                      <div className="flex-1 w-px bg-border" />
                    </div>
                    <div className="flex-1 pb-6">
                      <p className="text-sm font-medium">
                        {imp.importedBy}님이 데이터를 임포트했습니다
                      </p>
                      <p className="text-xs text-muted-foreground mt-0.5">
                        {formatDate(imp.createdAt)}
                      </p>
                      <div className="mt-2 rounded-md border p-3 text-sm space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="text-muted-foreground">파일:</span>
                          <span>{imp.fileName} ({imp.fileSize != null ? formatFileSize(imp.fileSize) : '-'})</span>
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-muted-foreground">상태:</span>
                          {getStatusBadge(imp.status)}
                        </div>
                        {imp.totalRows !== null && (
                          <div className="flex items-center gap-2">
                            <span className="text-muted-foreground">결과:</span>
                            <span>
                              {imp.successRows?.toLocaleString() || 0}행 성공
                              {imp.errorRows ? ` / ${imp.errorRows.toLocaleString()}행 실패` : ''}
                              {' / '}총 {imp.totalRows.toLocaleString()}행
                            </span>
                          </div>
                        )}
                        {imp.errorDetails && Object.keys(imp.errorDetails).length > 0 && (
                          <details className="mt-2">
                            <summary className="text-xs text-muted-foreground cursor-pointer hover:text-foreground">
                              오류 상세 보기
                            </summary>
                            <pre className="mt-1 text-xs bg-muted/50 rounded p-2 overflow-x-auto whitespace-pre-wrap">
                              {JSON.stringify(imp.errorDetails, null, 2)}
                            </pre>
                          </details>
                        )}
                      </div>
                    </div>
                  </div>
                ))
            ) : null}

            {/* Dataset creation event */}
            <div className="flex gap-4">
              <div className="flex flex-col items-center">
                <div className="h-2.5 w-2.5 rounded-full bg-muted-foreground mt-1.5" />
              </div>
              <div className="flex-1">
                <p className="text-sm font-medium">
                  {dataset.createdBy}님이 데이터셋을 생성했습니다
                </p>
                <p className="text-xs text-muted-foreground mt-0.5">
                  {formatDate(dataset.createdAt)}
                </p>
              </div>
            </div>
          </div>
        </TabsContent>
      </Tabs>

      {/* Edit Column Dialog */}
      <Dialog open={editColumnOpen} onOpenChange={setEditColumnOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>필드 수정</DialogTitle>
          </DialogHeader>
          <form onSubmit={editForm.handleSubmit(onEditColumnSubmit)} className="space-y-4">
            {dataset && dataset.rowCount > 0 && (
              <div className="rounded-md bg-amber-50 border border-amber-200 p-3">
                <p className="text-sm text-amber-800">
                  데이터가 있는 경우 필드명, 데이터 타입, 길이, NULL 허용 여부는 변경할 수 없습니다.
                </p>
              </div>
            )}

            <div className="space-y-2">
              <Label htmlFor="edit-columnName">필드명 *</Label>
              <Input
                id="edit-columnName"
                {...editForm.register('columnName')}
                placeholder="예: user_id"
                disabled={dataset && dataset.rowCount > 0}
              />
              {editForm.formState.errors.columnName && (
                <p className="text-sm text-destructive">
                  {editForm.formState.errors.columnName.message}
                </p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="edit-displayName">표시명</Label>
              <Input
                id="edit-displayName"
                {...editForm.register('displayName')}
                placeholder="예: 사용자 ID"
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="edit-dataType">데이터 타입 *</Label>
              <ColumnTypeSelect
                value={editForm.watch('dataType')}
                onChange={(value) => editForm.setValue('dataType', value as UpdateColumnFormData['dataType'])}
                disabled={dataset && dataset.rowCount > 0}
              />
              {editForm.formState.errors.dataType && (
                <p className="text-sm text-destructive">
                  {editForm.formState.errors.dataType.message}
                </p>
              )}
            </div>

            {editForm.watch('dataType') === 'VARCHAR' && (
              <div className="space-y-2">
                <Label htmlFor="edit-maxLength">최대 길이 *</Label>
                <Input
                  id="edit-maxLength"
                  type="number"
                  min={1}
                  max={10000}
                  {...editForm.register('maxLength', { valueAsNumber: true })}
                  placeholder="예: 255"
                  disabled={dataset && dataset.rowCount > 0}
                />
                {editForm.formState.errors.maxLength && (
                  <p className="text-sm text-destructive">
                    {editForm.formState.errors.maxLength.message}
                  </p>
                )}
              </div>
            )}

            <div className="flex items-center space-x-2">
              <Checkbox
                id="edit-isNullable"
                checked={editForm.watch('isNullable')}
                onCheckedChange={(checked) =>
                  editForm.setValue('isNullable', checked as boolean)
                }
                disabled={dataset && dataset.rowCount > 0}
              />
              <Label htmlFor="edit-isNullable" className="text-sm font-normal cursor-pointer">
                NULL 허용
              </Label>
            </div>

            <div className="flex items-center space-x-2">
              <Checkbox
                id="edit-isIndexed"
                checked={editForm.watch('isIndexed')}
                onCheckedChange={(checked) =>
                  editForm.setValue('isIndexed', checked as boolean)
                }
              />
              <Label htmlFor="edit-isIndexed" className="text-sm font-normal cursor-pointer">
                인덱스 생성
              </Label>
            </div>

            <div className="space-y-2">
              <Label htmlFor="edit-description">설명</Label>
              <Input
                id="edit-description"
                {...editForm.register('description')}
                placeholder="필드 설명"
              />
            </div>

            <div className="flex gap-2">
              <Button type="submit" className="flex-1" disabled={editForm.formState.isSubmitting}>
                {editForm.formState.isSubmitting ? '수정 중...' : '수정'}
              </Button>
              <Button
                type="button"
                variant="outline"
                onClick={() => setEditColumnOpen(false)}
              >
                취소
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

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
            <AlertDialogAction
              onClick={handleDeleteColumn}
              variant="destructive"
            >
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Import Dialog */}
      <Dialog open={importDialogOpen} onOpenChange={setImportDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>파일 임포트</DialogTitle>
          </DialogHeader>
          <FileUploadZone onFileSelect={handleFileUpload} disabled={uploadFile.isPending} />
          {uploadFile.isPending && <p className="text-sm text-muted-foreground mt-2">업로드 중...</p>}
        </DialogContent>
      </Dialog>
    </div>
  );
}
