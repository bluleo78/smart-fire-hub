import { useQuery } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { tenantsApi } from '@/api/tenants';
import { PermissionDeniedBanner } from '@/components/PermissionDeniedBanner';
import { TenantStatusBadge } from '@/components/TenantStatusBadge';
import { Button } from '@/components/ui/button';
import { SearchInput } from '@/components/ui/search-input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { SimplePagination } from '@/components/ui/simple-pagination';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useAuth } from '@/hooks/useAuth';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { formatDateOnly } from '@/lib/formatters';
import { isForbidden } from '@/lib/http-errors';

const PAGE_SIZE = 20;

/**
 * 테넌트 목록.
 *
 * `GET /tenants` 는 쿼리 파라미터가 없고 배열을 통째로 준다 — 검색·상태 필터·페이징이 전부
 * 클라이언트인 것은 서버가 그렇게 생겼기 때문이다. 서버 페이징을 흉내 내면 두 번째 페이지가
 * 존재하지 않는 요청을 보내게 된다.
 *
 * 행 안에 인라인 정지 버튼을 두지 않는다(D-2): 행 전체가 상세 이동 액션이라 히트 타깃이 겹치고,
 * 무엇보다 `memberCount` 를 읽은 뒤 누르게 만드는 편이 안전하다.
 */
export default function TenantListPage() {
  const navigate = useNavigate();
  const { hasPermission } = useAuth();
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounceValue(search, 300);
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState(0);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['platform-tenants'],
    queryFn: () => tenantsApi.list().then((r) => r.data),
  });

  const filtered = useMemo(() => {
    const keyword = debouncedSearch.trim().toLowerCase();
    return (data ?? [])
      .filter((t) => (status === 'ALL' ? true : t.status === status))
      .filter(
        (t) =>
          keyword === '' ||
          t.name.toLowerCase().includes(keyword) ||
          t.slug.toLowerCase().includes(keyword),
      )
      // 최근 생성이 운영 관심사라 createdAt 내림차순이 기본이다.
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }, [data, debouncedSearch, status]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const pageRows = filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE);

  const resetSearch = () => {
    setSearch('');
    setPage(0);
  };

  // 403 은 토스트가 아니라 본문 교체다 — 재시도할 것이 없다.
  if (isError && isForbidden(error)) {
    return (
      <div className="space-y-6">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">테넌트</h1>
        <PermissionDeniedBanner />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">테넌트</h1>
        {hasPermission('platform:tenant:create') && (
          <Button asChild>
            <Link to="/tenants/new">
              <Plus className="h-4 w-4" />
              테넌트 생성
            </Link>
          </Button>
        )}
      </div>

      <div className="flex items-center gap-3">
        <SearchInput
          placeholder="이름 또는 slug로 검색..."
          value={search}
          onChange={(value) => {
            setSearch(value);
            setPage(0);
          }}
        />
        <Select
          value={status}
          onValueChange={(value) => {
            setStatus(value);
            setPage(0);
          }}
        >
          <SelectTrigger className="w-36" aria-label="상태 필터">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">전체</SelectItem>
            <SelectItem value="ACTIVE">활성</SelectItem>
            <SelectItem value="SUSPENDED">정지됨</SelectItem>
          </SelectContent>
        </Select>
        <span className="ml-auto text-sm text-muted-foreground tabular-nums">
          총 {filtered.length}개
        </span>
      </div>

      <div className="rounded-md border">
        <Table aria-label="테넌트 목록">
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>slug</TableHead>
              <TableHead>상태</TableHead>
              <TableHead className="text-right">멤버</TableHead>
              <TableHead>생성일</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={5} rows={5} />
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={5} className="text-center text-destructive">
                  데이터를 불러오는데 실패했습니다.
                </TableCell>
              </TableRow>
            ) : pageRows.length > 0 ? (
              pageRows.map((t) => (
                <TableRow
                  key={t.id}
                  tabIndex={0}
                  role="button"
                  aria-label={`테넌트 ${t.name} 상세 보기`}
                  className="cursor-pointer transition-colors hover:bg-muted/50"
                  onClick={() => navigate(`/tenants/${t.id}`)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') navigate(`/tenants/${t.id}`);
                  }}
                >
                  <TableCell className="font-medium">{t.name}</TableCell>
                  <TableCell className="font-mono text-[13px] text-muted-foreground">
                    {t.slug}
                  </TableCell>
                  <TableCell>
                    <TenantStatusBadge status={t.status} />
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{t.memberCount}</TableCell>
                  <TableCell>{formatDateOnly(t.createdAt)}</TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={5}
                message="등록된 테넌트가 없습니다."
                searchKeyword={debouncedSearch || undefined}
                onResetSearch={search ? resetSearch : undefined}
              />
            )}
          </TableBody>
        </Table>
      </div>

      <SimplePagination
        page={safePage}
        totalPages={totalPages}
        onPageChange={setPage}
        totalElements={filtered.length}
        pageSize={PAGE_SIZE}
      />
    </div>
  );
}
