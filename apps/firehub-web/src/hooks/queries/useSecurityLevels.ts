import { useQuery } from '@tanstack/react-query';

import { securityLevelsApi } from '../../api/security-levels';

/** 등급 목록(rank 오름차순) — 배지·선택 UI 공용. 등급은 자주 바뀌지 않아 1분 캐시. */
export function useSecurityLevels() {
  return useQuery({
    queryKey: ['security-levels'],
    queryFn: () => securityLevelsApi.list().then((r) => r.data),
    staleTime: 60_000,
  });
}

/** 등급별 사용량(개수만) — security:settings 보유자만 호출(enabled). */
export function useSecurityLevelUsage(enabled: boolean) {
  return useQuery({
    queryKey: ['security-levels', 'usage'],
    queryFn: () => securityLevelsApi.usage().then((r) => r.data),
    enabled,
  });
}

/** 본인 열람 자격 — 등급 변경 다이얼로그가 상위 등급을 비활성화하는 데 쓴다. */
export function useMyClearance() {
  return useQuery({
    queryKey: ['security-levels', 'my-clearance'],
    queryFn: () => securityLevelsApi.myClearance().then((r) => r.data),
  });
}

/** 데이터셋 접근 허용 목록. */
export function useAccessGrants(datasetId: number, enabled = true) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'access-grants'],
    queryFn: () => securityLevelsApi.listGrants(datasetId).then((r) => r.data),
    enabled: enabled && !!datasetId,
  });
}

/** 역할의 열람 자격(등급). */
export function useRoleClearance(roleId: number) {
  return useQuery({
    queryKey: ['roles', roleId, 'clearance'],
    queryFn: () => securityLevelsApi.getRoleClearance(roleId).then((r) => r.data),
    enabled: !!roleId,
  });
}
