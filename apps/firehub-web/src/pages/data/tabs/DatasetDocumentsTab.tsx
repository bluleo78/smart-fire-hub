import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../../../components/ui/button';
import { useDeleteDocument, useDocuments, useUploadDocument } from '../../../hooks/queries/useDocuments';
import type { DatasetDetailResponse } from '../../../types/dataset';
import { DocumentList } from '../components/DocumentList';
import { DocumentSearchPanel } from '../components/DocumentSearchPanel';
import { FileUploadZone } from '../components/FileUploadZone';

interface DatasetDocumentsTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

/** 업로드 허용 확장자 — 텍스트 추출 가능한 문서 형식. */
const ACCEPT = '.pdf,.docx,.doc,.txt,.md';

/**
 * DOCUMENT 데이터셋 전용 탭. 문서 업로드/목록/삭제를 제공한다.
 * (의미검색 패널은 후속 태스크에서 추가.)
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function DatasetDocumentsTab({ dataset: _dataset, datasetId }: DatasetDocumentsTabProps) {
  const { data: documents, isLoading } = useDocuments(datasetId);
  const upload = useUploadDocument(datasetId);
  const remove = useDeleteDocument(datasetId);
  const [pendingFile, setPendingFile] = useState<File | null>(null);

  const handleUpload = () => {
    if (!pendingFile) return;
    upload.mutate(pendingFile, {
      onSuccess: () => {
        toast.success('업로드를 시작했습니다. 처리에는 시간이 걸릴 수 있습니다.');
        setPendingFile(null);
      },
      onError: () => toast.error('업로드에 실패했습니다.'),
    });
  };

  const handleDelete = (documentId: number) => {
    if (!window.confirm('이 문서를 삭제하시겠습니까? 관련 임베딩도 함께 삭제됩니다.')) return;
    remove.mutate(documentId, {
      onSuccess: () => toast.success('문서를 삭제했습니다.'),
      onError: () => toast.error('삭제에 실패했습니다.'),
    });
  };

  return (
    <div className="space-y-6">
      <section>
        <h2 className="text-lg font-semibold mb-3">문서 업로드</h2>
        <FileUploadZone
          onFileSelect={setPendingFile}
          accept={ACCEPT}
          disabled={upload.isPending}
          rejectionMessage="PDF, Word, 텍스트 문서만 지원합니다."
        />
        <div className="mt-3">
          <Button type="button" disabled={!pendingFile || upload.isPending} onClick={handleUpload}>
            {upload.isPending ? '업로드 중...' : '업로드'}
          </Button>
        </div>
      </section>

      <section data-testid="document-list-section">
        <h2 className="text-lg font-semibold mb-3">문서 목록</h2>
        {isLoading ? (
          <p className="text-sm text-muted-foreground">불러오는 중...</p>
        ) : !documents || documents.length === 0 ? (
          <p className="text-sm text-muted-foreground">업로드된 문서가 없습니다.</p>
        ) : (
          <DocumentList
            documents={documents}
            onDelete={handleDelete}
            deletingId={remove.isPending ? (remove.variables ?? null) : null}
          />
        )}
      </section>

      <DocumentSearchPanel datasetId={datasetId} />
    </div>
  );
}
