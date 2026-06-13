import { useDocuments } from '../../../hooks/queries/useDocuments';
import type { DatasetDetailResponse } from '../../../types/dataset';

interface DatasetDocumentsTabProps {
  dataset: DatasetDetailResponse;
  datasetId: number;
}

/**
 * DOCUMENT 데이터셋 전용 탭. 문서 업로드/목록/삭제/의미검색을 제공한다.
 * (Task 4: 목록 골격, 이후 태스크에서 업로드·삭제·검색을 채운다.)
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function DatasetDocumentsTab({ dataset: _dataset, datasetId }: DatasetDocumentsTabProps) {
  const { data: documents, isLoading } = useDocuments(datasetId);

  return (
    <div className="space-y-6">
      <section data-testid="document-list-section">
        <h2 className="text-lg font-semibold mb-3">문서 목록</h2>
        {isLoading ? (
          <p className="text-sm text-muted-foreground">불러오는 중...</p>
        ) : !documents || documents.length === 0 ? (
          <p className="text-sm text-muted-foreground">업로드된 문서가 없습니다.</p>
        ) : (
          <ul className="text-sm">
            {documents.map((d) => (
              <li key={d.id}>{d.originalName}</li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
