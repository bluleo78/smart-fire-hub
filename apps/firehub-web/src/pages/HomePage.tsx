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
  Plus,
  Terminal,
  Upload,
  XCircle,
} from 'lucide-react';
import { useState } from 'react';
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
import { useDatasets } from '../hooks/queries/useDatasets';
import { useAuth } from '../hooks/useAuth';
import { getStatusBadgeVariant, getStatusLabel, timeAgo } from '../lib/formatters';
import type { ActivityFeedParams } from '../types/dashboard';

const THIN_SCROLLBAR =
  '[&::-webkit-scrollbar]:w-1 [&::-webkit-scrollbar-track]:bg-transparent [&::-webkit-scrollbar-thumb]:rounded-full [&::-webkit-scrollbar-thumb]:bg-border';

function ActivityIcon({ eventType }: { eventType: string }) {
  const t = eventType.toUpperCase();
  if (t.includes('FAIL') || t.includes('ERROR')) return <XCircle className="h-4 w-4 text-destructive shrink-0" />;
  if (t.includes('WARN')) return <AlertTriangle className="h-4 w-4 text-warning shrink-0" />;
  if (t.includes('SUCCESS') || t.includes('COMPLETED')) return <CheckCircle2 className="h-4 w-4 text-success shrink-0" />;
  if (t.includes('RUN') || t.includes('START')) return <Terminal className="h-4 w-4 text-info shrink-0" />;
  return <Circle className="h-4 w-4 text-muted-foreground shrink-0" />;
}

export default function HomePage() {
  const navigate = useNavigate();
  const { user } = useAuth();

  const { data: health, isLoading: isHealthLoading } = useSystemHealth();
  const { data: stats } = useDashboardStats();
  const { data: attentionItems } = useAttentionItems();
  const { data: dashboardsData, isLoading: isDashboardsLoading } = useDashboards({ page: 0, size: 5 });
  const { data: datasetsData, isLoading: isDatasetsLoading } = useDatasets({ page: 0, size: 5 });

  const [activityParams, setActivityParams] = useState<ActivityFeedParams>({
    page: 0,
    size: 20,
  });
  const { data: activityFeed, isLoading: isActivityLoading } = useActivityFeed(activityParams);

  const [typeFilter, setTypeFilter] = useState('');
  const [severityFilter, setSeverityFilter] = useState('');

  function applyFilter(type: string, severity: string) {
    setTypeFilter(type);
    setSeverityFilter(severity);
    setActivityParams({ page: 0, size: 20, type: type || undefined, severity: severity || undefined });
  }

  const ph = health?.pipelineHealth;
  const dh = health?.datasetHealth;

  // 전체 시스템에 문제가 없는지 판단
  const hasNoIssues = ph && dh
    && ph.failing === 0 && dh.stale === 0 && dh.empty === 0
    && (!attentionItems || attentionItems.length === 0);

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">홈</h1>
        <p className="text-muted-foreground mt-1">환영합니다, {user?.name}님!</p>
      </div>

      {/* ZONE 1 — 시스템 건강 상태바 (컴팩트 인라인) */}
      <div className="rounded-lg border bg-card px-6 py-3">
        {isHealthLoading ? (
          <div className="flex gap-4">
            <Skeleton className="h-5 w-48" />
            <Skeleton className="h-5 w-48" />
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
            {/* Pipeline summary */}
            <button
              className="flex items-center gap-2 hover:opacity-80 transition-opacity cursor-pointer"
              onClick={() => navigate('/pipelines')}
            >
              <GitBranch className="h-4 w-4 text-pipeline" />
              <span className="text-sm font-medium text-pipeline">파이프라인</span>
              {ph && ph.total > 0 ? (
                <span className="flex items-center gap-1.5 text-sm tabular-nums">
                  {ph.failing > 0 && (
                    <span className="text-destructive font-semibold">{ph.failing} 실패</span>
                  )}
                  {ph.running > 0 && (
                    <span className="text-info font-semibold">{ph.running} 실행중</span>
                  )}
                  {ph.healthy > 0 && (
                    <span className="text-success font-semibold">{ph.healthy} 정상</span>
                  )}
                  {ph.disabled > 0 && (
                    <span className="text-muted-foreground">{ph.disabled} 비활성</span>
                  )}
                </span>
              ) : (
                <span className="text-sm text-muted-foreground tabular-nums">0개</span>
              )}
              <Sparkline data={[3, 5, 2, 8, 4, 6, 9]} color="pipeline" className="mt-1" />
            </button>

            <div className="h-4 w-px bg-border" />

            {/* Dataset summary */}
            <button
              className="flex items-center gap-2 hover:opacity-80 transition-opacity cursor-pointer"
              onClick={() => navigate('/data/datasets')}
            >
              <Database className="h-4 w-4 text-primary" />
              <span className="text-sm font-medium text-primary">데이터셋</span>
              {dh && dh.total > 0 ? (
                <span className="flex items-center gap-1.5 text-sm tabular-nums">
                  {dh.empty > 0 && (
                    <span className="text-destructive font-semibold">{dh.empty} 빈 데이터</span>
                  )}
                  {dh.stale > 0 && (
                    <span className="text-warning font-semibold">{dh.stale} 오래됨</span>
                  )}
                  {dh.fresh > 0 && (
                    <span className="text-success font-semibold">{dh.fresh} 최신</span>
                  )}
                </span>
              ) : (
                <span className="text-sm text-muted-foreground tabular-nums">0개</span>
              )}
              <Sparkline data={[8, 10, 6, 4, 7, 9, 5]} color="dataset" className="mt-1" />
            </button>

            <div className="h-4 w-px bg-border" />

            {/* Quick counts */}
            <div className="flex items-center gap-1.5 text-sm text-dashboard-accent">
              <LayoutDashboard className="h-3.5 w-3.5" />
              대시보드 <span className="tabular-nums">{dashboardsData?.totalElements ?? 0}</span>
            </div>

            {stats && stats.recentImports.length > 0 && (
              <>
                <div className="h-4 w-px bg-border" />
                <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
                  <Upload className="h-3.5 w-3.5" />
                  최근 임포트 {stats.recentImports.length}건
                </div>
              </>
            )}

            {/* 모든 시스템 정상 표시 */}
            {hasNoIssues && (
              <div className="flex items-center gap-1.5 text-sm text-success ml-auto">
                <CheckCircle2 className="h-4 w-4" />
                모든 시스템 정상
              </div>
            )}
          </div>
        )}
      </div>

      {/* ZONE 2 — 주의 필요 (이슈가 있을 때만 표시) */}
      {attentionItems && attentionItems.length > 0 && (
        <Card className="py-2 gap-1 flex flex-col card-hover">
          <CardHeader className="px-4 pb-0">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm font-semibold flex items-center gap-2">
                <AlertTriangle className="h-4 w-4 text-warning" />
                주의 필요
                <span className="inline-flex items-center justify-center rounded-full bg-destructive/10 text-destructive text-[11px] font-semibold px-1.5 py-0 min-w-[1.25rem] h-[1.25rem] leading-none">
                  {attentionItems.length}
                </span>
              </CardTitle>
            </div>
          </CardHeader>
          <Separator />
          <CardContent className="px-3 pt-1 pb-2">
            <div
              className={`space-y-1.5 overflow-y-auto max-h-[11.5rem] ${THIN_SCROLLBAR}`}
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
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" onClick={() => navigate('/data/datasets/new')}>
          <Database className="h-4 w-4 mr-2" />
          새 데이터셋
        </Button>
        <Button variant="outline" size="sm" onClick={() => navigate('/pipelines')}>
          <ListChecks className="h-4 w-4 mr-2" />
          파이프라인 목록
        </Button>
        <Button variant="outline" size="sm" onClick={() => navigate('/analytics/queries')}>
          <Terminal className="h-4 w-4 mr-2" />
          SQL 편집기
        </Button>
        <Button variant="outline" size="sm" onClick={() => navigate('/analytics/dashboards')}>
          <Plus className="h-4 w-4 mr-2" />
          새 대시보드
        </Button>
      </div>

      {/* ZONE 4 + ZONE 5 — 2컬럼 */}
      <div className="grid gap-4 lg:grid-cols-5">
        {/* ZONE 4 — 최근 사용 (좌측 3cols, 2x2 위젯 그리드) */}
        <div className="lg:col-span-3 grid gap-4 sm:grid-cols-2">
          {/* 최근 대시보드 */}
          <Card className="py-2 gap-1 flex flex-col card-hover">
            <CardHeader className="px-3 pb-0">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm font-semibold flex items-center gap-2">
                  <LayoutDashboard className="h-4 w-4 text-muted-foreground" />
                  최근 대시보드
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
              ) : dashboardsData?.content && dashboardsData.content.length > 0 ? (
                <div className={`overflow-y-auto max-h-[13rem] ${THIN_SCROLLBAR}`}>
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
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm font-semibold flex items-center gap-2">
                  <Database className="h-4 w-4 text-muted-foreground" />
                  최근 데이터셋
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
              ) : datasetsData?.content && datasetsData.content.length > 0 ? (
                <div className={`overflow-y-auto max-h-[13rem] ${THIN_SCROLLBAR}`}>
                  {datasetsData.content.map((ds) => (
                    <button
                      key={ds.id}
                      className="w-full text-left flex items-center justify-between py-1.5 px-1.5 -mx-1.5 rounded hover:bg-accent transition-colors group"
                      onClick={() => navigate(`/data/datasets/${ds.id}`)}
                    >
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-sm truncate group-hover:text-foreground">{ds.name}</span>
                        <FreshnessBar lastUpdated={ds.createdAt} className="ml-2" />
                        <Badge variant="outline" className="shrink-0 text-xs px-1.5 py-0">
                          {ds.datasetType === 'SOURCE' ? '소스' : '파생'}
                        </Badge>
                      </div>
                    </button>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-muted-foreground py-1.5">
                  데이터셋이 없습니다.{' '}
                  <Link to="/data/datasets/new" className="text-primary hover:underline">만들기</Link>
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
                    최근 임포트
                  </CardTitle>
                  <span className="text-xs text-muted-foreground">총 {stats.recentImports.length}건</span>
                </div>
              </CardHeader>
              <Separator />
              <CardContent className="px-3 pt-1 pb-2">
                <div className={`overflow-y-auto max-h-[13rem] ${THIN_SCROLLBAR}`}>
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
          {stats && stats.recentExecutions.length > 0 && (
            <Card className="py-2 gap-1 flex flex-col card-hover">
              <CardHeader className="px-3 pb-0">
                <div className="flex items-center justify-between">
                  <CardTitle className="text-sm font-semibold flex items-center gap-2">
                    <GitBranch className="h-4 w-4 text-muted-foreground" />
                    최근 실행
                  </CardTitle>
                  <span className="text-xs text-muted-foreground">총 {stats.recentExecutions.length}건</span>
                </div>
              </CardHeader>
              <Separator />
              <CardContent className="px-3 pt-1 pb-2">
                <div className={`overflow-y-auto max-h-[13rem] ${THIN_SCROLLBAR}`}>
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
                활동 피드
              </CardTitle>
              {activityFeed?.totalCount != null && (
                <span className="text-xs text-muted-foreground">총 {activityFeed.totalCount}건</span>
              )}
            </div>
            {/* Filters */}
            <div className="flex flex-wrap items-center gap-3 pt-1">
              <div className="flex items-center gap-1.5">
                <span className="text-xs text-muted-foreground">유형</span>
                {[
                  { label: '전체', value: '' },
                  { label: '파이프라인', value: 'PIPELINE' },
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
            ) : activityFeed?.items && activityFeed.items.length > 0 ? (
              <div
                className={`overflow-y-auto h-[32rem] ${THIN_SCROLLBAR}`}
              >
                {activityFeed.items.map((item) => (
                  <div
                    key={item.id}
                    className={`flex items-start gap-2.5 py-2 border-b last:border-0 row-hover ${
                      !item.isResolved ? 'border-l-2 border-l-destructive pl-2.5' : 'pl-[12px]'
                    }`}
                  >
                    <div className="mt-0.5">
                      <ActivityIcon eventType={item.eventType} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm leading-tight">{item.title}</p>
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
                {activityFeed.hasMore && (
                  <div className="pt-2">
                    <Button
                      variant="outline"
                      size="sm"
                      className="w-full h-7 text-xs"
                      onClick={() =>
                        setActivityParams((prev) => ({
                          ...prev,
                          size: (prev.size ?? 20) + 20,
                        }))
                      }
                    >
                      더보기
                    </Button>
                  </div>
                )}
              </div>
            ) : (
              <div className="flex flex-col items-center gap-2 py-8 text-center">
                <Activity className="h-8 w-8 text-muted-foreground/50" />
                <p className="text-sm text-muted-foreground">활동 내역이 없습니다.</p>
                <Button variant="outline" size="sm" onClick={() => navigate('/pipelines')}>
                  파이프라인 실행하기
                </Button>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
