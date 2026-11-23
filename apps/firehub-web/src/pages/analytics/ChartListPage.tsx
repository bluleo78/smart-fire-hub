import {
  BarChart2,
  Pencil,
  Plus,
  Share2,
  Trash2,
} from 'lucide-react';
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { AnalyticsDeleteConfirmDialog } from '../../components/analytics/AnalyticsDeleteConfirmDialog';
import { getChartTypeLabel } from '../../components/analytics/chart-missing-columns';
import { WorkspaceAnalyticsTable } from '../../components/analytics/WorkspaceAnalyticsTable';
import { PageHeading } from '../../components/PageHeading';
import { TruncatedLink } from '../../components/TruncatedLink';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { SearchInput } from '../../components/ui/search-input';
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
import { useCanAdminAnalytics, useCharts, useDeleteChart } from '../../hooks/queries/useAnalytics';
import { useIsOwner } from '../../hooks/useIsOwner';
import { useLastPageInfo } from '../../hooks/useLastPageInfo';
import { useListRetryFocus } from '../../hooks/useListRetryFocus';
import { offlineListMessage, useOfflinePaused } from '../../hooks/useOnlineStatus';
import { handleApiError, listLoadErrorProps } from '../../lib/api-error';
import { formatDateShort, formatRelativeTime } from '../../lib/formatters';
import { getPageAfterDelete } from '../../lib/pagination';
import { iGa } from '../../lib/utils';

/** 목록 보기 — 「워크스페이스 전체」는 analytics:admin 에게만 보인다(WD-199). */
type ListTab = 'mine' | 'shared' | 'workspace';

export default function ChartListPage() {
  // WD-985: 「검색 초기화」 뒤 초점이 body 로 떨어지지 않게 이 화면의 검색 입력으로 보낸다
  const searchInputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [selectedTab, setSelectedTab] = useState<ListTab>('mine');
  // 관리자(analytics:admin)에게만 「워크스페이스 전체」 보기를 둔다 (WD-199) — 정지·제거된 작성자가 공유한 자산을 정리할 수 있게 한다.
  const { canAdmin } = useCanAdminAnalytics();
  // 관리자 권한이 사라져 「워크스페이스 전체」 탭이 없어지면 「내 …」로 되돌린다 — 없는 탭이 패널에 남지 않게(WD-514)
  const tab: ListTab = selectedTab === 'workspace' && !canAdmin ? 'mine' : selectedTab;
  const workspaceView = tab === 'workspace';
  const [page, setPage] = useState(0);
  const size = 10;

  const sharedOnly = tab === 'shared';
  // 「내 차트」 탭은 내가 만든 차트만 — 필터 없이 부르면 서버가 남의 공유 차트까지 섞어 준다(WD-328). 「공유됨」은 sharedOnly 그대로.
  const mineOnly = tab === 'mine';

  // 목록 훅과 쪽 수 기억(useLastPageInfo)이 같은 조회 조건 객체를 쓴다
  const chartParams = {
    search: search || undefined,
    sharedOnly: sharedOnly || undefined,
    mineOnly: mineOnly || undefined,
    page,
    size,
  };
  const {
    data: chartsData,
    isLoading,
    isPending,
    isFetching,
    isError,
    isPaused,
    isPlaceholderData,
    error: chartsError,
    refetch,
  } = useCharts(chartParams);

  // 오프라인 멈춤 판정(공용 useOfflinePaused, WD-966·WD-982·WD-971): 받은 목록 없음 또는 쪽 이동 placeholder + paused 가 실제 오프라인이면 offline(안내),
  // 데이터 없이 온라인인데 paused(탭 숨김으로 재시도 멈춤)면 waiting(스켈레톤). offline 이면 흐림·inert 없이 안내를 보인다
  const { waiting, offline } = useOfflinePaused({ data: chartsData, isPaused, isPlaceholderData });

  // 「다시 시도」 포커스(WD-987): 실패한 목록에서 누르면 표로 초점을 옮겼다가 다시 실패하면 버튼으로 되돌린다.
  // 403 은 다시 해도 같으므로 버튼을 두지 않는다
  const { tableProps, bodyProps, retryAction } = useListRetryFocus({
    isPending,
    // WD-982: 쪽 이동 중 이전 쪽 행 처리(pending 판정·흐림·inert)는 훅이 맡는다
    // 오프라인 쪽 이동 대기는 흐림·inert 없이 안내를 보인다(WD-982, useOfflinePaused)
    isPlaceholderData: isPlaceholderData && !offline,
    isError,
    isFetching,
    data: chartsData,
    error: chartsError,
    refetch,
  });

  const deleteChart = useDeleteChart();
  // 남이 공유한 차트 줄에서는 편집·삭제를 숨긴다 — 서버가 작성자 외 요청을 404 로 거절한다(WD-196). 줄 클릭으로 보기는 그대로.
  const isOwner = useIsOwner();

  const charts = chartsData?.content ?? [];
  const { totalPages, totalElements } = useLastPageInfo(chartsData, chartParams);

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteChart.mutateAsync(id);
      // 현재 페이지의 마지막 항목을 삭제한 경우, 페이지를 앞으로 보정해
      // "검색 결과 없음" 오표시(#549)를 방지한다.
      setPage((prev) => getPageAfterDelete(charts.length, prev));
      toast.success(`차트 "${name}"${iGa(name)} 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '차트 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <PageHeading>차트</PageHeading>
        <Button onClick={() => navigate('/analytics/charts/new')}>
          <Plus className="h-4 w-4" />
          새 차트
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
        }}
      >
        {/* WD-890: 탭이 24~29px 로 낮아 누르기 빠듯했다 — 목록 높이를 h-10 으로 올리고 모든 탭(조건부 「워크스페이스 전체」 포함)에
            min-h-8(32px) 하한을 목록에서 한 번에 준다(대시보드 목록 WD-877 과 같은 방식) */}
        <TabsList className="group-data-[orientation=horizontal]/tabs:h-10 [&>[role=tab]]:min-h-8">
          <TabsTrigger value="mine">내 차트</TabsTrigger>
          <TabsTrigger value="shared">공유됨</TabsTrigger>
          {canAdmin && <TabsTrigger value="workspace">워크스페이스 전체</TabsTrigger>}
        </TabsList>

        <TabsControlsContent value={tab} className="space-y-6">
          {workspaceView ? (
            <WorkspaceAnalyticsTable kind="charts" />
          ) : (
            <>
              {/* Filters */}
              <div className="flex items-center gap-3 flex-wrap">
                <SearchInput
                  placeholder="차트 검색..."
                  inputRef={searchInputRef}
                  // 입력이 멈춘 뒤 한 번만 검색한다(WD-513) — 글자마다 목록 요청·페이지 초기화가 나가지 않게
                  debounceMs={300}
                  value={search}
                  onChange={(value) => {
                    setSearch(value);
                    setPage(0);
                  }}
                />
              </div>

              {/* Table */}
              <div className="rounded-md border">
                <Table
                  aria-label="차트 목록"
                  {...tableProps}
                >
                  <TableHeader>
                    <TableRow>
                      <TableHead>이름</TableHead>
                      <TableHead>타입</TableHead>
                      <TableHead>쿼리</TableHead>
                      <TableHead>수정일</TableHead>
                      {/* WD-890: 버튼 2개(32×2) + 간격 4 + 셀 좌우 여백 16 = 84px. 빈 머리는 스크린리더에 이름 없는 열로 읽혀 sr-only 「작업」을 준다 */}
                      <TableHead className="w-[84px]">
                        <span className="sr-only">작업</span>
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <tbody {...bodyProps}>
                    {isLoading || waiting ? (
                      <TableSkeletonRows columns={5} rows={5} />
                    ) : offline ? (
                      // 오프라인 쪽 이동 대기는 흐림·inert 없이 안내를 보인다(WD-982, useOfflinePaused)
                      <TableEmptyRow colSpan={5} message={offlineListMessage('차트')} />
                    ) : isError && !chartsData ? (
                      // 목록 조회 실패 — 0건 안내로 오해되지 않게 실패 안내를 보인다. 받아 둔 목록이 있으면 재조회 실패여도 유지한다 (WD-978)
                      <TableErrorRow
                        colSpan={5}
                        // 403 은 권한 안내(흐린 톤), 그 밖은 서버 사유 — 권한 문제를 일시 장애처럼 보이지 않게 한다 (WD-978 리뷰 후속)
                        {...listLoadErrorProps(chartsError, '차트')}
                        action={retryAction}
                      />
                    ) : charts.length > 0 ? (
                      charts.map((chart) => (
                        <TableRow
                          key={chart.id}
                          className="cursor-pointer hover:bg-muted/50 transition-colors group row-hover"
                          onClick={() => navigate(`/analytics/charts/${chart.id}`)}
                        >
                          <TableCell className="font-medium">
                            <div className="flex items-center gap-2">
                              <BarChart2 className="h-4 w-4 shrink-0 text-muted-foreground" />
                              {/* min-w-0: flex 아이템 기본값(min-width:auto)이 truncate 축소를 막는 문제 방지 */}
                              <div className="min-w-0">
                                <div className="flex items-center gap-1.5">
                                  {/* WD-890: xl 미만(사이드바 펼친 1024 포함)에서 표가 넘치지 않게 이름·설명·쿼리 이름 상한을 줄임 —
                                      잘린 글자는 title 로 전체를 보임 */}
                                  {/* WD-933: 행 클릭만으로는 키보드 경로가 없어(남이 공유한 차트는 편집 버튼도 없다) 이름을 상세 링크로 둔다. title·말줄임은 TruncatedLink 가 한다 */}
                                  <TruncatedLink
                                    to={`/analytics/charts/${chart.id}`}
                                    value={chart.name}
                                    className="block max-w-[14rem] xl:max-w-xs"
                                  />
                                  {chart.isShared && (
                                    <Badge variant="secondary" className="text-xs gap-1 py-0">
                                      <Share2 className="h-2.5 w-2.5" />
                                      공유
                                    </Badge>
                                  )}
                                </div>
                                {chart.description && (
                                  <p
                                    className="text-xs text-muted-foreground truncate max-w-[14rem] xl:max-w-xs mt-0.5"
                                    title={chart.description}
                                  >
                                    {chart.description}
                                  </p>
                                )}
                                <p className="text-xs text-muted-foreground mt-0.5">
                                  {chart.createdByName}
                                </p>
                              </div>
                            </div>
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline" className="text-xs">
                              {getChartTypeLabel(chart.chartType)}
                            </Badge>
                          </TableCell>
                          <TableCell>
                            <span
                              className="text-sm text-muted-foreground truncate max-w-[8rem] xl:max-w-[200px] block"
                              title={chart.savedQueryName}
                            >
                              {chart.savedQueryName}
                            </span>
                          </TableCell>
                          <TableCell>
                            <span
                              className="text-sm text-muted-foreground"
                              title={formatDateShort(chart.updatedAt)}
                            >
                              {formatRelativeTime(chart.updatedAt)}
                            </span>
                          </TableCell>
                          <TableCell>
                            {/* 행 작업 버튼 — 마우스를 올리거나 키보드 포커스가 안에 들어오면 보인다(WD-412).
                                WD-890: 28px(h-7 w-7)였던 버튼을 icon-sm(32px)으로 키운다. 아이콘은 size- 클래스가 없으면 Button 이 16px 로 덮으므로 size-3.5(14px)를 준다 */}
                            {isOwner(chart.createdBy) && (
                              <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                                <Button
                                  variant="ghost"
                                  size="icon-sm"
                                  title="편집"
                                  aria-label="편집"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    navigate(`/analytics/charts/${chart.id}`);
                                  }}
                                >
                                  <Pencil className="size-3.5" />
                                </Button>
                                {/* WD-86: 함께 삭제되는 항목(CASCADE)을 창을 열 때 조회해 보여 준다 */}
                                <AnalyticsDeleteConfirmDialog
                                  kind="chart"
                                  id={chart.id}
                                  itemName={chart.name}
                                  onConfirm={() => handleDelete(chart.id, chart.name)}
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
                              </div>
                            )}
                          </TableCell>
                        </TableRow>
                      ))
                    ) : (
                      <TableEmptyRow
                        colSpan={5}
                        message="차트가 없습니다."
                        resetFocusRef={searchInputRef}
                        searchKeyword={search || undefined}
                        onResetSearch={search ? () => { setSearch(''); setPage(0); } : undefined}
                        emptyAction={
                          !search ? (
                            <Button size="sm" variant="outline" onClick={() => navigate('/analytics/charts/new')}>
                              <Plus className="h-4 w-4" />
                              새 차트 만들기
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
