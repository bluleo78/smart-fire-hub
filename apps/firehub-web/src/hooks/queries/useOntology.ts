import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ontologyApi } from '@/api/ontology';

// 온톨로지 스키마(정적) — 캐시 오래 유지.
export const useOntologySchema = () =>
  useQuery({ queryKey: ['ontology'], queryFn: () => ontologyApi.getOntology().then((r) => r.data), staleTime: 5 * 60 * 1000 });

// 전체 지식그래프.
export const useOntologyGraph = () =>
  useQuery({ queryKey: ['ontology', 'graph'], queryFn: () => ontologyApi.getGraph().then((r) => r.data) });

// 지식 모델 편집(B-2b, ADMIN 전용) — 성공 시 스키마 쿼리를 갱신본으로 갱신한다(재조회 없이 응답을 캐시에 반영).
export const useUpdateOntology = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (req: Parameters<typeof ontologyApi.updateOntology>[0]) =>
      ontologyApi.updateOntology(req).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['ontology'], data);
    },
  });
};
