import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { documentsApi } from '../../api/documents';
import type { DocumentFileResponse, DocumentSearchRequest } from '../../types/document';

/** 진행중(비종료) 상태 — 폴링 지속 판단에 사용 */
const NON_TERMINAL = new Set(['PENDING', 'PARSING', 'EMBEDDING']);

/**
 * 문서 목록. 인제스션이 비동기이므로 비종료 문서가 하나라도 있으면 3초 폴링,
 * 모두 종료(COMPLETED/FAILED)면 폴링 중단 — 무한 폴링 방지.
 */
export function useDocuments(datasetId: number) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'documents'],
    queryFn: () => documentsApi.listDocuments(datasetId).then((r) => r.data),
    enabled: !!datasetId,
    refetchInterval: (query) => {
      const data = query.state.data;
      const hasPending = data?.some((d: DocumentFileResponse) => NON_TERMINAL.has(d.status));
      return hasPending ? 3000 : false;
    },
  });
}

export function useUploadDocument(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation<DocumentFileResponse, unknown, File>({
    mutationFn: (file) => documentsApi.uploadDocument(datasetId, file).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'documents'] });
    },
  });
}

export function useDeleteDocument(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation<void, unknown, number>({
    mutationFn: (documentId) => documentsApi.deleteDocument(datasetId, documentId).then((r) => r.data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'documents'] });
    },
  });
}

/** 의미검색 — 결과를 캐시하지 않고 mutation으로 on-demand 실행. */
export function useSearchDocuments() {
  return useMutation({
    mutationFn: (request: DocumentSearchRequest) =>
      documentsApi.searchDocuments(request).then((r) => r.data),
  });
}
