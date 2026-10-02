import { useQuery } from '@tanstack/react-query';

import { authApi } from '../../api/auth';

/**
 * 공개 가입 열림 여부(WD-2). 사용자 0명(시스템 첫 사용자)일 때만 열린다.
 *
 * 조회 실패는 "닫힘"으로 다룬다 — 대부분의 시스템은 닫혀 있고, 실패 시 가입 폼을 보여 주면
 * 제출해도 403 만 받는 막다른 길이 된다. 재시도·캐시를 두지 않는 이유: 첫 사용자가 가입하는 순간
 * 바로 닫히므로 오래된 "열림" 을 들고 있으면 안 된다.
 */
export function useSignupStatus() {
  return useQuery({
    queryKey: ['auth', 'signup-status'],
    queryFn: () => authApi.signupStatus().then((r) => r.data),
    staleTime: 0,
    retry: false,
  });
}
