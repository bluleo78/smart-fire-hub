import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Circle,
  Clock,
  Database,
  GitBranch,
  LayoutDashboard,
  ListChecks,
  Terminal,
  Upload,
  XCircle,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { FreshnessBar } from '../components/ui/freshness-bar';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import { Separator } from '../components/ui/separator';
import { Skeleton } from '../components/ui/skeleton';
import { Sparkline } from '../components/ui/sparkline';
import { useDashboards } from '../hooks/queries/useAnalytics';
import {
  useActivityFeed,
  useAttentionItems,
  useDashboardStats,
  useSystemHealth,
} from '../hooks/queries/useDashboard';
import { useDatasetPermissions, useDatasets } from '../hooks/queries/useDatasets';
import { usePipelinePermissions } from '../hooks/queries/usePipelines';
import { useAuth } from '../hooks/useAuth';
import { getOriginTypeLabel, getStatusBadgeVariant, getStatusLabel, timeAgo } from '../lib/formatters';

function ActivityIcon({ eventType }: { eventType: string }) {
  const t = eventType.toUpperCase();
  if (t.includes('FAIL') || t.includes('ERROR')) return <XCircle className="h-4 w-4 text-destructive shrink-0" />;
  if (t.includes('WARN')) return <AlertTriangle className="h-4 w-4 text-warning shrink-0" />;
  if (t.includes('SUCCESS') || t.includes('COMPLETED')) return <CheckCircle2 className="h-4 w-4 text-success shrink-0" />;
  if (t.includes('RUN') || t.includes('START')) return <Terminal className="h-4 w-4 text-info shrink-0" />;
  return <Circle className="h-4 w-4 text-muted-foreground shrink-0" />;
}

/**
 * 홈 카드의 조회 실패 안내 — 실패를 「없습니다」·0 개로 그리면 데이터가 사라졌다고 오해하므로(WD-333),
 * 무엇을 불러오지 못했는지와 「다시 시도」를 그 자리에 보인다.
 */
function HomeLoadError({ message, onRetry, className }: { message: string; onRetry: () => void; className?: string }) {
  return (
    <div role="alert" className={`flex flex-wrap items-center gap-2 text-sm ${className ?? ''}`}>
      <span className="text-destructive">{message}</span>
      <Button variant="outline" size="sm" className="h-6 text-xs px-2" onClick={onRetry}>
        다시 시도
      </Button>
    </div>
  );
}

export default function HomePage() {
  const navigate = useNavigate();
  const { user } = useAuth();

  // 조회 실패(isError)와 「정말 비어 있음」을 구분한다 — 이전 데이터 없이 실패하면 카드마다 실패 안내를 보인다(WD-333)
  const {
    data: health,
    isLoading: isHealthLoading,
    isError: isHealthError,
    refetch: refetchHealth,
  } = useSystemHealth();
  const { data: stats } = useDashboardStats();
  const {
    data: attentionItems,
    isError: isAttentionError,
    refetch: refetchAttention,
  } = useAttentionItems();
  const {
    data: dashboardsData,
    isLoading: isDashboardsLoading,
    isError: isDashboardsError,
    refetch: refetchDashboards,
  } = useDashboards({ page: 0, size: 5 });
  const {
    data: datasetsData,
    isLoading: isDatasetsLoading,
    isError: isDatasetsError,
    refetch: refetchDatasets,
  } = useDatasets({ page: 0, size: 5 });
  const healthFailed = isHealthError && !health;
  const attentionFailed = isAttentionError && !attentionItems;
  const dashboardsFailed = isDashboardsError && !dashboardsData;
  const datasetsFailed = isDatasetsError && !datasetsData;
  // 데이터셋 생성 바로가기는 생성 권한(dataset:write)이 있을 때만 보인다 — 없으면 생성 화면이 권한 안내만 보이므로(WD-144)
  const { canWriteDataset } = useDatasetPermissions();
  // 파이프라인 요약·바로가기·필터는 조회 권한(pipeline:read)이 있을 때만 보인다 — 없으면 파이프라인 화면이 권한 안내만 보이고,
  // 서버도 홈 API 에서 파이프라인 정보를 빼고 보낸다(WD-272)
  const {
    canReadPipeline,
    canExecutePipeline,
    isLoading: isPermissionsLoading,
  } = usePipelinePermissions();

  // 활동 피드: 필터 상태만 보관. 페이지는 useInfiniteQuery가 내부적으로 관리한다 (이슈 #226).
  const [selectedTypeFilter, setTypeFilter] = useState('');
  // 「파이프라인」 필터는 조회 권한이 있을 때만 있다 — 권한이 빠진 뒤 남은 선택값은 「전체」로 본다(WD-272)
  const typeFilter = selectedTypeFilter === 'PIPELINE' && !canReadPipeline ? '' : selectedTypeFilter;
  const [severityFilter, setSeverityFilter] = useState('');
  const {
    data: activityFeedPages,
    isLoading: isActivityLoading,
    isError: isActivityError,
    refetch: refetchActivity,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useActivityFeed({
    size: 20,
    type: typeFilter || undefined,
    severity: severityFilter || undefined,
  });

  // 모든 페이지의 items를 평탄화 — 렌더링은 누적된 리스트를 그대로 사용
  const activityItems = useMemo(
    () => activityFeedPages?.pages.flatMap((p) => p.items) ?? [],
    [activityFeedPages],
  );
  const activityTotalCount = activityFeedPages?.pages[0]?.totalCount;

  function applyFilter(type: string, severity: string) {
    setTypeFilter(type);
    setSeverityFilter(severity);
  }

  // sentinel 감지: 하단 sentinel이 시야에 들어오면 다음 페이지 자동 fetch.
  // 컨테이너 내부 스크롤(`h-[32rem]`)이므로 root를 스크롤 컨테이너로 지정해야 한다.
  const activityScrollRef = useRef<HTMLDivElement>(null);
  const activitySentinelRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const sentinel = activitySentinelRef.current;
    const root = activityScrollRef.current;
    if (!sentinel || !root || !hasNextPage || isFetchingNextPage) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          fetchNextPage();
        }
      },
      { root, rootMargin: '100px' },
    );
    io.observe(sentinel);
    return () => io.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, activityItems.length]);

  const ph = health?.pipelineHealth;
  const dh = health?.datasetHealth;

  // 전체 시스템에 문제가 없는지 판단 — 파이프라인을 볼 수 없으면 서버가 0 으로 보내 판단할 수 없으므로 표시하지 않는다(WD-272)
  // 주의 필요 항목을 불러오지 못했으면 정상이라고 말할 수 없다(WD-333)
  const hasNoIssues = canReadPipeline && ph && dh
    && ph.failing === 0 && dh.stale === 0 && dh.empty === 0
    && !attentionFailed
    && (!attentionItems || attentionItems.length === 0);

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">홈</h1>
        <p className="text-muted-foreground mt-1">환영합니다, {user?.name}님!</p>
      </div>

      {/* ZONE 1 — 시스템 건강 상태바 (컴팩트 인라인) */}
      {/* SR 네비게이션을 위한 sr-only 헤딩 — 시각 디자인은 유지 */}
      <section aria-labelledby="home-system-health-heading" className="rounded-lg border bg-card px-6 py-3">
        <h2 id="home-system-health-heading" className="sr-only">시스템 건강 상태</h2>
        {/* 권한을 불러오는 동안도 스켈레톤 — 파이프라인 요약이 늦게 끼어들며 줄이 밀리지 않게(WD-272) */}
        {isHealthLoading || isPermissionsLoading ? (
          <div className="flex gap-4">
            <Skeleton className="h-5 w-48" />
            <Skeleton className="h-5 w-48" />
          </div>
        ) : healthFailed ? (
          <HomeLoadError message="시스템 상태를 불러오지 못했습니다." onRetry={() => void refetchHealth()} />
        ) : (
          <div className="flex flex-wrap items-center gap-x-6 gap-y-2 [word-break:keep-all]">
            {/* Pipeline summary — 조회 권한이 있을 때만(WD-272) */}
            {canReadPipeline && (
              <>
                <button
                  className="flex items-center gap-2 hover:opacity-80 transition-opacity cursor-pointer"
                  onClick={() => navigate('/pipelines')}
                >
                  <GitBranch className="h-4 w-4 text-pipeline shrink-0" />
                  <span className="text-sm font-medium text-pipeline whitespace-nowrap">파이프라인</span>
                  {ph && ph.total > 0 ? (
                    <span className="flex items-center gap-1.5 text-sm tabular-nums">
                      {ph.failing > 0 && (
                        <span className="text-destructive font-semibold whitespace-nowrap">{ph.failing} 실패</span>
                      )}
                      {ph.running > 0 && (
                        <span className="text-info font-semibold whitespace-nowrap">{ph.running} 실행중</span>
                      )}
                      {ph.healthy > 0 && (
                        <span className="text-success font-semibold whitespace-nowrap">{ph.healthy} 정상</span>
                      )}
                      {ph.disabled > 0 && (
                        <span className="text-muted-foreground whitespace-nowrap">{ph.disabled} 비활성</span>
                      )}
                    </span>
                  ) : (
                    <span className="text-sm text-muted-foreground tabular-nums whitespace-nowrap">0개</span>
                  )}
                  {/* #669: 하드코딩 배열 대신 백엔드가 계산한 최근 7일 실제 실행 추이(ph.trend) 사용 */}
                  <Sparkline
                    data={ph?.trend ?? []}
                    color="pipeline"
                    className="mt-1"
                    ariaLabel="최근 7일 파이프라인 실행 추이"
                    testId="pipeline-sparkline"
                  />
                </button>

                <div className="h-4 w-px bg-border" />
              </>
            )}

            {/* Dataset summary */}
            <button
              className="flex items-center gap-2 hover:opacity-80 transition-opacity cursor-pointer"
              onClick={() => navigate('/data/datasets')}
            >
              <Database className="h-4 w-4 text-primary shrink-0" />
              <span className="text-sm font-medium text-primary whitespace-nowrap">데이터셋</span>
              {dh && dh.total > 0 ? (
                <span className="flex items-center gap-1.5 text-sm tabular-nums">
                  {dh.empty > 0 && (
                    <span className="text-destructive font-semibold whitespace-nowrap">{dh.empty} 빈 데이터</span>
                  )}
                  {dh.stale > 0 && (
                    <span className="text-warning font-semibold whitespace-nowrap">{dh.stale} 오래됨</span>
                  )}
                  {dh.fresh > 0 && (
                    <span className="text-success font-semibold whitespace-nowrap">{dh.fresh} 최신</span>
                  )}
                </span>
              ) : (
                <span className="text-sm text-muted-foreground tabular-nums whitespace-nowrap">0개</span>
              )}
              {/* #669: 하드코딩 배열 대신 백엔드가 계산한 최근 7일 실제 임포트/변경 추이(dh.trend) 사용 */}
              <Sparkline
                data={dh?.trend ?? []}
                color="dataset"
                className="mt-1"
                ariaLabel="최근 7일 데이터셋 변경 추이"
                testId="dataset-sparkline"
              />
            </button>

            <div className="h-4 w-px bg-border" />

            {/* Quick counts */}
            <div className="flex items-center gap-1.5 text-sm text-dashboard-accent whitespace-nowrap">
              <LayoutDashboard className="h-3.5 w-3.5 shrink-0" />
              {/* 목록 조회가 실패했으면 0 대신 「—」 — 대시보드가 없는 것처럼 보이지 않게(WD-333) */}
              대시보드 <span className="tabular-nums">{dashboardsFailed ? '—' : (dashboardsData?.totalElements ?? 0)}</span>
            </div>

            {stats && stats.recentImports.length > 0 && (
              <>
                <div className="h-4 w-px bg-border" />
                <div className="flex items-center gap-1.5 text-sm text-muted-foreground whitespace-nowrap">
                  <Upload className="h-3.5 w-3.5 shrink-0" />
                  최근 임포트 {stats.recentImports.length}건
                </div>
              </>
            )}

            {/* 모든 시스템 정상 표시 */}
            {hasNoIssues && (
              <div className="flex items-center gap-1.5 text-sm text-success ml-auto whitespace-nowrap">
                <CheckCircle2 className="h-4 w-4 shrink-0" />
                모든 시스템 정상
              </div>
            )}
          </div>
        )}
      </section>

      {/* ZONE 2 — 주의 필요 (이슈가 있을 때만 표시). 조회 실패 시에는 사라지지 않고 실패를 알린다(WD-333) */}
      {attentionFailed && (
        <Card className="py-2 px-4">
          <HomeLoadError message="주의 필요 항목을 불러오지 못했습니다." onRetry={() => void refetchAttention()} />
        </Card>
      )}
      {attentionItems && attentionItems.length > 0 && (
        <Card className="py-2 gap-1 flex flex-col card-hover">
          <CardHeader className="px-4 pb-0">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm font-semibold flex items-center gap-2">
                <AlertTriangle className="h-4 w-4 text-warning" />
                {/* h2: 페이지 내 1단계 섹션 헤딩 (SR 네비게이션 지원) */}
                <h2 className="text-sm font-semibold m-0">주의 필요</h2>
                <span className="inline-flex items-center justify-center rounded-full bg-destructive/10 text-destructive text-[11px] font-semibold px-1.5 py-0 min-w-[1.25rem] h-[1.25rem] leading-none">
                  {attentionItems.length}
                </span>
              </CardTitle>
            </div>
          </CardHeader>
          <Separator />
          <CardContent className="px-3 pt-1 pb-2">
            <div
              className="space-y-1.5 overflow-y-auto overscroll-contain max-h-[11.5rem] pr-2"
            >
              {attentionItems.map((item, idx) => (
                <button
                  key={idx}
                  className="w-full text-left"
                  onClick={() =>
                    navigate(
                      item.entityType === 'PIPELINE'
                        ? `/pipelines/${item.entityId}`
                        : `/data/datasets/${item.entityId}`
                    )
                  }
                >
                  <div
                    className={`rounded-lg border cursor-pointer transition-colors hover:bg-accent border-l-4 px-4 py-2.5 flex items-center gap-3 ${
                      item.severity === 'CRITICAL'
                        ? 'border-l-destructive'
                        : 'border-l-warning'
                    }`}
                  >
                    {item.severity === 'CRITICAL' ? (
                      <XCircle className="h-4 w-4 text-destructive shrink-0" />
                    ) : (
                      <AlertTriangle className="h-4 w-4 text-warning shrink-0" />
                    )}
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium leading-tight">{item.title}</p>
                      <p className="text-xs text-muted-foreground mt-0.5">{item.description}</p>
                    </div>
                    <Badge
                      variant={item.severity === 'CRITICAL' ? 'destructive' : 'secondary'}
                      className="shrink-0"
                    >
                      {item.severity === 'CRITICAL' ? '긴급' : '경고'}
                    </Badge>
                  </div>
                </button>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* ZONE 3 — 퀵 액션 */}
      <section aria-labelledby="home-quick-actions-heading" className="flex flex-wrap gap-2">
        <h2 id="home-quick-actions-heading" className="sr-only">퀵 액션</h2>
        {canWriteDataset && (
          <Button variant="outline" size="sm" onClick={() => navigate('/data/datasets/new')}>
            <Database className="h-4 w-4" />
            새 데이터셋
          </Button>
        )}
        {canReadPipeline && (
          <Button variant="outline" size="sm" onClick={() => navigate('/pipelines')}>
            <ListChecks className="h-4 w-4" />
            파이프라인 목록
          </Button>
        )}
        <Button variant="outline" size="sm" onClick={() => navigate('/analytics/queries')}>
          <Terminal className="h-4 w-4" />
          SQL 편집기
        </Button>
        <Button variant="outline" size="sm" onClick={() => navigate('/analytics/dashboards')}>
          <LayoutDashboard className="h-4 w-4" />
          대시보드 관리
        </Button>
      </section>

      {/* ZONE 4 + ZONE 5 — 2컬럼
          WD-332: 그리드 칸·카드의 기본 최소 너비(auto)는 내용(줄바꿈 안 되는 긴 이름)만큼이라, 모바일에서 카드가 화면보다
          넓어져 「전체 보기」·시각·배지가 잘렸다. 바깥·안쪽 그리드 모두 grid-cols-1(minmax(0,1fr))로 칸 최소 너비를 0 으로 둬 칸 폭 안에
          가두고 이름은 말줄임한다. 좁은 카드에서는 머리줄의 「총 N개·전체 보기」가 다음 줄로 내려간다. */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-5">
        {/* ZONE 4 — 최근 사용 (좌측 3cols, 2x2 위젯 그리드) */}
        <div className="lg:col-span-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
          {/* 최근 대시보드 */}
          <Card className="py-2 gap-1 flex flex-col card-hover">
            <CardHeader className="px-3 pb-0">
              <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
                <CardTitle className="text-sm font-semibold flex items-center gap-2">
                  <LayoutDashboard className="h-4 w-4 text-muted-foreground" />
                  {/* h3: ZONE 4 위젯 그리드 내 하위 섹션 헤딩 */}
                  <h3 className="text-sm font-semibold m-0">최근 대시보드</h3>
                </CardTitle>
                <div className="flex items-center gap-2">
                  {dashboardsData?.totalElements != null && (
                    <span className="text-xs text-muted-foreground">총 {dashboardsData.totalElements}개</span>
                  )}
                  <Link
                    to="/analytics/dashboards"
                    className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                  >
                    전체 보기
                  </Link>
                </div>
              </div>
            </CardHeader>
            <Separator />
            <CardContent className="px-3 pt-1 pb-2">
              {isDashboardsLoading ? (
                <div className="space-y-1.5">
                  {[...Array(3)].map((_, i) => (
                    <Skeleton key={i} className="h-7 w-full" />
                  ))}
                </div>
              ) : dashboardsFailed ? (
                <HomeLoadError message="대시보드를 불러오지 못했습니다." onRetry={() => void refetchDashboards()} className="py-1.5" />
              ) : dashboardsData?.content && dashboardsData.content.length > 0 ? (
                <div className="overflow-y-auto overscroll-contain max-h-[13rem] pr-2">
                  {dashboardsData.content.map((dashboard) => (
                    <button
                      key={dashboard.id}
                      className="w-full text-left flex items-center justify-between py-1.5 px-1.5 -mx-1.5 rounded hover:bg-accent transition-colors group"
                      onClick={() => navigate(`/analytics/dashboards/${dashboard.id}`)}
                    >
                      <span className="text-sm truncate group-hover:text-foreground">{dashboard.name}</span>
                      <span className="text-xs text-muted-foreground shrink-0 ml-2">위젯 {dashboard.widgetCount ?? 0}개</span>
                    </button>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground py-1.5">
                  대시보드가 없습니다.{' '}
                  <Link to="/analytics/dashboards" className="text-primary hover:underline">만들기</Link>
                </p>
              )}
            </CardContent>
          </Card>

          {/* 최근 데이터셋 */}
          <Card className="py-2 gap-1 flex flex-col card-hover">
            <CardHeader className="px-3 pb-0">
              <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1">
                <CardTitle className="text-sm font-semibold flex items-center gap-2">
                  <Database className="h-4 w-4 text-muted-foreground" />
                  <h3 className="text-sm font-semibold m-0">최근 데이터셋</h3>
                </CardTitle>
                <div className="flex items-center gap-2">
                  {datasetsData?.totalElements != null && (
                    <span className="text-xs text-muted-foreground">총 {datasetsData.totalElements}개</span>
                  )}
                  <Link
                    to="/data/datasets"
                    className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                  >
                    전체 보기
                  </Link>
                </div>
              </div>
            </CardHeader>
            <Separator />
            <CardContent className="px-3 pt-1 pb-2">
              {isDatasetsLoading ? (
                <div className="space-y-1.5">
                  {[...Array(3)].map((_, i) => (
                    <Skeleton key={i} className="h-7 w-full" />
                  ))}
                </div>
              ) : datasetsFailed ? (
                <HomeLoadError message="데이터셋을 불러오지 못했습니다." onRetry={() => void refetchDatasets()} className="py-1.5" />
              ) : datasetsData?.content && datasetsData.content.length > 0 ? (
                <div className="overflow-y-auto overscroll-contain max-h-[13rem] pr-2">
                  {datasetsData.content.map((ds) => (
                    <button
                      key={ds.id}
                      className="w-full text-left flex items-center justify-between py-1.5 px-1.5 -mx-1.5 rounded hover:bg-accent transition-colors group"
                      onClick={() => navigate(`/data/datasets/${ds.id}`)}
                    >
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-sm truncate group-hover:text-foreground">{ds.name}</span>
                        <FreshnessBar lastUpdated={ds.createdAt} className="shrink-0" />
                        <Badge variant="outline" className="shrink-0 text-xs px-1.5 py-0">
                          {/* 출처 라벨은 데이터셋 목록과 같은 매핑을 쓴다(WD-296) — 예전에는 DERIVED 밖을 모두 「원본」으로 보여
                              파이프라인 자동 생성 임시(TEMP) 데이터셋이 사용자가 올린 원본처럼 보였다. */}
                          {ds.storageType === 'DOCUMENT' ? '문서' : getOriginTypeLabel(ds.originType)}
                        </Badge>
                      </div>
                    </button>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground py-1.5">
                  데이터셋이 없습니다.
                  {canWriteDataset && (
                    <>
                      {' '}
                      <Link to="/data/datasets/new" className="text-primary hover:underline">만들기</Link>
                    </>
                  )}
                </p>
              )}
            </CardContent>
          </Card>

          {/* 최근 임포트 */}
          {stats && stats.recentImports.length > 0 && (
            <Card className="py-2 gap-1 flex flex-col card-hover">
              <CardHeader className="px-3 pb-0">
                <div className="flex items-center justify-between">
                  <CardTitle className="text-sm font-semibold flex items-center gap-2">
                    <Upload className="h-4 w-4 text-muted-foreground" />
                    <h3 className="text-sm font-semibold m-0">최근 임포트</h3>
                  </CardTitle>
                  <span className="text-xs text-muted-foreground">총 {stats.recentImports.length}건</span>
                </div>
              </CardHeader>
              <Separator />
              <CardContent className="px-3 pt-1 pb-2">
                <div>
                  {stats.recentImports.slice(0, 5).map((imp) => (
                    <div key={imp.id} className="flex items-center justify-between py-1.5">
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm">{imp.datasetName}</p>
                        <p className="text-xs text-muted-foreground truncate">{imp.fileName}</p>
                      </div>
                      <div className="flex items-center gap-1.5 shrink-0 ml-2">
                        <Badge variant={getStatusBadgeVariant(imp.status)} className="text-xs px-1.5 py-0">
                          {getStatusLabel(imp.status)}
                        </Badge>
                        <span className="text-xs text-muted-foreground">{timeAgo(imp.createdAt)}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          )}

          {/* 최근 실행 */}
          {canReadPipeline && stats && stats.recentExecutions.length > 0 && (
            <Card className="py-2 gap-1 flex flex-col card-hover">
              <CardHeader className="px-3 pb-0">
                <div className="flex items-center justify-between">
                  <CardTitle className="text-sm font-semibold flex items-center gap-2">
                    <GitBranch className="h-4 w-4 text-muted-foreground" />
                    <h3 className="text-sm font-semibold m-0">최근 실행</h3>
                  </CardTitle>
                  <span className="text-xs text-muted-foreground">총 {stats.recentExecutions.length}건</span>
                </div>
              </CardHeader>
              <Separator />
              <CardContent className="px-3 pt-1 pb-2">
                <div>
                  {stats.recentExecutions.slice(0, 5).map((exec) => (
                    <div key={exec.id} className="flex items-center justify-between py-1.5">
                      <p className="truncate text-sm min-w-0 flex-1">{exec.pipelineName}</p>
                      <div className="flex items-center gap-1.5 shrink-0 ml-2">
                        <Badge variant={getStatusBadgeVariant(exec.status)} className="text-xs px-1.5 py-0">
                          {getStatusLabel(exec.status)}
                        </Badge>
                        <span className="text-xs text-muted-foreground">{timeAgo(exec.createdAt)}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          )}
        </div>

        {/* ZONE 5 — 활동 피드 (우측 2cols) */}
        <Card className="lg:col-span-2 py-2 gap-1 flex flex-col card-hover">
          <CardHeader className="px-3 pb-0">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm font-semibold flex items-center gap-2">
                <Activity className="h-4 w-4 text-muted-foreground" />
                {/* h2: ZONE 5 활동 피드 (페이지 1단계 섹션) */}
                <h2 className="text-sm font-semibold m-0">활동 피드</h2>
              </CardTitle>
              {activityTotalCount != null && (
                <span className="text-xs text-muted-foreground">총 {activityTotalCount}건</span>
              )}
            </div>
            {/* Filters */}
            <div className="flex flex-wrap items-center gap-3 pt-1">
              <div className="flex items-center gap-1.5">
                <span className="text-xs text-muted-foreground">유형</span>
                {[
                  { label: '전체', value: '' },
                  // 파이프라인 필터는 조회 권한이 있을 때만 — 없으면 서버가 파이프라인 이벤트를 싣지 않아 늘 빈 결과다(WD-272)
                  ...(canReadPipeline ? [{ label: '파이프라인', value: 'PIPELINE' }] : []),
                  { label: '데이터셋', value: 'DATASET' },
                ].map((opt) => (
                  <Button
                    key={opt.value}
                    variant={typeFilter === opt.value ? 'default' : 'outline'}
                    size="sm"
                    className="h-6 text-xs px-2"
                    onClick={() => applyFilter(opt.value, severityFilter)}
                  >
                    {opt.label}
                  </Button>
                ))}
              </div>
              <div className="flex items-center gap-1.5">
                <span className="text-xs text-muted-foreground">상태</span>
                <Select
                  value={severityFilter || 'ALL'}
                  onValueChange={(v) => applyFilter(typeFilter, v === 'ALL' ? '' : v)}
                >
                  <SelectTrigger className="h-6 text-xs w-[80px] px-2">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">전체</SelectItem>
                    <SelectItem value="CRITICAL">실패</SelectItem>
                    <SelectItem value="WARNING">경고</SelectItem>
                    <SelectItem value="INFO">정보</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>
          </CardHeader>
          <Separator />
          <CardContent className="px-3 pt-1 pb-2">
            {isActivityLoading ? (
              <div className="space-y-1.5">
                {[...Array(5)].map((_, i) => (
                  <Skeleton key={i} className="h-10 w-full" />
                ))}
              </div>
            ) : isActivityError && !activityFeedPages ? (
              <HomeLoadError message="활동 내역을 불러오지 못했습니다." onRetry={() => void refetchActivity()} className="justify-center py-8" />
            ) : activityItems.length > 0 ? (
              <div
                ref={activityScrollRef}
                data-testid="activity-feed-scroll"
                /*
                 * 높이를 뷰포트에 연동한다. `h-[32rem]`(512px)은 화면 크기와 무관한 고정값이라
                 * 홈에서 main(457px 오버플로)·이 피드(604px)·다른 카드(191px)가 동시에 스크롤됐고,
                 * 화면이 낮을수록 나빠졌다(피드 하나가 뷰포트보다 커지는 구간이 생긴다).
                 * 내부 스크롤 자체는 #225에서 확정된 설계라 유지하고, 높이 산정만 고친다.
                 */
                className="overflow-y-auto overscroll-contain max-h-[min(32rem,50dvh)] pr-2"
              >
                {activityItems.map((item) => (
                  <div
                    key={`${item.eventType}-${item.id}`}
                    className={`flex items-start gap-2.5 py-2 border-b last:border-0 row-hover ${
                      !item.isResolved ? 'border-l-2 border-l-destructive pl-2.5' : 'pl-[12px]'
                    }`}
                  >
                    <div className="mt-0.5">
                      <ActivityIcon eventType={item.eventType} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm leading-tight break-words">{item.title}</p>
                      {item.description && (
                        <p className="text-xs text-muted-foreground mt-0.5 truncate">{item.description}</p>
                      )}
                      <div className="flex items-center gap-2 mt-1">
                        {!item.isResolved && (
                          <Badge variant="destructive" className="text-xs h-4 px-1.5">미해결</Badge>
                        )}
                        <span className="text-[11px] text-muted-foreground flex items-center gap-1">
                          <Clock className="h-3 w-3" />
                          {timeAgo(item.occurredAt)}
                        </span>
                      </div>
                    </div>
                  </div>
                ))}
                {/* sentinel: 시야에 들어오면 IntersectionObserver가 fetchNextPage 호출 */}
                {hasNextPage && (
                  <div
                    ref={activitySentinelRef}
                    data-testid="activity-feed-sentinel"
                    className="py-2 text-center text-xs text-muted-foreground"
                  >
                    {isFetchingNextPage ? '불러오는 중...' : ''}
                  </div>
                )}
                {/* 끝 도달 안내 — 두 번째 페이지 이상 로드 후 더 이상 없을 때만 표시 (단일 페이지에서는 노이즈) */}
                {!hasNextPage && activityFeedPages && activityFeedPages.pages.length > 1 && (
                  <div className="py-2 text-center text-xs text-muted-foreground">
                    더 이상 항목이 없습니다.
                  </div>
                )}
              </div>
            ) : (
              <div className="flex flex-col items-center gap-2 py-8 text-center">
                <Activity className="h-8 w-8 text-muted-foreground/50" />
                <p className="text-sm text-muted-foreground">활동 내역이 없습니다.</p>
                {/* 파이프라인 목록(조회)으로 가서 실행하므로 조회·실행 권한이 모두 있을 때만 보인다(WD-272) */}
                {canReadPipeline && canExecutePipeline && (
                  <Button variant="outline" size="sm" onClick={() => navigate('/pipelines')}>
                    파이프라인 실행하기
                  </Button>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
