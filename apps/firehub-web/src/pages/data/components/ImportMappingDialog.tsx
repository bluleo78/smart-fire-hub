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
    handlers,
    derived,
    status,
  } = useImportDialog(props);

  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      {/*
       * flex flex-col + max-h-[90vh]: 다이얼로그 전체 높이를 뷰포트 90%로 제한하면서
       * 내부를 flex 컬럼으로 구성해 스크롤 영역과 footer를 분리한다.
       * overflow-hidden: DialogContent 자체는 스크롤하지 않고 내부 영역이 스크롤하도록 한다.
       */}
      <DialogContent className="flex flex-col max-h-[90vh] overflow-hidden overflow-x-hidden">
        <DialogHeader>
          <DialogTitle>파일 임포트</DialogTitle>
          <DialogDescription className="sr-only">파일을 업로드하고 컬럼을 매핑하여 데이터셋에 가져옵니다.</DialogDescription>
        </DialogHeader>

        {step === 1 && (
          <div className="space-y-4">
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
             * min-h-0: flex 자식 요소의 기본 min-height 제약을 해제해 올바른 스크롤이 되도록 한다.
             */}
            <div className="flex-1 overflow-y-auto min-h-0 space-y-6 min-w-0">
              <ImportMappingTable
                suggestedMappings={previewData.suggestedMappings}
                mappings={mappings}
                hasUnmappedRequired={derived.hasUnmappedRequired}
                unmappedRequired={derived.unmappedRequired}
                getAvailableDatasetColumns={derived.getAvailableDatasetColumns}
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
                isValidating={status.isValidating}
                onValidate={handlers.validate}
                onShowAllErrors={setShowAllErrors}
              />
            </div>

            {/*
             * sticky footer: 스크롤 영역 밖에 위치하여 콘텐츠 길이에 상관없이
             * 취소/임포트 버튼이 항상 뷰포트 내에 표시된다.
             * shrink-0: flex 컨테이너에서 footer가 축소되지 않도록 고정한다.
             */}
            <div className="shrink-0 flex justify-end gap-2 pt-4 border-t bg-background">
              <Button variant="outline" onClick={() => props.onOpenChange(false)} disabled={status.isUploading}>
                취소
              </Button>
              <Button onClick={handlers.import} disabled={derived.hasUnmappedRequired || status.isUploading}>
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
          <ImportProgressView progress={importProgress} onClose={handlers.close} />
        )}
      </DialogContent>
    </Dialog>
  );
}
