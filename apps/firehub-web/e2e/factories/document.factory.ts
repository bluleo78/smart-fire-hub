import type { DocumentFileResponse } from '../../src/types/document';

/** DocumentFileResponse 1건 생성. 기본은 COMPLETED 상태. */
export function createDocument(overrides?: Partial<DocumentFileResponse>): DocumentFileResponse {
  return {
    id: 1,
    datasetId: 1,
    originalName: 'sample.pdf',
    mimeType: 'application/pdf',
    fileSize: 102400,
    status: 'COMPLETED',
    pageCount: 10,
    chunkCount: 45,
    errorDetail: null,
    uploadedBy: 1,
    createdAt: '2026-06-13T10:00:00',
    completedAt: '2026-06-13T10:05:00',
    ...overrides,
  };
}

/** DocumentFileResponse N건. */
export function createDocuments(count: number): DocumentFileResponse[] {
  return Array.from({ length: count }, (_, i) =>
    createDocument({ id: i + 1, originalName: `document_${i + 1}.pdf` }),
  );
}
