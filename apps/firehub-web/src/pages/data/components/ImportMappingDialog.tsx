import { Loader2 } from 'lucide-react';

import { Button } from '../../../components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
import type { DatasetColumnResponse } from '../../../types/dataset';
import { useImportDialog } from '../hooks/useImportDialog';
import { FileUploadZone } from './FileUploadZone';
import { ImportMappingTable } from './ImportMappingTable';
import { ImportModeSelector } from './ImportModeSelector';
import { ImportProgressView } from './ImportProgressView';
import { ImportSamplePreview } from './ImportSamplePreview';
import { ImportSheetSelector } from './ImportSheetSelector';
import { ImportValidationSection } from './ImportValidationSection';

interface ImportMappingDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  datasetColumns: DatasetColumnResponse[];
}

export function ImportMappingDialog(props: ImportMappingDialogProps) {
  const {
    step,
    previewData,
    mappings,
    validationResult,
    showAllErrors,
    setShowAllErrors,
    importMode,
    setImportMode,
    importProgress,
    failedErrors,
    handlers,
    derived,
    status,
  } = useImportDialog(props);

  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      {/*
       * flex flex-col: 높이는 공용 DialogContent 의 max-h(100dvh-4rem) 로 제한하면서(WD-1123)
       * 내부를 flex 컬럼으로 구성해 스크롤 영역과 footer를 분리한다.
       * WD-1140: overflow-hidden 은 뺐다 — 안쪽 래퍼가 먼저 스크롤하고, 극단적으로 낮은 화면에서는 바깥이 마지막 수단으로 스크롤한다.
       */}
      <DialogContent className="flex flex-col">
        <DialogHeader>
          <DialogTitle>파일 임포트</DialogTitle>
          <DialogDescription className="sr-only">파일을 업로드하고 컬럼을 매핑하여 데이터셋에 가져옵니다.</DialogDescription>
        </DialogHeader>

        {step === 1 && (
          // WD-1123: 낮은 뷰포트에서 업로드 영역이 잘리지 않게 매핑 단계처럼 안쪽에서 스크롤한다.
          <div className="relative min-h-24 flex-1 space-y-4 overflow-y-auto">
            <FileUploadZone onFileSelect={handlers.fileSelect} disabled={status.isPreviewing} />
            {status.isPreviewing && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" />
                <span>파일 분석 중...</span>
              </div>
            )}
          </div>
        )}

        {step === 2 && previewData && (
          <>
            {/*
             * flex-1 overflow-y-auto: 컨텐츠 영역이 남은 공간을 차지하며 세로 스크롤된다.
             * WD-1140: min-h-24 는 영역이 0 으로 찌그러지지 않게, relative 는 sr-only(absolute) 자식이 바깥 scrollHeight 를 부풀리지 않게,
             * overscroll-contain 없음은 끝에서 휠이 바깥으로 이어져 영역 밖 푸터에 닿게 한다.
             */}
            <div className="relative flex-1 overflow-y-auto min-h-24 space-y-6 min-w-0">
              {/*
               * 엑셀이면 어느 시트를 가져오는지 맨 위에 보여 준다(WD-253). 시트를 바꿔 미리보기를 다시 받는 동안에도
               * 아래 매핑 표를 언마운트하지 않는다 — 표가 다시 마운트되면 제목으로 포커스를 끌어가기 때문(#330).
               */}
              <ImportSheetSelector
                sheets={previewData.sheets}
                sheetName={previewData.sheetName}
                // 검증 중에도 잠근다 — 검증 응답이 시트를 바꾼 뒤에 도착해 새 시트 화면에 이전 시트 결과가 섞이지 않게 한다
                disabled={status.isPreviewing || status.isValidating}
                onSheetChange={handlers.sheetChange}
              />

              <ImportMappingTable
                suggestedMappings={previewData.suggestedMappings}
                mappings={mappings}
                hasUnmappedRequired={derived.hasUnmappedRequired}
                hasAnyMapping={derived.hasAnyMapping}
                unmappedRequired={derived.unmappedRequired}
                getAvailableDatasetColumns={derived.getAvailableDatasetColumns}
                unmappableColumns={derived.unmappableColumns}
                onMappingChange={handlers.mappingChange}
              />

              <ImportModeSelector
                importMode={importMode}
                hasPrimaryKey={derived.hasPrimaryKey}
                onModeChange={setImportMode}
              />

              <ImportSamplePreview
                previewData={previewData}
                mappings={mappings}
                datasetColumns={props.datasetColumns}
              />

              <ImportValidationSection
                validationResult={validationResult}
                showAllErrors={showAllErrors}
                displayedErrors={derived.displayedErrors}
                hasUnmappedRequired={derived.hasUnmappedRequired}
                hasAnyMapping={derived.hasAnyMapping}
                isValidating={status.isValidating}
                isPreviewing={status.isPreviewing}
                onValidate={handlers.validate}
                onShowAllErrors={setShowAllErrors}
              />
            </div>

            {/*
             * sticky footer: 스크롤 영역 밖에 위치한다. 낮지 않은 화면에서는 스크롤 영역 밖 푸터라 늘 보이고,
             * 아주 낮은 화면은 바깥 스크롤(마지막 수단)으로 닿는다.
             * shrink-0: flex 컨테이너에서 footer가 축소되지 않도록 고정한다.
             */}
            <div className="shrink-0 flex justify-end gap-2 pt-4 border-t bg-background">
              <Button variant="outline" onClick={() => props.onOpenChange(false)} disabled={status.isUploading}>
                취소
              </Button>
              {/*
               * validRows가 0이면 임포트 불가 — 헤더만 있고 데이터 행이 없는 파일 방지 (#128)
               * !hasAnyMapping이면 임포트 불가 — 매핑된 컬럼이 0개인데 통과로 보이며 전부 NULL
               * 행이 생성되는 것을 방지 (#653). NOT NULL 컬럼이 없는 데이터셋에서는
               * hasUnmappedRequired만으로는 이 상태를 막지 못한다.
               * isPreviewing이면 임포트 불가 — 시트를 바꿔 미리보기를 다시 받는 동안 누르면 바뀌기 전 시트·매핑으로
               * 가져오기가 시작된다(WD-253).
               */}
              <Button onClick={handlers.import} disabled={derived.hasUnmappedRequired || !derived.hasAnyMapping || status.isUploading || status.isPreviewing || (validationResult !== null && validationResult.validRows === 0)}>
                {status.isUploading ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" />
                    임포트 중...
                  </>
                ) : (
                  '임포트'
                )}
              </Button>
            </div>
          </>
        )}

        {step === 3 && (
          // WD-1140/WD-1152: 3단계 바깥 칸은 스크롤하지 않는다 — 본문 스크롤과 푸터(확인/닫기)는 ImportProgressView 가 나눠 맡는다.
          <div className="flex min-h-0 flex-1 flex-col">
            <ImportProgressView progress={importProgress} onClose={handlers.close} failedErrors={failedErrors} />
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
