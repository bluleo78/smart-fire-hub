import { useDashboardStats } from '../hooks/queries/useDashboard';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { Link } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { Database, GitBranch, Upload, Play } from 'lucide-react';
import { getStatusBadgeVariant, getStatusLabel } from '../lib/formatters';

export default function HomePage() {
  const { user } = useAuth();
  const { data: stats, isLoading } = useDashboardStats();

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">대시보드</h1>
      <p className="text-muted-foreground">환영합니다, {user?.name}님!</p>

      {/* Stat cards grid */}
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        {/* Total datasets card */}
        <Link to="/data/datasets">
          <Card className="transition-colors hover:bg-accent">
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">데이터셋</CardTitle>
              <Database className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              {isLoading ? (
                <Skeleton className="h-8 w-20" />
              ) : (
                <>
                  <div className="text-2xl font-bold">{stats?.totalDatasets ?? 0}</div>
                  <p className="text-xs text-muted-foreground">
                    소스: {stats?.sourceDatasets ?? 0} / 파생: {stats?.derivedDatasets ?? 0}
                  </p>
                </>
              )}
            </CardContent>
          </Card>
        </Link>

        {/* Total pipelines card */}
        <Link to="/pipelines">
          <Card className="transition-colors hover:bg-accent">
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">파이프라인</CardTitle>
              <GitBranch className="h-4 w-4 text-muted-foreground" />
            </CardHeader>
            <CardContent>
              {isLoading ? (
                <Skeleton className="h-8 w-20" />
              ) : (
                <>
                  <div className="text-2xl font-bold">{stats?.totalPipelines ?? 0}</div>
                  <p className="text-xs text-muted-foreground">
                    활성: {stats?.activePipelines ?? 0}
                  </p>
                </>
              )}
            </CardContent>
          </Card>
        </Link>

        {/* Recent imports card */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">최근 임포트</CardTitle>
            <Upload className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {isLoading ? (
              <Skeleton className="h-8 w-20" />
            ) : (
              <div className="text-2xl font-bold">{stats?.recentImports.length ?? 0}</div>
            )}
          </CardContent>
        </Card>

        {/* Recent executions card */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">최근 실행</CardTitle>
            <Play className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            {isLoading ? (
              <Skeleton className="h-8 w-20" />
            ) : (
              <div className="text-2xl font-bold">{stats?.recentExecutions.length ?? 0}</div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Recent activity grid */}
      <div className="grid gap-4 md:grid-cols-2">
        {/* Recent imports table */}
        <Card>
          <CardHeader>
            <CardTitle>최근 임포트</CardTitle>
          </CardHeader>
          <CardContent>
            {isLoading ? (
              <div className="space-y-2">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : stats?.recentImports && stats.recentImports.length > 0 ? (
              <div className="space-y-3">
                {stats.recentImports.map((imp) => (
                  <div key={imp.id} className="flex items-start justify-between border-b pb-2 last:border-0">
                    <div className="flex-1 space-y-1">
                      <p className="text-sm font-medium">{imp.datasetName}</p>
                      <p className="text-xs text-muted-foreground">{imp.fileName}</p>
                      <p className="text-xs text-muted-foreground">
                        {new Date(imp.createdAt).toLocaleString('ko-KR')}
                      </p>
                    </div>
                    <Badge variant={getStatusBadgeVariant(imp.status)}>
                      {getStatusLabel(imp.status)}
                    </Badge>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">최근 임포트 내역이 없습니다.</p>
            )}
          </CardContent>
        </Card>

        {/* Recent executions table */}
        <Card>
          <CardHeader>
            <CardTitle>최근 실행</CardTitle>
          </CardHeader>
          <CardContent>
            {isLoading ? (
              <div className="space-y-2">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : stats?.recentExecutions && stats.recentExecutions.length > 0 ? (
              <div className="space-y-3">
                {stats.recentExecutions.map((exec) => (
                  <div key={exec.id} className="flex items-start justify-between border-b pb-2 last:border-0">
                    <div className="flex-1 space-y-1">
                      <p className="text-sm font-medium">{exec.pipelineName}</p>
                      <p className="text-xs text-muted-foreground">
                        {new Date(exec.createdAt).toLocaleString('ko-KR')}
                      </p>
                    </div>
                    <Badge variant={getStatusBadgeVariant(exec.status)}>
                      {getStatusLabel(exec.status)}
                    </Badge>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">최근 실행 내역이 없습니다.</p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
