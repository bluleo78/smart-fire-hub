import { useCallback, useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { PageHeading } from '@/components/PageHeading';
import { TruncatedLink } from '@/components/TruncatedLink';
import { SearchInput } from '@/components/ui/search-input';
import { SimplePagination } from '@/components/ui/simple-pagination';
import { TableEmptyRow, TableErrorRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { TruncatedText } from '@/components/ui/truncated-text';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { useLastPageInfo } from '@/hooks/useLastPageInfo';
import { membershipRoleLabel } from '@/lib/membership-role';
import { parseNonNegativeInt } from '@/lib/pagination';

import { Badge } from '../../components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { useMyPermissions } from '../../hooks/queries/useMyPermissions';
import { useUsers } from '../../hooks/queries/useUsers';
import { AccountDisabledBadge } from './components/AccountDisabledBadge';
import { AddMemberDialog } from './components/AddMemberDialog';

export default function UserListPage() {
  // WD-985: 「검색 초기화」 뒤 초점이 body 로 떨어지지 않게 이 화면의 검색 입력으로 보낸다
  const searchInputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  // 멤버 추가 버튼 노출(user:write)·역할 지정 가능 여부(role:assign) — 최종 판정은 서버(WD-2).
  const { permissions } = useMyPermissions();
  // 페이지·검색어는 주소 쿼리(?page=·?q=)를 단일 진실 소스로 둔다 — 상세에 다녀와도 보던 위치로 돌아오게(WD-283).
  // 데이터셋 목록과 같은 규칙: 입력칸도 주소에 바로 묶고(로컬 상태와 어긋나지 않게), 조회만 디바운스한다.
  const [searchParams, setSearchParams] = useSearchParams();
  const search = searchParams.get('q') ?? '';
  // 손으로 고친 주소(?page=abc, -1)는 첫 페이지로 읽는다(WD-146 과 같은 처리)
  const page = parseNonNegativeInt(searchParams.get('page'), 0);
  const debouncedSearch = useDebounceValue(search, 300);
  const pageSize = 10;

  /** 주소 쿼리 갱신 — 빈 값·첫 페이지는 키를 지워 주소를 깔끔히 두고, 기록을 쌓지 않게 replace 한다. */
  const patchParams = useCallback(
    (patch: { q?: string; page?: number }) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [key, value] of Object.entries(patch)) {
            if (value === '' || value === 0) next.delete(key);
            else if (value !== undefined) next.set(key, String(value));
          }
          return next;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );
  const setPage = (next: number) => patchParams({ page: next });

  const handleSearchChange = (value: string) => {
    patchParams({ q: value, page: 0 });
  };

  /**
   * 상세로 갈 때 넘길 router state — 지금 목록 주소(page·q)를 실어 상세의 「목록으로 돌아가기」가 이 위치로 돌아오게 한다(WD-283).
   * 행 클릭(openUser)과 이름 링크(WD-816)가 같은 값을 쓰도록 한 곳에서 만든다.
   */
  const listState = { listSearch: `?${searchParams.toString()}` };
  const openUser = (id: number) => navigate(`/admin/users/${id}`, { state: listState });

  // 목록 훅과 쪽 수 기억(useLastPageInfo)이 같은 조회 조건 객체를 쓴다
  const userParams = {
    search: debouncedSearch || undefined,
    page,
    size: pageSize,
  };
  const { data: users, isLoading, isError, isPlaceholderData } = useUsers(userParams);

  const { totalPages, totalElements } = useLastPageInfo(users, userParams);

  // 범위 밖 페이지(북마크·멤버가 줄어든 뒤 돌아옴)는 빈 목록을 「사용자가 없습니다」로 오해하지 않게 마지막 페이지로 옮긴다(데이터셋 목록 WD-146 과 같은 처리).
  // WD-982: placeholder(쪽 이동 중 이전 쪽 data)의 totalPages 로는 판정하지 않는다 — 실제 응답이 와야 판정한다
  const pageOutOfRange = !!users && !isPlaceholderData && page > 0 && page >= Math.max(users.totalPages, 1);
  useEffect(() => {
    if (!pageOutOfRange || !users) return;
    patchParams({ page: Math.max(users.totalPages - 1, 0) });
  }, [pageOutOfRange, users, patchParams]);

  return (
    <div className="space-y-6">
      {/* 제목 행 오른쪽 주 액션 — RoleListPage 와 같은 배치(와이어프레임 ①). user:write 보유 시만. */}
      <div className="flex items-center justify-between">
        <PageHeading>사용자 관리</PageHeading>
        {/* 역할 지정은 role:assign 에 더해 고를 역할 목록(GET /roles = role:read)도 읽을 수 있어야 한다(WD-121). */}
        {permissions.has('user:write') && (
          <AddMemberDialog
            canAssignRoles={permissions.has('role:assign') && permissions.has('role:read')}
            canReadRoles={permissions.has('role:read')}
          />
        )}
      </div>

      <SearchInput
        placeholder="이름 또는 아이디로 검색..."
        inputRef={searchInputRef}
        value={search}
        onChange={handleSearchChange}
      />

      {/* WD-634: 긴 값 상한 = (표 폭 100cqw − --k) × 몫. --k = 짧은 칸 선언 폭(상태) + 긴 값 칸 여백 */}
      <div className="@container [--k:calc(6rem_+_3rem)] rounded-md border">
        {/* WD-982: 쪽 이동 중 이전 행은 흐리게 하고 누를 수 없게 한다(감사 로그와 같은 방식) */}
        <Table
          aria-label="사용자 목록"
          aria-busy={isPlaceholderData || undefined}
          className={isPlaceholderData ? 'pointer-events-none opacity-60' : undefined}
        >
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>아이디</TableHead>
              <TableHead>이메일</TableHead>
              <TableHead className="w-[6rem]">상태</TableHead>
            </TableRow>
          </TableHeader>
          {/* inert: 이동 중 낡은 행을 키보드로도 열 수 없게 한다 (WD-982) */}
          <TableBody inert={isPlaceholderData}>
            {isLoading || pageOutOfRange ? (
              <TableSkeletonRows columns={4} rows={5} />
            ) : isError ? (
              <TableErrorRow colSpan={4} />
            ) : users && users.content.length > 0 ? (
              users.content.map((u) => (
                <TableRow
                  key={u.id}
                  // 행은 표 행 의미(열 머리글 연동)를 지키려고 button 역할·tabIndex 를 주지 않는다(WD-816).
                  // 키보드·보조기기 진입점은 이름 칸의 링크이고, 행 onClick 은 마우스 편의로만 남긴다.
                  className="cursor-pointer hover:bg-muted/50 transition-colors row-hover"
                  onClick={() => openUser(u.id)}
                >
                  {/* WD-15: 주 식별자(이름)만 굵게 — 보조 식별자(아이디)가 더 굵으면 시선이 역전된다(02 §4.1).
                      역할 라벨은 공용 매핑(lib/membership-role)으로 admin 콘솔과 같은 용어 "소유자". */}
                  {/* WD-559·WD-634: 긴 값은 표 컨테이너 비례 상한(cqw)으로 말줄임 — 이유는 TruncatedText 설명. 소유자 배지는 줄지 않게 둔다 */}
                  <TableCell className="font-medium">
                    {/* 상한은 이름+소유자 배지 묶음에 건다 — 배지가 붙어도 칸 몫을 넘지 않고, 이름(min-w-0)만 줄어든다 */}
                    <span className="inline-flex max-w-[min(16rem,calc((100cqw_-_var(--k))_*_0.26))] items-center gap-2">
                      <TruncatedLink to={`/admin/users/${u.id}`} state={listState} value={u.name} className="min-w-0" />
                      {u.membershipRole === 'OWNER' && <Badge variant="outline" className="shrink-0">{membershipRoleLabel('OWNER')}</Badge>}
                    </span>
                  </TableCell>
                  <TableCell>
                    <TruncatedText value={u.username} className="max-w-[min(14rem,calc((100cqw_-_var(--k))_*_0.22))]" />
                  </TableCell>
                  <TableCell>
                    <TruncatedText value={u.email} className="max-w-[min(24rem,calc((100cqw_-_var(--k))_*_0.49))]" />
                  </TableCell>
                  <TableCell>
                    {/* 이 워크스페이스 멤버십 상태(전역 계정 아님, WD-2). 색만으로 구분하지 않도록 문구가 다르다.
                        전역 계정이 운영자에게 비활성화됐으면 옆에 계정 배지를 붙인다(WD-3).
                        WD-634: 배지가 둘이면 줄바꿈(flex-wrap)해 칸이 선언 폭(--k 에 포함)을 넘지 않게 한다. */}
                    <span className="inline-flex flex-wrap items-center gap-2">
                      <Badge variant={u.isActive ? 'success' : 'secondary'} title="이 워크스페이스 멤버십">{u.isActive ? '활성' : '정지'}</Badge>
                      {u.accountActive === false && <AccountDisabledBadge />}
                    </span>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={4}
                message="사용자가 없습니다."
                resetFocusRef={searchInputRef}
                searchKeyword={debouncedSearch || undefined}
                onResetSearch={search ? () => patchParams({ q: '', page: 0 }) : undefined}
              />
            )}
          </TableBody>
        </Table>
      </div>

      {/* WD-982: 늘 그린다 — 쪽 이동 중·실패 중에도 이전 쪽으로 돌아갈 수 있게. 쪽 수가 1 이하면 컴포넌트가 스스로 숨긴다 */}
      <SimplePagination
        page={page}
        totalPages={totalPages}
        onPageChange={setPage}
        totalElements={totalElements}
        pageSize={pageSize}
      />
    </div>
  );
}
