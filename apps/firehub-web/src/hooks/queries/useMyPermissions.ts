import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { authApi } from '../../api/auth';
import { useAuth } from '../useAuth';

/**
 * 내 권한 코드(현재 워크스페이스). 웹은 지금까지 역할 이름(isAdmin)만 봤는데, 멤버 추가는 스펙상
 * user:write·role:assign 권한 기준이라 권한 코드를 직접 읽는다. 최종 판정은 서버 — 여기는 노출 결정용.
 *
 * 쿼리 키에 사용자·워크스페이스를 넣는다(WD-121). 로그아웃·재로그인은 페이지를 다시 읽지 않아 react-query 캐시가 남는데,
 * 키가 사용자와 무관하면 B 가 로그인해도 staleTime 동안 A 의 권한으로 메뉴·화면이 판정됐다. 로그아웃 시 캐시를 지우는
 * 방식은 세션이 바뀌는 경로(로그인·로그아웃·다른 탭 로그아웃·비밀번호 변경 완료)마다 빠짐없이 지워야 해서 한 곳만 놓쳐도
 * 다시 새지만, 키에 신원을 넣으면 어느 경로로 바뀌든 A 의 항목은 B 에게 닿지 않는다.
 * 로그인 전(user 없음)에는 조회하지 않는다 — 누구의 권한인지 정할 수 없는 요청이다.
 */
export function useMyPermissions() {
  const { user, activeTenantId } = useAuth();
  const userId = user?.id ?? null;
  const query = useQuery({
    queryKey: ['auth', 'me', 'permissions', userId, activeTenantId],
    queryFn: () => authApi.myPermissions().then((r) => r.data),
    staleTime: 60_000,
    enabled: userId !== null,
  });
  const permissions = useMemo(() => new Set(query.data ?? []), [query.data]);
  return { permissions, isLoading: query.isLoading, isError: query.isError, refetch: query.refetch };
}
