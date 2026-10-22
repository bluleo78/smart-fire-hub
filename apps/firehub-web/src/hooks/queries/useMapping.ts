import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

import { mappingApi } from '@/api/mapping';
import type { MappingResponse, MappingSpec } from '@/types/mapping';

import { MAPPING_USAGE_QUERY_PREFIX } from './useOntologyElement';

// 온톨로지 관심사는 useOntology.ts가 소유한다. 기존 import 경로를 깨지 않도록 여기서 다시 내보낸다.
export { useOntologyById, useOntologyList } from './useOntology';

/**
 * 데이터셋 매핑 조회.
 * 매핑이 없으면 백엔드가 404를 주는데, 이는 에러가 아니라 "아직 만들지 않음"이라는 정상 초기 상태다.
 * 그대로 두면 신규 데이터셋마다 에러 토스트가 뜨므로 null로 정규화한다.
 * retry: false — 404를 재시도해봐야 소용없고, E2E 타이밍도 불안정해진다.
 */
export function useMapping(datasetId: number) {
  return useQuery<MappingResponse | null>({
    queryKey: ['datasets', datasetId, 'mapping'],
    queryFn: () =>
      mappingApi
        .getMapping(datasetId)
        .then((r) => r.data)
        .catch((err: unknown) => {
          if (axios.isAxiosError(err) && err.response?.status === 404) return null;
          throw err;
        }),
    enabled: !!datasetId,
    retry: false,
  });
}

/** 데이터셋↔온톨로지 바인딩 조회. ontologyId가 null이면 미바인딩. */
export function useBinding(datasetId: number) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'ontology-binding'],
    queryFn: () => mappingApi.getBinding(datasetId).then((r) => r.data),
    enabled: !!datasetId,
  });
}

/**
 * 온톨로지 연결. 성공 시 바인딩 쿼리를 무효화해 탭이 매핑 편집 상태로 전이한다.
 * WD-184: 다른 모델로 다시 연결하면 백엔드가 이전 모델 기준 매핑을 지운다(mappingRemoved). 그때는 캐시된 옛 매핑이
 * 새 모델 아래에 남아 보이지 않게 매핑 쿼리와, 지식 모델 화면의 사용처 쿼리도 함께 무효화한다.
 * 결과(BindOntologyResponse)를 돌려주어 호출부가 삭제 안내를 띄울 수 있게 한다. 테스트 목처럼 본문이 없으면 null.
 */
export function useBindOntology(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ontologyId: number) => mappingApi.bindOntology(datasetId, ontologyId).then((r) => r.data ?? null),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'ontology-binding'] });
      if (data?.mappingRemoved) {
        queryClient.invalidateQueries({ queryKey: ['datasets', datasetId, 'mapping'] });
        queryClient.invalidateQueries({ queryKey: [MAPPING_USAGE_QUERY_PREFIX] });
      }
    },
  });
}

/** 초안 저장. 응답을 캐시에 직접 반영해 재조회 없이 상태 배지를 갱신한다. */
export function useSaveMapping(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (spec: MappingSpec) => mappingApi.saveMapping(datasetId, spec).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['datasets', datasetId, 'mapping'], data);
      // WD-129: 지식 모델 삭제 확인 창의 매핑 사용처가 이 저장으로 바뀐다(요소를 넣거나 뺌). "매핑에서 먼저 빼 주세요" 안내를
      // 따라 뺀 뒤 편집기로 돌아왔을 때 낡은 "사용 중" 이 확인 버튼을 계속 막지 않게 모든 온톨로지의 사용처를 무효화한다
      // (바인딩된 온톨로지 id 를 여기서는 모른다).
      queryClient.invalidateQueries({ queryKey: [MAPPING_USAGE_QUERY_PREFIX] });
    },
  });
}

/** 활성화. 저장된 spec을 서버가 재검증하므로 실패(400)가 여기서 처음 드러날 수 있다. */
export function useActivateMapping(datasetId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => mappingApi.activateMapping(datasetId).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['datasets', datasetId, 'mapping'], data);
    },
  });
}
