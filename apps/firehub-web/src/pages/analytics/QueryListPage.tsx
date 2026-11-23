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
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { AnalyticsDeleteConfirmDialog } from '../../components/analytics/AnalyticsDeleteConfirmDialog';
import { WorkspaceAnalyticsTable } from '../../components/analytics/WorkspaceAnalyticsTable';
import { PageHeading } from '../../components/PageHeading';
import { TruncatedLink } from '../../components/TruncatedLink';
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
import { TableEmptyRow, TableErrorRow } from '../../components/ui/table-empty';
import { TableSkeletonRows } from '../../components/ui/table-skeleton';
import { Tabs, TabsControlsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import {
  hasFolder,
  useCanAdminAnalytics,
  useDeleteSavedQuery,
  useExecuteSavedQuery,
  useQueryFolders,
  useSavedQueries,
} from '../../hooks/queries/useAnalytics';
import { useIsOwner } from '../../hooks/useIsOwner';
import { useLastPageInfo } from '../../hooks/useLastPageInfo';
import { useListRetryFocus } from '../../hooks/useListRetryFocus';
import { offlineListMessage, useOfflinePaused } from '../../hooks/useOnlineStatus';
import { handleApiError, isForbiddenError, listLoadErrorProps } from '../../lib/api-error';
import { formatDateTimeMinute, formatRelativeTime } from '../../lib/formatters';
import { getPageAfterDelete } from '../../lib/pagination';
import { iGa } from '../../lib/utils';

/** 목록 보기 — 「워크스페이스 전체」는 analytics:admin 에게만 보인다(WD-199). */
type ListTab = 'mine' | 'shared' | 'workspace';

export default function QueryListPage() {
  // WD-985: 「검색 초기화」 뒤 초점이 body 로 떨어지지 않게 이 화면의 검색 입력으로 보낸다
  const searchInputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [folder, setFolder] = useState('');
  const [selectedTab, setSelectedTab] = useState<ListTab>('mine');
  // 관리자(analytics:admin)에게만 「워크스페이스 전체」 보기를 둔다 (WD-199) — 정지·제거된 작성자가 공유한 자산을 정리할 수 있게 한다.
  const { canAdmin } = useCanAdminAnalytics();
  // 관리자 권한이 사라져 「워크스페이스 전체」 탭이 없어지면 「내 …」로 되돌린다 — 없는 탭이 패널에 남지 않게(WD-514)
  const tab: ListTab = selectedTab === 'workspace' && !canAdmin ? 'mine' : selectedTab;
  const workspaceView = tab === 'workspace';
  const [page, setPage] = useState(0);
  const size = 10;

  const sharedOnly = tab === 'shared';
  // 「내 쿼리」 탭은 내가 만든 쿼리만 — 필터 없이 부르면 서버가 남의 공유 쿼리까지 섞어 준다(WD-328). 「공유됨」은 sharedOnly 그대로.
  const mineOnly = tab === 'mine';

  // 목록 훅과 쪽 수 기억(useLastPageInfo)이 같은 조회 조건 객체를 쓴다
  const queryParams = {
    search: search || undefined,
    folder: folder || undefined,
    sharedOnly: sharedOnly || undefined,
    mineOnly: mineOnly || undefined,
    page,
    size,
  };
  const {
    data: queriesData,
    isLoading,
    isPending,
    isFetching,
    isError,
    isPaused,
    isPlaceholderData,
    error: queriesError,
    refetch,
  } = useSavedQueries(queryParams);

  // 오프라인 멈춤 판정(공용 useOfflinePaused, WD-966·WD-982·WD-971): 받은 목록 없음 또는 쪽 이동 placeholder + paused 가 실제 오프라인이면 offline(안내),
  // 데이터 없이 온라인인데 paused(탭 숨김으로 재시도 멈춤)면 waiting(스켈레톤). offline 이면 흐림·inert 없이 안내를 보인다
  const { waiting, offline } = useOfflinePaused({ data: queriesData, isPaused, isPlaceholderData });

  // 「다시 시도」 포커스(WD-987): 실패한 목록에서 누르면 표로 초점을 옮겼다가 다시 실패하면 버튼으로 되돌린다.
  // 403 은 다시 해도 같으므로 버튼을 두지 않는다
  const { tableProps, bodyProps, retryAction } = useListRetryFocus({
    isPending,
    // WD-982: 쪽 이동 중 이전 쪽 행 처리(pending 판정·흐림·inert)는 훅이 맡는다
    // 오프라인 쪽 이동 대기는 흐림·inert 없이 안내를 보인다(WD-982, useOfflinePaused)
    isPlaceholderData: isPlaceholderData && !offline,
    isError,
    isFetching,
    data: queriesData,
    error: queriesError,
    refetch,
  });

  // 폴더 드롭다운도 목록과 같은 탭 범위 — 「내 쿼리」에서 남의 공유 쿼리에만 있는 폴더를 고르면 늘 빈 결과가 된다(WD-328)
  const {
    data: foldersData,
    isError: isFoldersError,
    error: foldersError,
  } = useQueryFolders({
    sharedOnly: sharedOnly || undefined,
    mineOnly: mineOnly || undefined,
  });
  const deleteQuery = useDeleteSavedQuery();
  // 남이 공유한 쿼리 줄에서는 편집·삭제를 숨긴다 — 서버가 작성자 외 요청을 404 로 거절한다(WD-196). 실행·줄 클릭(보기)은 그대로.
  const isOwner = useIsOwner();
  const executeSavedQuery = useExecuteSavedQuery();

  const queries = queriesData?.content ?? [];
  const { totalPages, totalElements } = useLastPageInfo(queriesData, queryParams);
  // 빈/공백 폴더는 useQueryFolders 가 이미 걸러 준다 (WD-889)
  const folders = foldersData ?? [];
  // 폴더 목록 실패 안내 문구 (WD-990) — 실패(데이터 없음)일 때만 값이 있다. 403 은 다시 시도해도 소용없으므로 권한 문구로 구분
  const folderErrorText =
    isFoldersError && !foldersData
      ? isForbiddenError(foldersError)
        ? '폴더 목록을 볼 권한이 없습니다'
        : '폴더 목록을 불러오지 못했습니다'
      : null;

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
        <PageHeading>저장된 쿼리</PageHeading>
        <Button onClick={() => navigate('/analytics/queries/new')}>
          <Plus className="h-4 w-4" />
          새 쿼리
        </Button>
      </div>

      {/* Tabs */}
      {/* 필터형 탭 — 목록을 TabsControlsContent 로 감싸 선택 탭이 실제 패널을 가리킨다(WD-514, ui/tabs.tsx). gap-6 은 이전 space-y-6 유지 */}
      <Tabs
        className="gap-6"
        value={tab}
        onValueChange={(v) => {
          setSelectedTab(v as ListTab);
          setPage(0);
          setFolder('');
          setSearch('');
        }}
      >
        {/* WD-890: 탭이 24~29px 로 낮아 누르기 빠듯했다 — 목록 높이를 h-10 으로 올리고 모든 탭(조건부 「워크스페이스 전체」 포함)에
            min-h-8(32px) 하한을 목록에서 한 번에 준다(대시보드 목록 WD-877 과 같은 방식) */}
        <TabsList className="group-data-[orientation=horizontal]/tabs:h-10 [&>[role=tab]]:min-h-8">
          <TabsTrigger value="mine">내 쿼리</TabsTrigger>
          <TabsTrigger value="shared">공유됨</TabsTrigger>
          {canAdmin && <TabsTrigger value="workspace">워크스페이스 전체</TabsTrigger>}
        </TabsList>

        <TabsControlsContent value={tab} className="space-y-6">
          {workspaceView ? (
            <WorkspaceAnalyticsTable kind="queries" />
          ) : (
            <>
              {/* Filters */}
              <div className="flex items-center gap-3 flex-wrap">
                <SearchInput
                  placeholder="쿼리 검색..."
                  inputRef={searchInputRef}
                  // 입력이 멈춘 뒤 한 번만 검색한다(WD-513) — 글자마다 목록 요청·페이지 초기화가 나가지 않게
                  debounceMs={300}
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
                  {/* 값을 고른 뒤에도 무엇을 거르는 상자인지 읽히게 접근 이름을 붙인다 (WD-398) */}
                  <SelectTrigger
                    className="w-[180px]"
                    aria-label="폴더 필터"
                    aria-describedby={folderErrorText ? 'query-folder-error' : undefined}
                  >
                    <SelectValue placeholder="전체 폴더" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__all__">전체 폴더</SelectItem>
                    {folders.map((f) => (
                      <SelectItem key={f} value={f}>
                        {f}
                      </SelectItem>
                    ))}
                    {/* 폴더 목록 조회 실패를 「폴더 없음」과 구분한다 — 실패해도 쿼리 목록·「전체 폴더」는 그대로 쓰고, 고를 수 없는 안내만 더한다 (WD-978 리뷰 후속).
                        WD-990: SelectItem(role=option)·value 로 두면 폴더 이름과 값이 겹칠 수 있고 「고를 수 있는 항목」으로 읽혀서, 항목이 아닌 div 로 둔다.
                        403(권한 없음)은 일시 실패와 달리 다시 시도해도 소용없으므로 권한 문구로 구분한다. */}
                    {folderErrorText && (
                      <div aria-hidden="true" className="px-2 py-1.5 text-sm text-muted-foreground">
                        {folderErrorText}
                      </div>
                    )}
                  </SelectContent>
                </Select>
                {/* WD-990 리뷰: 드롭다운 안 안내는 열기 전·listbox 밖이라 낭독되지 않아, 같은 문구를 화면 밖 status 로 두고 트리거 설명에 잇는다 */}
                {/* WD-990 리뷰 후속: 글자를 담은 채 처음 붙는 status 는 낭독되지 않아, 빈 span 을 늘 두고 글자만 바꾼다 */}
                <span id="query-folder-error" role="status" className="sr-only">
                  {folderErrorText}
                </span>
              </div>

              {/* Table */}
              <div className="rounded-md border">
                <Table
                  aria-label="저장된 쿼리 목록"
                  {...tableProps}
                >
                  <TableHeader>
                    <TableRow>
                      <TableHead>이름</TableHead>
                      <TableHead>폴더</TableHead>
                      <TableHead>데이터셋</TableHead>
                      <TableHead>차트</TableHead>
                      <TableHead>수정일</TableHead>
                      {/* WD-890: 버튼 3개(32×3) + 간격 4×2 + 셀 좌우 여백 16 = 120px. 빈 머리는 스크린리더에 이름 없는 열로 읽혀 sr-only 「작업」을 준다 */}
                      <TableHead className="w-[120px]">
                        <span className="sr-only">작업</span>
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <tbody {...bodyProps}>
                    {isLoading || waiting ? (
                      <TableSkeletonRows columns={6} rows={5} />
                    ) : offline ? (
                      // 오프라인 쪽 이동 대기는 흐림·inert 없이 안내를 보인다(WD-982, useOfflinePaused)
                      <TableEmptyRow colSpan={6} message={offlineListMessage('쿼리')} />
                    ) : isError && !queriesData ? (
                      // 목록 조회 실패 — 0건 안내로 오해되지 않게 실패 안내를 보인다. 받아 둔 목록이 있으면 재조회 실패여도 유지한다 (WD-978)
                      <TableErrorRow
                        colSpan={6}
                        // 403 은 권한 안내(흐린 톤), 그 밖은 서버 사유 — 권한 문제를 일시 장애처럼 보이지 않게 한다 (WD-978 리뷰 후속)
                        {...listLoadErrorProps(queriesError, '쿼리')}
                        action={retryAction}
                      />
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
                              {/* WD-933: 이름은 상세 링크라 포커스 링(ring-2)이 overflow-hidden 에 잘린다 — 안쪽 여유(p)와 바깥 음수 여백(-m)을 같은 값으로 두어 레이아웃은 그대로 둔다.
                                  WD-976: 고대비(forced-colors)에서는 outline-hidden 이 `outline: 2px solid transparent; outline-offset: 2px` 로 바뀌어 링크 밖 4px 까지 그려진다 → 여유를 2px 에서 4px(p-1 -m-1)로 늘린다 */}
                              <div className="min-w-0 overflow-hidden p-1 -m-1">
                                <div className="flex items-center gap-1.5 min-w-0">
                                  {/* 긴 쿼리 이름이 테이블 레이아웃을 깨지 않도록 truncate + max-w 적용. WD-890: xl 미만(사이드바 펼친 1024 포함)에서 표가 넘치지 않게 이름·설명 상한을 줄임 —
                                      잘린 글자는 title 로 전체를 보임 */}
                                  {/* WD-933: 행 클릭만으로는 키보드 경로가 없어(남이 공유한 쿼리는 편집 버튼도 없다) 이름을 상세 링크로 둔다. title·말줄임은 TruncatedLink 가 한다 */}
                                  <TruncatedLink
                                    to={`/analytics/queries/${query.id}`}
                                    value={query.name}
                                    className="block max-w-[14rem] xl:max-w-[300px]"
                                  />
                                  {query.isShared && (
                                    <Badge variant="secondary" className="text-xs gap-1 py-0">
                                      <Share2 className="h-2.5 w-2.5" />
                                      공유
                                    </Badge>
                                  )}
                                </div>
                                {query.description && (
                                  <p
                                    className="text-xs text-muted-foreground truncate max-w-[14rem] xl:max-w-xs mt-0.5"
                                    title={query.description}
                                  >
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
                            {/* WD-889: 옛 공백 폴더는 빈 폴더 표시 대신 「-」로 */}
                            {hasFolder(query.folder) ? (
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
                            {/* 행 작업 버튼 — 마우스를 올리거나 키보드 포커스가 안에 들어오면 보인다(WD-412: Tab 으로 와도 보이지 않던 문제).
                                WD-890: 28px(h-7 w-7)였던 버튼을 icon-sm(32px)으로 키운다. 아이콘은 size- 클래스가 없으면 Button 이 16px 로 덮으므로 size-3.5(14px)를 준다 */}
                            <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                              <Button
                                variant="ghost"
                                size="icon-sm"
                                title="실행"
                                aria-label="실행"
                                onClick={(e) => handleRun(e, query.id, query.name)}
                              >
                                <Play className="size-3.5" />
                              </Button>
                              {isOwner(query.createdBy) && (
                                <>
                                  <Button
                                    variant="ghost"
                                    size="icon-sm"
                                    title="편집"
                                    aria-label="편집"
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      navigate(`/analytics/queries/${query.id}`);
                                    }}
                                  >
                                    <Pencil className="size-3.5" />
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
                                        size="icon-sm"
                                        aria-label="삭제"
                                        onClick={(e) => e.stopPropagation()}
                                      >
                                        <Trash2 className="size-3.5" />
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
                        resetFocusRef={searchInputRef}
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
        </TabsControlsContent>
      </Tabs>
    </div>
  );
}
