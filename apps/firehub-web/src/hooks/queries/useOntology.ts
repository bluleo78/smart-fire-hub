import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { ontologyApi } from '@/api/ontology';
import { handleApiError, isConflictError } from '@/lib/api-error';
import type { CreateOntologyRequest, OntologyStatus, UpdateOntologyRequest } from '@/types/ontology';

// 온톨로지 스키마(정적) — 캐시 오래 유지.
export const useOntologySchema = () =>
  useQuery({ queryKey: ['ontology'], queryFn: () => ontologyApi.getOntology().then((r) => r.data), staleTime: 5 * 60 * 1000 });

// 전체 지식그래프.
export const useOntologyGraph = () =>
  useQuery({ queryKey: ['ontology', 'graph'], queryFn: () => ontologyApi.getGraph().then((r) => r.data) });

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
 * id 스코프 스키마 편집. 성공 시 해당 온톨로지 스키마와 목록을 함께 무효화한다.
 * 상태 전이는 여기가 아니라 useOntologyStatusTransition(전용 PATCH)이 담당한다.
 * ['ontology', id]는 숫자 id라 ['ontology','graph']와 겹치지 않으므로 exact가 필요 없다.
 */
export function useUpdateOntologyById() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, req }: { id: number; req: UpdateOntologyRequest }) =>
      ontologyApi.updateOntologyById(id, req).then((r) => r.data),
    onSuccess: (_data, { id }) => {
      queryClient.invalidateQueries({ queryKey: ['ontology', id] });
      queryClient.invalidateQueries({ queryKey: ['ontologies'] });
      // id=1(레거시 기본 온톨로지)은 useOntologySchema/useOntologyGraph가 별도 키(['ontology'] bare,
      // ['ontology','graph'])로도 캐싱한다 — by-id 무효화만으로는 이 레거시 키에 닿지 않아
      // 지식그래프 시각화 페이지가 편집 직후 낡은 스키마·그래프를 보여준다.
      // id!==1인 온톨로지는 레거시 키와 무관하므로 불필요한 재조회를 만들지 않는다.
      // exact: true — ['ontology', 2] 같은 다른 by-id 캐시까지 쓸려나가지 않도록 bare ['ontology']만 겨냥한다.
      // 이 분기는 임시가 아니다 — 지식그래프 시각화 페이지(useOntologySchema/useOntologyGraph)가
      // bare 훅을 계속 쓰는 한 상시로 필요하다. 그 페이지가 by-id 훅으로 옮겨가기 전까지는 지우지 말 것.
      if (id === 1) {
        queryClient.invalidateQueries({ queryKey: ['ontology'], exact: true });
        queryClient.invalidateQueries({ queryKey: ['ontology', 'graph'] });
      }
    },
    // 409(버전 충돌) 시 캐시된 스키마는 이미 낡았다 —
    // 해당 id의 캐시를 재조회해야 편집기의 latestVersion이 갱신되고 "덮어쓰고 저장"이 열린다(#301).
    onError: (error, { id }) => {
      if (isConflictError(error)) {
        queryClient.invalidateQueries({ queryKey: ['ontology', id] });
      }
    },
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
    },
  });
}
