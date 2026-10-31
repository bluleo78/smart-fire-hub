import {
  BarChart2,
  FileText,
  Folder,
  Lock,
  Pencil,
  Play,
  Plus,
  Share2,
  Trash2,
} from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { AnalyticsDeleteConfirmDialog } from '../../components/analytics/AnalyticsDeleteConfirmDialog';
import { WorkspaceAnalyticsTable } from '../../components/analytics/WorkspaceAnalyticsTable';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { SearchInput } from '../../components/ui/search-input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { SimplePagination } from '../../components/ui/simple-pagination';
import {
  Table,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { TableEmptyRow } from '../../components/ui/table-empty';
import { TableSkeletonRows } from '../../components/ui/table-skeleton';
import { Tabs, TabsList, TabsTrigger } from '../../components/ui/tabs';
import {
  useCanAdminAnalytics,
  useDeleteSavedQuery,
  useExecuteSavedQuery,
  useQueryFolders,
  useSavedQueries,
} from '../../hooks/queries/useAnalytics';
import { useIsOwner } from '../../hooks/useIsOwner';
import { handleApiError } from '../../lib/api-error';
import { formatDateTimeMinute, formatRelativeTime } from '../../lib/formatters';
import { getPageAfterDelete } from '../../lib/pagination';
import { iGa } from '../../lib/utils';

/** 목록 보기 — 「워크스페이스 전체」는 analytics:admin 에게만 보인다(WD-199). */
type ListTab = 'mine' | 'shared' | 'workspace';

export default function QueryListPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [folder, setFolder] = useState('');
  const [tab, setTab] = useState<ListTab>('mine');
  // 관리자(analytics:admin)에게만 「워크스페이스 전체」 보기를 둔다 (WD-199) — 정지·제거된 작성자가 공유한 자산을 정리할 수 있게 한다.
  const { canAdmin } = useCanAdminAnalytics();
  const workspaceView = canAdmin && tab === 'workspace';
  const [page, setPage] = useState(0);
  const size = 10;

  const sharedOnly = tab === 'shared';
  // 「내 쿼리」 탭은 내가 만든 쿼리만 — 필터 없이 부르면 서버가 남의 공유 쿼리까지 섞어 준다(WD-328). 「공유됨」은 sharedOnly 그대로.
  const mineOnly = tab === 'mine';

  const { data: queriesData, isLoading } = useSavedQueries({
    search: search || undefined,
    folder: folder || undefined,
    sharedOnly: sharedOnly || undefined,
    mineOnly: mineOnly || undefined,
    page,
    size,
  });

  // 폴더 드롭다운도 목록과 같은 탭 범위 — 「내 쿼리」에서 남의 공유 쿼리에만 있는 폴더를 고르면 늘 빈 결과가 된다(WD-328)
  const { data: foldersData } = useQueryFolders({
    sharedOnly: sharedOnly || undefined,
    mineOnly: mineOnly || undefined,
  });
  const deleteQuery = useDeleteSavedQuery();
  // 남이 공유한 쿼리 줄에서는 편집·삭제를 숨긴다 — 서버가 작성자 외 요청을 404 로 거절한다(WD-196). 실행·줄 클릭(보기)은 그대로.
  const isOwner = useIsOwner();
  const executeSavedQuery = useExecuteSavedQuery();

  const queries = queriesData?.content ?? [];
  const totalPages = queriesData?.totalPages ?? 0;
  const totalElements = queriesData?.totalElements;
  const folders = foldersData ?? [];

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteQuery.mutateAsync(id);
      // 현재 페이지의 마지막 항목을 삭제한 경우, 페이지를 앞으로 보정해
      // "검색 결과 없음" 오표시(#549)를 방지한다.
      setPage((prev) => getPageAfterDelete(queries.length, prev));
      toast.success(`쿼리 "${name}"${iGa(name)} 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '쿼리 삭제에 실패했습니다.');
    }
  };

  const handleRun = async (e: React.MouseEvent, id: number, name: string) => {
    e.stopPropagation();
    try {
      // 이슈 #569: 목록에서 실행한 결과(rows/columns/소요시간)를 버리지 않고
      // navigate의 location.state로 편집기에 전달한다.
      // QueryEditorPage는 이 state가 있으면 재실행 없이 결과 패널을 바로 렌더링한다.
      const executionResult = await executeSavedQuery.mutateAsync(id);
      toast.success(`쿼리 "${name}" 실행 완료`);
      navigate(`/analytics/queries/${id}`, { state: { executionResult } });
    } catch (error) {
      handleApiError(error, '쿼리 실행에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">저장된 쿼리</h1>
        <Button onClick={() => navigate('/analytics/queries/new')}>
          <Plus className="h-4 w-4" />
          새 쿼리
        </Button>
      </div>

      {/* Tabs */}
      <Tabs
        value={tab}
        onValueChange={(v) => {
          setTab(v as ListTab);
          setPage(0);
          setFolder('');
          setSearch('');
        }}
      >
        <TabsList>
          <TabsTrigger value="mine">내 쿼리</TabsTrigger>
          <TabsTrigger value="shared">공유됨</TabsTrigger>
          {canAdmin && <TabsTrigger value="workspace">워크스페이스 전체</TabsTrigger>}
        </TabsList>
      </Tabs>

      {workspaceView ? (
        <WorkspaceAnalyticsTable kind="queries" />
      ) : (
        <>
          {/* Filters */}
          <div className="flex items-center gap-3 flex-wrap">
            <SearchInput
              placeholder="쿼리 검색..."
              value={search}
              onChange={(value) => {
                setSearch(value);
                setPage(0);
              }}
            />
            <Select
              value={folder || '__all__'}
              onValueChange={(value) => {
                setFolder(value === '__all__' ? '' : value);
                setPage(0);
              }}
            >
              <SelectTrigger className="w-[180px]">
                <SelectValue placeholder="전체 폴더" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__all__">전체 폴더</SelectItem>
                {folders.map((f) => (
                  <SelectItem key={f} value={f}>
                    {f}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Table */}
          <div className="rounded-md border">
            <Table aria-label="저장된 쿼리 목록">
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>폴더</TableHead>
                  <TableHead>데이터셋</TableHead>
                  <TableHead>차트</TableHead>
                  <TableHead>수정일</TableHead>
                  <TableHead className="w-[100px]" />
                </TableRow>
              </TableHeader>
              <tbody>
                {isLoading ? (
                  <TableSkeletonRows columns={6} rows={5} />
                ) : queries.length > 0 ? (
                  queries.map((query) => (
                    <TableRow
                      key={query.id}
                      className="cursor-pointer hover:bg-muted/50 transition-colors group row-hover"
                      onClick={() => navigate(`/analytics/queries/${query.id}`)}
                    >
                      <TableCell className="font-medium">
                        <div className="flex items-center gap-2 min-w-0">
                          <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
                          {/* 이름 셀 내부: 긴 텍스트가 truncate되도록 min-w-0 + overflow-hidden 적용 */}
                          <div className="min-w-0 overflow-hidden">
                            <div className="flex items-center gap-1.5 min-w-0">
                              {/* 긴 쿼리 이름이 테이블 레이아웃을 깨지 않도록 truncate + max-w 적용 */}
                              <span className="truncate max-w-[300px] block">{query.name}</span>
                              {query.isShared && (
                                <Badge variant="secondary" className="text-xs gap-1 py-0">
                                  <Share2 className="h-2.5 w-2.5" />
                                  공유
                                </Badge>
                              )}
                            </div>
                            {query.description && (
                              <p className="text-xs text-muted-foreground truncate max-w-xs mt-0.5">
                                {query.description}
                              </p>
                            )}
                            <p className="text-xs text-muted-foreground mt-0.5">
                              {query.createdByName}
                            </p>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        {query.folder ? (
                          <div className="flex items-center gap-1 text-sm text-muted-foreground">
                            <Folder className="h-3.5 w-3.5" />
                            {query.folder}
                          </div>
                        ) : (
                          <span className="text-muted-foreground text-sm">-</span>
                        )}
                      </TableCell>
                      <TableCell>
                        {query.datasetName ? (
                          <Badge variant="outline" className="text-xs">
                            {query.datasetName}
                          </Badge>
                        ) : query.datasetId != null ? (
                          // 연결 데이터셋은 있지만 조회자가 볼 수 없다(보안 등급 — 서버가 이름만 null 로 준다). 권한 부족은 오류가 아니므로
                          // 위젯 잠금 상태와 같은 아이콘의 차분한 표시만 한다(오류색·토스트 금지). 문구는 위젯(WD-143 「원본 데이터를 볼 수 없음」)과 달리
                          // "권한 없음"으로 단정한다 — 여기는 연결 데이터셋이 존재함을 안다. 삭제된 데이터셋은 서버가 datasetId 를
                          // null 로 만들어(ON DELETE SET NULL) 아래 「연결 없음」으로 간다.
                          <span
                            className="flex items-center gap-1 whitespace-nowrap text-sm text-muted-foreground"
                            data-testid="query-dataset-restricted"
                          >
                            <Lock className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                            열람 권한 없음
                          </span>
                        ) : (
                          // 연결 데이터셋 없음(편집기로 저장한 쿼리 대부분) — 내부 용어 대신 같은 표의 다른 빈 칸(폴더·차트)과 같은 "-" (WD-90)
                          <span className="text-muted-foreground text-sm" data-testid="query-dataset-none">
                            -
                          </span>
                        )}
                      </TableCell>
                      <TableCell>
                        {query.chartCount > 0 ? (
                          <div className="flex items-center gap-1 text-sm text-muted-foreground tabular-nums">
                            <BarChart2 className="h-3.5 w-3.5" />
                            {query.chartCount}개
                          </div>
                        ) : (
                          <span className="text-muted-foreground text-sm">-</span>
                        )}
                      </TableCell>
                      <TableCell>
                        {/* 이슈 #108: 상대시간 표시 + hover 시 절대시간(YYYY-MM-DD HH:mm) 툴팁 */}
                        <span
                          className="text-sm text-muted-foreground"
                          title={formatDateTimeMinute(query.updatedAt)}
                        >
                          {formatRelativeTime(query.updatedAt)}
                        </span>
                      </TableCell>
                      <TableCell>
                        {/* Hover action buttons */}
                        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-7 w-7 p-0"
                            title="실행"
                            aria-label="실행"
                            onClick={(e) => handleRun(e, query.id, query.name)}
                          >
                            <Play size={13} />
                          </Button>
                          {isOwner(query.createdBy) && (
                            <>
                              <Button
                                variant="ghost"
                                size="sm"
                                className="h-7 w-7 p-0"
                                title="편집"
                                aria-label="편집"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  navigate(`/analytics/queries/${query.id}`);
                                }}
                              >
                                <Pencil size={13} />
                              </Button>
                              {/* WD-86: 함께 삭제되는 항목(CASCADE)을 창을 열 때 조회해 보여 준다 */}
                              <AnalyticsDeleteConfirmDialog
                                kind="query"
                                id={query.id}
                                itemName={query.name}
                                onConfirm={() => handleDelete(query.id, query.name)}
                                trigger={
                                  <Button
                                    variant="ghost"
                                    size="sm"
                                    className="h-7 w-7 p-0"
                                    aria-label="삭제"
                                    onClick={(e) => e.stopPropagation()}
                                  >
                                    <Trash2 className="h-3.5 w-3.5" />
                                  </Button>
                                }
                              />
                            </>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  ))
                ) : (
                  <TableEmptyRow
                    colSpan={6}
                    message="저장된 쿼리가 없습니다."
                    searchKeyword={search || undefined}
                    onResetSearch={
                      search || folder
                        ? () => { setSearch(''); setFolder(''); setPage(0); }
                        : undefined
                    }
                    emptyAction={
                      !search && !folder ? (
                        <Button size="sm" variant="outline" onClick={() => navigate('/analytics/queries/new')}>
                          <Plus className="h-4 w-4" />
                          새 쿼리 만들기
                        </Button>
                      ) : undefined
                    }
                  />
                )}
              </tbody>
            </Table>
          </div>

          <SimplePagination
            page={page}
            totalPages={totalPages}
            onPageChange={setPage}
            totalElements={totalElements}
            pageSize={size}
          />
        </>
      )}
    </div>
  );
}
