import { useState, useEffect } from 'react';
import { toast } from 'sonner';
import axios from 'axios';
import { usePreviewImport, useValidateImport, useUploadFile } from '../../../hooks/queries/useDatasets';
import { useImportProgress } from '../../../hooks/queries/useImportProgress';
import { useQueryClient } from '@tanstack/react-query';
import type { DatasetColumnResponse } from '../../../types/dataset';
import type { ImportPreviewResponse, ColumnMappingEntry, ImportValidateResponse } from '../../../types/dataImport';
import type { ErrorResponse } from '../../../types/auth';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import { Button } from '../../../components/ui/button';
import { Badge } from '../../../components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../../../components/ui/table';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import { Card } from '../../../components/ui/card';
import { FileUploadZone } from './FileUploadZone';
import { ImportProgressView } from './ImportProgressView';
import { AlertTriangle, CheckCircle2, ArrowRight, Loader2 } from 'lucide-react';

interface ImportMappingDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  datasetColumns: DatasetColumnResponse[];
}

export function ImportMappingDialog({ open, onOpenChange, datasetId, datasetColumns }: ImportMappingDialogProps) {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewData, setPreviewData] = useState<ImportPreviewResponse | null>(null);
  const [mappings, setMappings] = useState<ColumnMappingEntry[]>([]);
  const [validationResult, setValidationResult] = useState<ImportValidateResponse | null>(null);
  const [showAllErrors, setShowAllErrors] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);

  const importProgress = useImportProgress(jobId);
  const queryClient = useQueryClient();

  const previewImport = usePreviewImport(datasetId);
  const validateImport = useValidateImport(datasetId);
  const uploadFile = useUploadFile(datasetId);

  // Invalidate queries when import completes
  useEffect(() => {
    if (importProgress?.stage === 'COMPLETED') {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'imports'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    }
  }, [importProgress?.stage, queryClient, datasetId]);

  // Reset state when dialog closes
  useEffect(() => {
    if (!open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional batch reset on close
      setStep(1);
      setSelectedFile(null);
      setPreviewData(null);
      setMappings([]);
      setValidationResult(null);
      setShowAllErrors(false);
      setJobId(null);
    }
  }, [open]);

  // Initialize mappings from preview data
  useEffect(() => {
    if (previewData) {
      const initialMappings: ColumnMappingEntry[] = previewData.suggestedMappings.map((sm) => ({
        fileColumn: sm.fileColumn,
        datasetColumn: sm.datasetColumn,
      }));
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync derived state from preview
      setMappings(initialMappings);
    }
  }, [previewData]);

  const handleFileSelect = async (file: File) => {
    setSelectedFile(file);
    try {
      const preview = await previewImport.mutateAsync(file);
      setPreviewData(preview);
      setStep(2);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '미리보기 로드에 실패했습니다.');
      } else {
        toast.error('미리보기 로드에 실패했습니다.');
      }
    }
  };

  const handleMappingChange = (fileColumn: string, datasetColumn: string | null) => {
    setMappings((prev) =>
      prev.map((m) => (m.fileColumn === fileColumn ? { ...m, datasetColumn } : m))
    );
    // Clear validation when mappings change
    setValidationResult(null);
  };

  const handleValidate = async () => {
    if (!selectedFile) return;
    try {
      const result = await validateImport.mutateAsync({ file: selectedFile, mappings });
      setValidationResult(result);
      if (result.errorRows === 0) {
        toast.success('검증에 성공했습니다.');
      } else {
        toast.warning(`검증 완료: ${result.errorRows}개의 오류가 발견되었습니다.`);
      }
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '검증에 실패했습니다.');
      } else {
        toast.error('검증에 실패했습니다.');
      }
    }
  };

  const handleImport = async () => {
    if (!selectedFile) return;
    try {
      const result = await uploadFile.mutateAsync({ file: selectedFile, mappings });
      setJobId(result.jobId);
      setStep(3);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 409) {
        toast.error('이미 진행 중인 임포트가 있습니다.');
      } else if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '임포트에 실패했습니다.');
      } else {
        toast.error('임포트에 실패했습니다.');
      }
    }
  };

  const handleClose = () => {
    onOpenChange(false);
  };

  const getMatchTypeBadge = (matchType: string) => {
    const variants: Record<string, { variant: 'default' | 'secondary' | 'destructive' | 'outline'; label: string }> = {
      EXACT: { variant: 'default', label: '정확 일치' },
      CASE_INSENSITIVE: { variant: 'secondary', label: '대소문자' },
      DISPLAY_NAME: { variant: 'secondary', label: '표시명' },
      NORMALIZED: { variant: 'secondary', label: '유사' },
      NONE: { variant: 'destructive', label: '미매핑' },
    };
    const config = variants[matchType] || { variant: 'outline' as const, label: matchType };
    return <Badge variant={config.variant}>{config.label}</Badge>;
  };

  // Check if all required columns are mapped
  const requiredColumns = datasetColumns.filter((col) => !col.isNullable);
  const unmappedRequired = requiredColumns.filter(
    (col) => !mappings.some((m) => m.datasetColumn === col.columnName)
  );
  const hasUnmappedRequired = unmappedRequired.length > 0;

  // Get available dataset columns for each file column
  const getAvailableDatasetColumns = (currentFileColumn: string) => {
    const currentMapping = mappings.find((m) => m.fileColumn === currentFileColumn);
    const usedColumns = mappings
      .filter((m) => m.fileColumn !== currentFileColumn && m.datasetColumn !== null)
      .map((m) => m.datasetColumn);
    return datasetColumns.filter((col) => !usedColumns.includes(col.columnName) || col.columnName === currentMapping?.datasetColumn);
  };

  const displayedErrors = showAllErrors ? validationResult?.errors || [] : (validationResult?.errors || []).slice(0, 100);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto overflow-x-hidden">
        <DialogHeader>
          <DialogTitle>파일 임포트</DialogTitle>
        </DialogHeader>

        {step === 1 && (
          <div className="space-y-4">
            <FileUploadZone onFileSelect={handleFileSelect} disabled={previewImport.isPending} />
            {previewImport.isPending && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                <span>파일 분석 중...</span>
              </div>
            )}
          </div>
        )}

        {step === 2 && previewData && (
          <div className="space-y-6 min-w-0">
            {/* A. Mapping Table */}
            <div className="space-y-2">
              <h3 className="text-sm font-semibold">컬럼 매핑</h3>
              {hasUnmappedRequired && (
                <div className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
                  <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                  <div>
                    <p className="font-medium">필수 필드가 매핑되지 않았습니다:</p>
                    <p className="text-xs mt-1">
                      {unmappedRequired.map((col) => col.displayName || col.columnName).join(', ')}
                    </p>
                  </div>
                </div>
              )}
              <div className="rounded-md border">
                <Table className="table-fixed">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="text-xs px-2 w-[28%]">파일 컬럼</TableHead>
                      <TableHead className="text-xs w-6 px-0"></TableHead>
                      <TableHead className="text-xs px-2">데이터셋 컬럼</TableHead>
                      <TableHead className="text-xs px-2 w-[72px]">매칭</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {previewData.suggestedMappings.map((suggestion) => {
                      const currentMapping = mappings.find((m) => m.fileColumn === suggestion.fileColumn);
                      const availableColumns = getAvailableDatasetColumns(suggestion.fileColumn);
                      return (
                        <TableRow key={suggestion.fileColumn}>
                          <TableCell className="text-xs font-medium px-2 py-1.5 truncate">
                            {suggestion.fileColumn}
                          </TableCell>
                          <TableCell className="px-0 py-1.5">
                            <ArrowRight className="h-3 w-3 text-muted-foreground" />
                          </TableCell>
                          <TableCell className="px-2 py-1.5">
                            <Select
                              value={currentMapping?.datasetColumn || '__none__'}
                              onValueChange={(value) =>
                                handleMappingChange(suggestion.fileColumn, value === '__none__' ? null : value)
                              }
                            >
                              <SelectTrigger className="h-8 text-xs w-full">
                                <SelectValue placeholder="매핑 안 함" />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="__none__">매핑 안 함</SelectItem>
                                {availableColumns.map((col) => (
                                  <SelectItem key={col.id} value={col.columnName}>
                                    {col.displayName || col.columnName}
                                    {!col.isNullable && <span className="text-destructive ml-1">*</span>}
                                  </SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                          </TableCell>
                          <TableCell className="px-2 py-1.5">{getMatchTypeBadge(suggestion.matchType)}</TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </div>
            </div>

            {/* B. Sample Data Preview */}
            <div className="space-y-2">
              <h3 className="text-sm font-semibold">샘플 데이터 (상위 5행)</h3>
              <div className="rounded-md border overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      {previewData.fileHeaders.map((header) => {
                        const mapping = mappings.find((m) => m.fileColumn === header);
                        const displayName = mapping?.datasetColumn
                          ? datasetColumns.find((col) => col.columnName === mapping.datasetColumn)?.displayName ||
                            mapping.datasetColumn
                          : header;
                        return <TableHead key={header} className="text-xs whitespace-nowrap px-2">{displayName}</TableHead>;
                      })}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {previewData.sampleRows.slice(0, 5).map((row, idx) => (
                      <TableRow key={idx}>
                        {previewData.fileHeaders.map((header) => (
                          <TableCell key={header} className="text-xs px-2 max-w-[100px] truncate">
                            {row[header] || '-'}
                          </TableCell>
                        ))}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </div>

            {/* C. Validation Section */}
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-semibold">검증</h3>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleValidate}
                  disabled={validateImport.isPending || hasUnmappedRequired}
                >
                  {validateImport.isPending ? (
                    <>
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                      검증 중...
                    </>
                  ) : (
                    '검증'
                  )}
                </Button>
              </div>

              {validationResult && (
                <Card className="p-4 space-y-3">
                  <div className="flex items-start gap-2">
                    {validationResult.errorRows === 0 ? (
                      <>
                        <CheckCircle2 className="h-5 w-5 text-green-600 flex-shrink-0 mt-0.5" />
                        <div className="text-sm">
                          <p className="font-medium text-green-600">검증 성공</p>
                          <p className="text-muted-foreground">
                            전체 {validationResult.totalRows.toLocaleString()}행 / 유효{' '}
                            {validationResult.validRows.toLocaleString()}행
                          </p>
                        </div>
                      </>
                    ) : (
                      <>
                        <AlertTriangle className="h-5 w-5 text-amber-600 flex-shrink-0 mt-0.5" />
                        <div className="text-sm">
                          <p className="font-medium text-amber-600">검증 오류 발견</p>
                          <p className="text-muted-foreground">
                            전체 {validationResult.totalRows.toLocaleString()}행 / 유효{' '}
                            {validationResult.validRows.toLocaleString()}행 / 오류{' '}
                            <span className="text-destructive font-medium">
                              {validationResult.errorRows.toLocaleString()}행
                            </span>
                          </p>
                        </div>
                      </>
                    )}
                  </div>

                  {validationResult.errors.length > 0 && (
                    <div className="space-y-2">
                      <details className="group" open={validationResult.errorRows <= 10}>
                        <summary className="text-xs font-medium text-muted-foreground cursor-pointer hover:text-foreground">
                          오류 상세 보기 ({validationResult.errors.length}개)
                        </summary>
                        <div className="mt-2 rounded-md border overflow-x-auto">
                          <Table>
                            <TableHeader>
                              <TableRow>
                                <TableHead>행</TableHead>
                                <TableHead>컬럼</TableHead>
                                <TableHead>값</TableHead>
                                <TableHead>오류</TableHead>
                              </TableRow>
                            </TableHeader>
                            <TableBody>
                              {displayedErrors.map((err, idx) => (
                                <TableRow key={idx}>
                                  <TableCell>{err.rowNumber}</TableCell>
                                  <TableCell className="font-medium">{err.columnName}</TableCell>
                                  <TableCell className="max-w-xs truncate">{err.value || '-'}</TableCell>
                                  <TableCell className="text-destructive text-xs">{err.error}</TableCell>
                                </TableRow>
                              ))}
                            </TableBody>
                          </Table>
                        </div>
                        {validationResult.errors.length > 100 && !showAllErrors && (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="w-full mt-2"
                            onClick={() => setShowAllErrors(true)}
                          >
                            전체 오류 보기 ({validationResult.errors.length}개)
                          </Button>
                        )}
                      </details>
                    </div>
                  )}
                </Card>
              )}
            </div>

            {/* D. Action Buttons */}
            <div className="flex justify-end gap-2 pt-4 border-t">
              <Button variant="outline" onClick={() => onOpenChange(false)} disabled={uploadFile.isPending}>
                취소
              </Button>
              <Button
                onClick={handleImport}
                disabled={hasUnmappedRequired || uploadFile.isPending}
              >
                {uploadFile.isPending ? (
                  <>
                    <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    임포트 중...
                  </>
                ) : (
                  '임포트'
                )}
              </Button>
            </div>
          </div>
        )}

        {step === 3 && (
          <ImportProgressView progress={importProgress} onClose={handleClose} />
        )}
      </DialogContent>
    </Dialog>
  );
}
