import {
  BarChart2,
  Pencil,
  Plus,
  Share2,
  Trash2,
} from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { DeleteConfirmDialog } from '../../components/ui/delete-confirm-dialog';
import { SearchInput } from '../../components/ui/search-input';
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
import { useCharts, useDeleteChart } from '../../hooks/queries/useAnalytics';
import { handleApiError } from '../../lib/api-error';
import { formatDateShort } from '../../lib/formatters';
import type { ChartType } from '../../types/analytics';

const CHART_TYPE_LABELS: Record<ChartType, string> = {
  BAR: '막대',
  LINE: '선',
  AREA: '영역',
  PIE: '파이',
  DONUT: '도넛',
  SCATTER: '산점도',
  TABLE: '테이블',
};

function getRelativeTime(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return '방금 전';
  if (mins < 60) return `${mins}분 전`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}일 전`;
  const months = Math.floor(days / 30);
  return `${months}개월 전`;
}

export default function ChartListPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [tab, setTab] = useState<'mine' | 'shared'>('mine');
  const [page, setPage] = useState(0);
  const size = 10;

  const sharedOnly = tab === 'shared';

  const { data: chartsData, isLoading } = useCharts({
    search: search || undefined,
    sharedOnly: sharedOnly || undefined,
    page,
    size,
  });

  const deleteChart = useDeleteChart();

  const charts = chartsData?.content ?? [];
  const totalPages = chartsData?.totalPages ?? 0;
  const totalElements = chartsData?.totalElements;

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteChart.mutateAsync(id);
      toast.success(`차트 "${name}"이(가) 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '차트 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">차트</h1>
        <Button onClick={() => navigate('/analytics/charts/new')}>
          <Plus className="mr-2 h-4 w-4" />
          새 차트
        </Button>
      </div>

      {/* Tabs */}
      <Tabs
        value={tab}
        onValueChange={(v) => {
          setTab(v as 'mine' | 'shared');
          setPage(0);
        }}
      >
        <TabsList>
          <TabsTrigger value="mine">내 차트</TabsTrigger>
          <TabsTrigger value="shared">공유됨</TabsTrigger>
        </TabsList>
      </Tabs>

      {/* Filters */}
      <div className="flex items-center gap-3 flex-wrap">
        <SearchInput
          placeholder="차트 검색..."
          value={search}
          onChange={(value) => {
            setSearch(value);
            setPage(0);
          }}
        />
      </div>

      {/* Table */}
      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>타입</TableHead>
              <TableHead>쿼리</TableHead>
              <TableHead>수정일</TableHead>
              <TableHead className="w-[80px]" />
            </TableRow>
          </TableHeader>
          <tbody>
            {isLoading ? (
              <TableSkeletonRows columns={5} rows={5} />
            ) : charts.length > 0 ? (
              charts.map((chart) => (
                <TableRow
                  key={chart.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors group"
                  onClick={() => navigate(`/analytics/charts/${chart.id}`)}
                >
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      <BarChart2 className="h-4 w-4 shrink-0 text-muted-foreground" />
                      <div>
                        <div className="flex items-center gap-1.5">
                          <span>{chart.name}</span>
                          {chart.isShared && (
                            <Badge variant="secondary" className="text-xs gap-1 py-0">
                              <Share2 className="h-2.5 w-2.5" />
                              공유
                            </Badge>
                          )}
                        </div>
                        {chart.description && (
                          <p className="text-xs text-muted-foreground truncate max-w-xs mt-0.5">
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
                      {CHART_TYPE_LABELS[chart.chartType] ?? chart.chartType}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <span className="text-sm text-muted-foreground truncate max-w-[200px] block">
                      {chart.savedQueryName}
                    </span>
                  </TableCell>
                  <TableCell>
                    <span
                      className="text-sm text-muted-foreground"
                      title={formatDateShort(chart.updatedAt)}
                    >
                      {getRelativeTime(chart.updatedAt)}
                    </span>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 w-7 p-0"
                        title="편집"
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/analytics/charts/${chart.id}`);
                        }}
                      >
                        <Pencil size={13} />
                      </Button>
                      <DeleteConfirmDialog
                        entityName="차트"
                        itemName={chart.name}
                        onConfirm={() => handleDelete(chart.id, chart.name)}
                        trigger={
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-7 w-7 p-0"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        }
                      />
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={5}
                message={search ? '검색 결과가 없습니다.' : '차트가 없습니다.'}
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
    </div>
  );
}
