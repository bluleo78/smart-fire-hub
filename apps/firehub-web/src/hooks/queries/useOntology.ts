import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ontologyApi } from '@/api/ontology';
import { isConflictError } from '@/lib/api-error';

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
    // 409(버전 충돌) 시 캐시된 스키마는 이미 낡았다 — 재조회해 최신 schemaVersion을 확보해야
    // 사용자가 편집을 유지한 채 재저장할 수 있다(#301). 재조회하지 않으면 재시도·재오픈 모두
    // 같은 낡은 버전을 다시 보내 409가 무한 반복된다.
    // exact: true — ['ontology','graph'](읽기 전용 인스턴스 그래프)까지 끌어오지 않기 위함.
    onError: (error) => {
      if (isConflictError(error)) {
        queryClient.invalidateQueries({ queryKey: ['ontology'], exact: true });
      }
    },
  });
};
