import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { usersApi } from '../../api/users';
import { keepPreviousWhenOnlyPageChanged } from './keepPreviousWhenOnlyPageChanged';

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
    // WD-982: 쪽만 바뀐 조회는 이전 쪽 data 를 이동 중 유지한다 — 페이지네이션·초점이 사라지지 않게(감사 로그 사용자 필터는 쪽이 안 바뀌어 영향 없음)
    placeholderData: keepPreviousWhenOnlyPageChanged(params),
  });
}

/**
 * 워크스페이스 소유권 넘기기(POST /users/{id}/ownership, WD-1132).
 * 성공하면 옛 소유자·새 소유자의 membershipRole 이 모두 바뀌므로 사용자 목록 캐시(['users', params])를 무효화한다.
 * 사용자 상세 화면은 이 캐시를 쓰지 않고 usersApi 로 직접 읽으므로, 상세 갱신은 호출처가 따로 한다.
 * 성공 안내·오류 안내(서버 400/403/404/409 메시지)·상세 갱신은 mutate 콜백으로 호출처가 처리한다 —
 * 이 훅을 쓰는 컴포넌트가 확인 창처럼 먼저 사라지면 콜백이 버려지므로, 사라지지 않는 컴포넌트에서 호출해야 한다.
 */
export function useTransferOwnership() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, confirmName }: { id: number; confirmName: string }) =>
      usersApi.transferOwnership(id, confirmName),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  });
}
