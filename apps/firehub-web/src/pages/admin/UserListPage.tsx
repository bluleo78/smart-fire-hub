import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { SearchInput } from '@/components/ui/search-input';
import { SimplePagination } from '@/components/ui/simple-pagination';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useDebounceValue } from '@/hooks/useDebounceValue';

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
import { AddMemberDialog } from './components/AddMemberDialog';

export default function UserListPage() {
  const navigate = useNavigate();
  // 멤버 추가 버튼 노출(user:write)·역할 지정 가능 여부(role:assign) — 최종 판정은 서버(WD-2).
  const { permissions } = useMyPermissions();
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounceValue(search, 300);
  const [page, setPage] = useState(0);
  const pageSize = 10;

  const handleSearchChange = (value: string) => {
    setSearch(value);
    setPage(0);
  };

  const { data: users, isLoading, isError } = useUsers({
    search: debouncedSearch || undefined,
    page,
    size: pageSize,
  });

  return (
    <div className="space-y-6">
      {/* 제목 행 오른쪽 주 액션 — RoleListPage 와 같은 배치(와이어프레임 ①). user:write 보유 시만. */}
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">사용자 관리</h1>
        {permissions.has('user:write') && <AddMemberDialog canAssignRoles={permissions.has('role:assign')} />}
      </div>

      <SearchInput
        placeholder="이름 또는 아이디로 검색..."
        value={search}
        onChange={handleSearchChange}
      />

      <div className="rounded-md border">
        <Table aria-label="사용자 목록">
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>아이디</TableHead>
              <TableHead>이메일</TableHead>
              <TableHead>상태 (이 워크스페이스)</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={4} rows={5} />
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={4} className="text-center text-destructive">
                  데이터를 불러오는데 실패했습니다.
                </TableCell>
              </TableRow>
            ) : users && users.content.length > 0 ? (
              users.content.map((u) => (
                <TableRow
                  key={u.id}
                  // 키보드 접근성: Tab 포커스 가능하도록 tabIndex={0}, role="button" 추가
                  tabIndex={0}
                  role="button"
                  aria-label={`사용자 ${u.name} 상세 보기`}
                  className="cursor-pointer hover:bg-muted/50 transition-colors row-hover"
                  onClick={() => navigate(`/admin/users/${u.id}`)}
                  // Enter/Space 키로 행 클릭과 동일한 네비게이션 동작 수행
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') navigate(`/admin/users/${u.id}`); }}
                >
                  <TableCell>
                    <span className="inline-flex items-center gap-2">
                      {u.name}
                      {u.membershipRole === 'OWNER' && <Badge variant="outline">OWNER</Badge>}
                    </span>
                  </TableCell>
                  <TableCell className="font-medium">{u.username}</TableCell>
                  <TableCell>{u.email ?? '-'}</TableCell>
                  <TableCell>
                    {/* 이 워크스페이스 멤버십 상태(전역 계정 아님, WD-2). 색만으로 구분하지 않도록 문구가 다르다. */}
                    <Badge variant={u.isActive ? 'success' : 'secondary'}>{u.isActive ? '활성' : '정지'}</Badge>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={4}
                message="사용자가 없습니다."
                searchKeyword={debouncedSearch || undefined}
                onResetSearch={search ? () => { setSearch(''); setPage(0); } : undefined}
              />
            )}
          </TableBody>
        </Table>
      </div>

      {users && (
        <SimplePagination
          page={page}
          totalPages={users.totalPages}
          onPageChange={setPage}
          totalElements={users.totalElements}
          pageSize={pageSize}
        />
      )}
    </div>
  );
}
