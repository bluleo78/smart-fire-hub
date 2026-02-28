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
import { useUsers } from '../../hooks/queries/useUsers';

export default function UserListPage() {
  const navigate = useNavigate();
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
      <h1 className="text-2xl font-bold">사용자 관리</h1>

      <SearchInput
        placeholder="이름 또는 아이디로 검색..."
        value={search}
        onChange={handleSearchChange}
      />

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>아이디</TableHead>
              <TableHead>이메일</TableHead>
              <TableHead>상태</TableHead>
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
                  className="cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => navigate(`/admin/users/${u.id}`)}
                >
                  <TableCell>{u.name}</TableCell>
                  <TableCell className="font-medium">{u.username}</TableCell>
                  <TableCell>{u.email ?? '-'}</TableCell>
                  <TableCell>
                    <Badge variant={u.isActive ? 'default' : 'secondary'}>
                      {u.isActive ? '활성' : '비활성'}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={4} message="사용자가 없습니다." />
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
