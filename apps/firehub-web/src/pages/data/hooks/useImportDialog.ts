import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { useEffect,useMemo,useState } from 'react';
import { toast } from 'sonner';

import { useImports,usePreviewImport, useUploadFile,useValidateImport } from '../../../hooks/queries/useDatasets';
import type { ImportProgress } from '../../../hooks/queries/useImportProgress';
import { useImportProgress } from '../../../hooks/queries/useImportProgress';
import { handleApiError } from '../../../lib/api-error';
import { parseErrorDetails } from '../../../lib/errorDetails';
import type {
  ColumnMappingEntry,
  ImportMode,
  ImportPreviewResponse,
  ImportValidateResponse,
  ValidationErrorDetail,
} from '../../../types/dataImport';
import type { DatasetColumnResponse } from '../../../types/dataset';

interface UseImportDialogOptions {
  datasetId: number;
  datasetColumns: DatasetColumnResponse[];
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

interface UseImportDialogReturn {
  step: 1 | 2 | 3;
  selectedFile: File | null;
  previewData: ImportPreviewResponse | null;
  mappings: ColumnMappingEntry[];
  validationResult: ImportValidateResponse | null;
  showAllErrors: boolean;
  setShowAllErrors: (show: boolean) => void;
  importMode: ImportMode;
  setImportMode: (mode: ImportMode) => void;
  jobId: string | null;
  importProgress: ImportProgress | null;
  failedErrors: ValidationErrorDetail[] | null;
  handlers: {
    fileSelect: (file: File) => void;
    mappingChange: (fileColumn: string, datasetColumn: string | null) => void;
    validate: () => void;
    import: () => void;
    close: () => void;
  };
  derived: {
    requiredColumns: DatasetColumnResponse[];
    unmappedRequired: DatasetColumnResponse[];
    hasUnmappedRequired: boolean;
    hasPrimaryKey: boolean;
    getAvailableDatasetColumns: (fileColumn: string) => DatasetColumnResponse[];
    displayedErrors: ValidationErrorDetail[];
  };
  status: {
    isUploading: boolean;
    isValidating: boolean;
    isPreviewing: boolean;
  };
}

export function useImportDialog({
  datasetId,
  datasetColumns,
  open,
  onOpenChange,
}: UseImportDialogOptions): UseImportDialogReturn {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [previewData, setPreviewData] = useState<ImportPreviewResponse | null>(null);
  const [mappings, setMappings] = useState<ColumnMappingEntry[]>([]);
  const [validationResult, setValidationResult] = useState<ImportValidateResponse | null>(null);
  const [showAllErrors, setShowAllErrors] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [importMode, setImportMode] = useState<ImportMode>('APPEND');

  const importProgress = useImportProgress(jobId);
  const queryClient = useQueryClient();

  const previewImport = usePreviewImport(datasetId);
  const validateImport = useValidateImport(datasetId);
  const uploadFile = useUploadFile(datasetId);
  // 임포트 진행 실패 시 오류 상세(errorDetails)는 SSE 이벤트에 실리지 않고 audit_log를 통해
  // 이력(ImportResponse)에만 저장된다 — 이력 목록을 조회해 FAILED 블록에서 재사용한다.
  const { data: imports } = useImports(datasetId);

  // Invalidate queries when import completes or fails (실패 이력도 errorDetails 조회를 위해 갱신 필요)
  useEffect(() => {
    if (importProgress?.stage === 'COMPLETED' || importProgress?.stage === 'FAILED') {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'imports'] });
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId] });
    }
  }, [importProgress?.stage, queryClient, datasetId]);

  // 현재 열려 있는 다이얼로그의 선택 파일과 이름이 같은 가장 최근 FAILED 이력에서
  // 검증 오류 상세를 찾는다. jobId는 ImportResponse에 없어 직접 매칭할 수 없기 때문에
  // fileName + 최신순 정렬을 근사 매칭 기준으로 사용한다.
  const failedErrors = useMemo(() => {
    if (importProgress?.stage !== 'FAILED' || !imports) return null;
    const candidates = imports
      .filter((i) => i.status === 'FAILED' && (!selectedFile || i.fileName === selectedFile.name))
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    for (const candidate of candidates) {
      const parsed = parseErrorDetails(candidate.errorDetails);
      if (parsed) return parsed;
    }
    return null;
  }, [importProgress?.stage, imports, selectedFile]);

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
      setImportMode('APPEND');
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
      handleApiError(error, '미리보기 로드에 실패했습니다.');
    }
  };

  const handleMappingChange = (fileColumn: string, datasetColumn: string | null) => {
    setMappings((prev) =>
      prev.map((m) => (m.fileColumn === fileColumn ? { ...m, datasetColumn } : m))
    );
    setValidationResult(null);
  };

  const handleValidate = async () => {
    if (!selectedFile) return;
    try {
      const result = await validateImport.mutateAsync({ file: selectedFile, mappings });
      setValidationResult(result);
      // 사전 검증 완료 토스트 — 샘플 검사임을 명시
      if (result.errorRows === 0) {
        toast.success(`샘플 ${result.sampleSize}행 검사 통과`);
      } else {
        toast.warning(`샘플 ${result.sampleSize}행 중 ${result.errorRows}개 오류`);
      }
    } catch (error) {
      handleApiError(error, '검증에 실패했습니다.');
    }
  };

  const handleImport = async () => {
    if (!selectedFile) return;
    try {
      const result = await uploadFile.mutateAsync({ file: selectedFile, mappings, importMode });
      setJobId(result.jobId);
      setStep(3);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 409) {
        toast.error('이미 진행 중인 임포트가 있습니다.');
      } else {
        handleApiError(error, '임포트에 실패했습니다.');
      }
    }
  };

  const handleClose = () => {
    onOpenChange(false);
  };

  // Derived values
  const requiredColumns = datasetColumns.filter((col) => !col.isNullable);
  const unmappedRequired = requiredColumns.filter(
    (col) => !mappings.some((m) => m.datasetColumn === col.columnName)
  );
  const hasUnmappedRequired = unmappedRequired.length > 0;
  const hasPrimaryKey = datasetColumns.some((col) => col.isPrimaryKey);

  const getAvailableDatasetColumns = (currentFileColumn: string): DatasetColumnResponse[] => {
    const currentMapping = mappings.find((m) => m.fileColumn === currentFileColumn);
    const usedColumns = mappings
      .filter((m) => m.fileColumn !== currentFileColumn && m.datasetColumn !== null)
      .map((m) => m.datasetColumn);
    return datasetColumns.filter(
      (col) => !usedColumns.includes(col.columnName) || col.columnName === currentMapping?.datasetColumn
    );
  };

  const displayedErrors = showAllErrors
    ? validationResult?.errors || []
    : (validationResult?.errors || []).slice(0, 100);

  return {
    step,
    selectedFile,
    previewData,
    mappings,
    validationResult,
    showAllErrors,
    setShowAllErrors,
    importMode,
    setImportMode,
    jobId,
    importProgress,
    failedErrors,
    handlers: {
      fileSelect: handleFileSelect,
      mappingChange: handleMappingChange,
      validate: handleValidate,
      import: handleImport,
      close: handleClose,
    },
    derived: {
      requiredColumns,
      unmappedRequired,
      hasUnmappedRequired,
      hasPrimaryKey,
      getAvailableDatasetColumns,
      displayedErrors,
    },
    status: {
      isUploading: uploadFile.isPending,
      isValidating: validateImport.isPending,
      isPreviewing: previewImport.isPending,
    },
  };
}
