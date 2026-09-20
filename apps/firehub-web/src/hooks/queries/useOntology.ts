import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { ontologyApi } from '@/api/ontology';
import { handleApiError } from '@/lib/api-error';
import type { CreateOntologyRequest, OntologyStatus } from '@/types/ontology';

/**
 * 한 온톨로지의 인스턴스 그래프. 스코프는 서버가 잡는다(근거는 OntologyService.getGraph 참고).
 *
 * ontologyId 가 없으면 조회 자체를 하지 않는다(enabled) — 고를 온톨로지가 없으면 물어보지도 않는다.
 * 쿼리 키에 id 를 넣는 것이 필수다: 키가 고정이면 온톨로지를 바꿔도 이전 온톨로지의 캐시가 그대로
 * 재사용돼(react-query structural sharing) 화면이 조용히 옛 그래프를 보여준다.
 *
 * staleTime 은 형제 훅들과 같은 5분 — 전역 기본값(30초)을 쓰면 온톨로지를 왕복할 때마다 수 MB
 * 페이로드를 배경 재조회한다. 이 응답이 셋 중 가장 크므로 오히려 더 길어야 할 자리다.
 */
export const useOntologyGraph = (ontologyId: number | null | undefined) =>
  useQuery({
    queryKey: ['ontology', 'graph', ontologyId],
    queryFn: () => ontologyApi.getGraph(ontologyId as number).then((r) => r.data),
    enabled: ontologyId != null,
    staleTime: 5 * 60 * 1000,
  });

/**
 * 온톨로지 목록. status 미지정이면 서버 기본값(active만) — 바인딩 후보 용도다.
 * 관리 다이얼로그는 'all'을 넘겨 draft·archived까지 본다.
 * 쿼리 키에 status를 넣어 후보 목록과 관리 목록의 캐시가 서로를 덮지 않게 한다.
 */
export function useOntologyList(status?: OntologyStatus | 'all') {
  return useQuery({
    queryKey: ['ontologies', status ?? 'active'],
    queryFn: () => ontologyApi.listOntologies(status).then((r) => r.data),
    staleTime: 5 * 60 * 1000,
  });
}

/** id 스코프 스키마. 표 데이터셋·다중 온톨로지는 id=1 고정이 아니므로 반드시 by-id로 읽는다. */
export function useOntologyById(ontologyId: number | null | undefined) {
  return useQuery({
    queryKey: ['ontology', ontologyId],
    queryFn: () => ontologyApi.getOntologyById(ontologyId as number).then((r) => r.data),
    enabled: ontologyId != null,
    staleTime: 5 * 60 * 1000,
  });
}

/** 신규 온톨로지 생성. 목록 전체를 무효화한다(status별 캐시가 여러 개라 prefix로 턴다). */
export function useCreateOntology() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (req: CreateOntologyRequest) => ontologyApi.createOntology(req).then((r) => r.data),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ontologies'] }),
  });
}

/**
 * 상태 전이(활성화/복귀/은퇴) 공용 실행기 — OntologyStatusBanner(배너)와 OntologyManageDialog(관리
 * 테이블)가 공유한다. 성공 문구는 맥락마다 다르므로(배너는 도메인명 없이 "온톨로지가 활성화되었습니다",
 * 관리 다이얼로그는 도메인명을 포함) 호출부가 그대로 넘긴다.
 *
 * 전용 PATCH를 쓰므로 호출부가 대상 스키마를 미리 조회하지 않아도 된다 — 관리 테이블이 행마다
 * useOntologyById로 N회 조회하던 것이 이 때문에 사라졌다.
 * 무효화는 목록(['ontologies'])만 하면 된다: 상태는 OntologySummary에만 있고 ['ontology', id]가 담는
 * OntologySchema에는 없다. 스키마도 변하지 않으므로 by-id 캐시를 털 이유가 없다.
 */
export function useOntologyStatusTransition() {
  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: ({ id, status }: { id: number; status: OntologyStatus }) =>
      ontologyApi.updateOntologyStatus(id, status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ontologies'] }),
  });
  const transition = async (params: {
    id: number;
    status: OntologyStatus;
    successMessage: string;
    failureMessage: string;
  }) => {
    try {
      await mutation.mutateAsync({ id: params.id, status: params.status });
      toast.success(params.successMessage);
    } catch (error) {
      handleApiError(error, params.failureMessage);
    }
  };
  return { transition, isPending: mutation.isPending };
}

/** 삭제. 성공 시 목록 무효화 + 삭제된 온톨로지의 개별 캐시를 제거한다. */
export function useDeleteOntology() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => ontologyApi.deleteOntology(id),
    onSuccess: (_data, id) => {
      queryClient.invalidateQueries({ queryKey: ['ontologies'] });
      queryClient.removeQueries({ queryKey: ['ontology', id] });
      // 그래프 캐시는 ['ontology', 'graph', id] 라 위 접두사에 걸리지 않는다 — 따로 지우지 않으면
      // 삭제된 온톨로지의 수 MB 그래프가 gcTime 까지 메모리에 남는다.
      queryClient.removeQueries({ queryKey: ['ontology', 'graph', id] });
    },
  });
}
