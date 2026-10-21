import { useQuery } from '@tanstack/react-query';

import { usersApi } from '../../api/users';

/**
 * 워크스페이스 사용자 목록(GET /users, user:read).
 * options.enabled 로 조회를 끌 수 있다 — 감사 로그처럼 사용자 목록이 보조 기능인 화면이 user:read 없는 사람에게
 * 확실히 403 일 요청을 보내지 않게 한다(WD-121).
 */
export function useUsers(params: { search?: string; page?: number; size?: number }, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ['users', params],
    queryFn: () => usersApi.getUsers(params).then(r => r.data),
    enabled: options?.enabled ?? true,
  });
}
