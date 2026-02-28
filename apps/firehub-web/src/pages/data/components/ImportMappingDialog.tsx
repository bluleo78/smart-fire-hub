import { Loader2 } from 'lucide-react';

import { Button } from '../../../components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../../components/ui/dialog';
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
      <DialogContent className="max-h-[90vh] overflow-y-auto overflow-x-hidden">
        <DialogHeader>
          <DialogTitle>파일 임포트</DialogTitle>
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
          <div className="space-y-6 min-w-0">
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

            <div className="flex justify-end gap-2 pt-4 border-t">
              <Button variant="outline" onClick={() => props.onOpenChange(false)} disabled={status.isUploading}>
                취소
              </Button>
              <Button onClick={handlers.import} disabled={derived.hasUnmappedRequired || status.isUploading}>
                {status.isUploading ? (
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
          <ImportProgressView progress={importProgress} onClose={handlers.close} />
        )}
      </DialogContent>
    </Dialog>
  );
}
