import type { DocumentFileResponse, DocumentSearchHit, DocumentSearchRequest } from '../types/document';
import { client } from './client';

export const documentsApi = {
  /** 문서 업로드 — multipart/form-data. 202 Accepted + 메타 반환(인제스션은 비동기). */
  uploadDocument: (datasetId: number, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return client.post<DocumentFileResponse>(`/datasets/${datasetId}/documents`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  listDocuments: (datasetId: number) =>
    client.get<DocumentFileResponse[]>(`/datasets/${datasetId}/documents`),
  deleteDocument: (datasetId: number, documentId: number) =>
    client.delete<void>(`/datasets/${datasetId}/documents/${documentId}`),
  /** 의미검색 — datasetIds 생략 시 전역. 현재 UI는 항상 단일 데이터셋으로 호출. */
  searchDocuments: (request: DocumentSearchRequest) =>
    client.post<DocumentSearchHit[]>(`/documents/search`, request),
};
