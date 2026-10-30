import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { useEffect,useMemo,useState } from 'react';
import { toast } from 'sonner';

import { parseUtcDate } from '@/lib/formatters';

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

/**
 * 파일 열 이름이 비었는지 본다(WD-369). 화면 표기용 — BOM(U+FEFF)과 앞뒤 공백을 걷어낸 뒤 비면 빈 이름으로 보인다.
 */
export function isBlankColumnName(name: string | null | undefined): boolean {
  if (!name) return true;
  return name.replace(/^\uFEFF/, '').trim() === '';
}

/**
 * 이름이 빈 열을 화면에 보일 이름(WD-369). 빈 이름 열이 여럿이면 표에서 서로 구분되지 않으므로 파일 속 위치(1부터)를 붙인다.
 */
export function fileColumnLabel(name: string, index: number): string {
  return isBlankColumnName(name) ? `(이름 없음) ${index + 1}번째 열` : name;
}

/**
 * 열마다 매핑할 수 없는 빈 이름 열인지(WD-369). 서버는 행을 「다듬지 않은 열 이름 → 값」으로 읽어, 글자까지 같은 빈 이름 열이 둘 이상이면 값이
 * 서로 덮어써진다(매핑하면 서버가 400 으로 거절한다). 같은 이름의 빈 열이 하나뿐이면 겹치지 않아 매핑할 수 있다.
 */
export function unmappableBlankColumns(fileColumns: string[]): boolean[] {
  const counts = new Map<string, number>();
  for (const c of fileColumns) counts.set(c, (counts.get(c) ?? 0) + 1);
  return fileColumns.map((c) => isBlankColumnName(c) && (counts.get(c) ?? 0) >= 2);
}

/**
 * index 위치의 매핑을 돌려준다(WD-369). 시트를 바꾼 직후처럼 매핑 상태가 아직 이전 미리보기 것이면 같은 위치라도 다른 열이므로, 열 이름이 맞을 때만
 * 돌려준다.
 */
export function mappingAt(
  mappings: ColumnMappingEntry[],
  index: number,
  fileColumn: string | undefined
): ColumnMappingEntry | undefined {
  const m = mappings[index];
  return m && m.fileColumn === fileColumn ? m : undefined;
}

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
    /** 엑셀의 다른 시트를 골라 미리보기를 다시 받는다 (WD-253) */
    sheetChange: (sheetName: string) => void;
    /** index: 파일 열 위치(0부터) — 이름이 같은(빈) 열이 여럿이어도 한 행만 바뀌게 위치로 고른다 (WD-369) */
    mappingChange: (index: number, datasetColumn: string | null) => void;
    validate: () => void;
    import: () => void;
    close: () => void;
  };
  derived: {
    requiredColumns: DatasetColumnResponse[];
    unmappedRequired: DatasetColumnResponse[];
    hasUnmappedRequired: boolean;
    hasAnyMapping: boolean;
    hasPrimaryKey: boolean;
    getAvailableDatasetColumns: (index: number) => DatasetColumnResponse[];
    /** 파일 열 위치별로 매핑할 수 없는 빈 이름 열인지 (WD-369) */
    unmappableColumns: boolean[];
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
  // 진행 상황을 확인할 수 없어 멈춘 경우(WD-203)는 이번 임포트의 실패가 아니므로 예전 실패 이력의 오류를 붙이지 않는다
  const progressUnobservable = importProgress?.unobservable === true;
  const failedErrors = useMemo(() => {
    if (importProgress?.stage !== 'FAILED' || progressUnobservable || !imports) return null;
    const candidates = imports
      .filter((i) => i.status === 'FAILED' && (!selectedFile || i.fileName === selectedFile.name))
      .sort((a, b) => parseUtcDate(b.createdAt).getTime() - parseUtcDate(a.createdAt).getTime());
    for (const candidate of candidates) {
      const parsed = parseErrorDetails(candidate.errorDetails);
      if (parsed) return parsed;
    }
    return null;
  }, [importProgress?.stage, progressUnobservable, imports, selectedFile]);

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
      // 매핑할 수 없는 빈 이름 열은 추천이 있더라도 미매핑으로 시작한다 — 서버가 매핑을 거절하기 때문이다 (WD-369)
      const unmappable = unmappableBlankColumns(previewData.suggestedMappings.map((sm) => sm.fileColumn));
      const initialMappings: ColumnMappingEntry[] = previewData.suggestedMappings.map((sm, i) => ({
        fileColumn: sm.fileColumn,
        datasetColumn: unmappable[i] ? null : sm.datasetColumn,
      }));
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync derived state from preview
      setMappings(initialMappings);
    }
  }, [previewData]);

  /**
   * 미리보기를 받아 화면에 반영한다 — 파일을 처음 올릴 때와 시트를 바꿀 때(WD-253)가 함께 쓴다.
   * 서버가 성공 상태로 빈 본문을 돌려주면(예전 IOException 처리 결함, WD-204) 화면이 비므로, 미리보기 내용(파일 컬럼 목록)이
   * 없으면 반영하지 않고 오류로 안내한다. 실패하면 previewData 가 그대로라 이전 미리보기(·선택 시트)가 남는다.
   * @returns 반영했으면 true
   */
  const loadPreview = async (
    file: File,
    sheetName: string | undefined,
    messages: { empty: string; failed: string }
  ): Promise<boolean> => {
    try {
      const preview = await previewImport.mutateAsync({ file, sheetName });
      if (!preview || !Array.isArray(preview.fileHeaders)) {
        toast.error(messages.empty);
        return false;
      }
      setPreviewData(preview);
      return true;
    } catch (error) {
      handleApiError(error, messages.failed);
      return false;
    }
  };

  const handleFileSelect = async (file: File) => {
    setSelectedFile(file);
    // 처음 올릴 때는 시트를 지정하지 않는다 — 서버가 첫 번째 보이는 시트를 골라 응답의 sheetName 으로 알려 준다
    const loaded = await loadPreview(file, undefined, {
      empty: '파일 내용을 읽지 못했습니다. 파일을 확인한 뒤 다시 올려 주세요.',
      failed: '미리보기 로드에 실패했습니다.',
    });
    if (loaded) setStep(2);
  };

  /**
   * 시트 선택 변경(WD-253) — 같은 파일로 고른 시트의 미리보기를 다시 받아 매핑·샘플을 갈아 끼운다.
   * 2단계는 그대로 두고(setStep 호출 안 함) 내용만 바꾼다. 매핑은 previewData 효과가 새 추천값으로 초기화한다.
   * 검증 중에는 선택 상자가 잠겨 있어(ImportMappingDialog) 늦게 온 이전 시트의 검증 결과가 새 시트 화면에 섞이지 않는다.
   */
  const handleSheetChange = async (sheetName: string) => {
    if (!selectedFile || sheetName === previewData?.sheetName) return;
    const loaded = await loadPreview(selectedFile, sheetName, {
      empty: '시트 내용을 읽지 못했습니다. 다른 시트를 고르거나 파일을 확인해 주세요.',
      failed: '시트 미리보기 로드에 실패했습니다.',
    });
    if (loaded) {
      // 검증 결과는 이전 시트 기준이라 더는 맞지 않는다
      setValidationResult(null);
      setShowAllErrors(false);
    }
  };

  // 매핑할 수 없는 빈 이름 열 표시는 미리보기가 바뀔 때만 다시 센다 (WD-369)
  const unmappableColumns = useMemo(
    () => unmappableBlankColumns((previewData?.suggestedMappings ?? []).map((sm) => sm.fileColumn)),
    [previewData]
  );

  // 매핑은 suggestedMappings(=파일 열)와 같은 순서라 위치로 바꾼다. 이름으로 찾으면 이름이 같은 빈 열들이 함께 바뀐다 (WD-369)
  const handleMappingChange = (index: number, datasetColumn: string | null) => {
    setMappings((prev) => {
      const target = prev[index];
      if (!target) return prev;
      // 매핑할 수 없는 빈 이름 열 — 선택 상자가 잠겨 있지만 상태로도 막아 둔다 (WD-369)
      if (datasetColumn !== null && unmappableColumns[index]) {
        return prev;
      }
      return prev.map((m, i) => (i === index ? { ...m, datasetColumn } : m));
    });
    setValidationResult(null);
  };

  const handleValidate = async () => {
    if (!selectedFile) return;
    // 매핑된 컬럼이 0개면 서버 검증(행 단위 값 검사)만으로는 "매핑 자체가 없다"는 사실을
    // 잡아내지 못해 항상 "통과"로 보인다(#653) — 서버 호출 전에 명시적으로 차단한다.
    const anyMapped = mappings.some((m) => m.datasetColumn !== null);
    if (!anyMapped) {
      toast.error('매핑된 컬럼이 없습니다. 최소 1개 이상의 컬럼을 매핑해주세요.');
      setValidationResult(null);
      return;
    }
    try {
      // 마지막 미리보기에서 읽은 시트를 그대로 검증한다(엑셀만 값이 있음, CSV 는 null 이라 생략됨)
      const result = await validateImport.mutateAsync({
        file: selectedFile,
        mappings,
        sheetName: previewData?.sheetName,
      });
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
      // 미리보기·검증과 같은 시트를 가져오도록 마지막 미리보기의 sheetName 을 함께 보낸다 (WD-253)
      const result = await uploadFile.mutateAsync({
        file: selectedFile,
        mappings,
        importMode,
        sheetName: previewData?.sheetName,
      });
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
  // 필수(NOT NULL) 컬럼이 없는 데이터셋(nullable 컬럼만 존재)이면 hasUnmappedRequired가
  // 항상 false가 되어 매핑 0개인 상태도 통과시키는 구멍이 생긴다(#653) — 매핑이 하나라도
  // 있는지를 별도로 검사해 임포트/검증 차단 조건에 함께 사용한다.
  const hasAnyMapping = mappings.some((m) => m.datasetColumn !== null);
  const hasPrimaryKey = datasetColumns.some((col) => col.isPrimaryKey);

  // 위치(index)로 현재 행을 고른다 — 이름이 같은 빈 열끼리 서로의 선택을 「자기 것」으로 보지 않게 한다 (WD-369)
  const getAvailableDatasetColumns = (index: number): DatasetColumnResponse[] => {
    const currentMapping = mappingAt(mappings, index, previewData?.suggestedMappings[index]?.fileColumn);
    const usedColumns = mappings
      .filter((m, i) => i !== index && m.datasetColumn !== null)
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
      sheetChange: handleSheetChange,
      mappingChange: handleMappingChange,
      validate: handleValidate,
      import: handleImport,
      close: handleClose,
    },
    derived: {
      requiredColumns,
      unmappedRequired,
      hasUnmappedRequired,
      hasAnyMapping,
      hasPrimaryKey,
      getAvailableDatasetColumns,
      unmappableColumns,
      displayedErrors,
    },
    status: {
      isUploading: uploadFile.isPending,
      isValidating: validateImport.isPending,
      isPreviewing: previewImport.isPending,
    },
  };
}
