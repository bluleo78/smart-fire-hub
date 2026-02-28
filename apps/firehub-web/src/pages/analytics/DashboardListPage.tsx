import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDashboards, useDeleteDashboard } from '../../hooks/queries/useAnalytics';
import { useCreateDashboard } from '../../hooks/queries/useAnalytics';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import {
  Table,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { TableSkeletonRows } from '../../components/ui/table-skeleton';
import { TableEmptyRow } from '../../components/ui/table-empty';
import { DeleteConfirmDialog } from '../../components/ui/delete-confirm-dialog';
import { SimplePagination } from '../../components/ui/simple-pagination';
import { Tabs, TabsList, TabsTrigger } from '../../components/ui/tabs';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '../../components/ui/dialog';
import { Label } from '../../components/ui/label';
import { Textarea } from '../../components/ui/textarea';
import { Switch } from '../../components/ui/switch';
import {
  Plus,
  Trash2,
  Pencil,
  LayoutDashboard,
  Share2,
  RefreshCw,
} from 'lucide-react';
import { toast } from 'sonner';
import { handleApiError } from '../../lib/api-error';
import { formatDateShort } from '../../lib/formatters';
import type { CreateDashboardRequest } from '../../types/analytics';

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

interface CreateDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (id: number) => void;
}

function CreateDashboardDialog({ open, onOpenChange, onCreated }: CreateDialogProps) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [isShared, setIsShared] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState('');
  const createDashboard = useCreateDashboard();

  const handleSubmit = async () => {
    if (!name.trim()) return;
    const req: CreateDashboardRequest = {
      name: name.trim(),
      description: description.trim() || undefined,
      isShared,
      autoRefreshSeconds: autoRefresh ? parseInt(autoRefresh, 10) : null,
    };
    try {
      const result = await createDashboard.mutateAsync(req);
      toast.success(`대시보드 "${result.name}"이(가) 생성되었습니다.`);
      onCreated(result.id);
      onOpenChange(false);
      setName('');
      setDescription('');
      setIsShared(false);
      setAutoRefresh('');
    } catch (error) {
      handleApiError(error, '대시보드 생성에 실패했습니다.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>새 대시보드</DialogTitle>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="dash-name">이름 *</Label>
            <Input
              id="dash-name"
              placeholder="대시보드 이름"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="dash-desc">설명</Label>
            <Textarea
              id="dash-desc"
              placeholder="설명 (선택)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="dash-refresh">자동 새로고침 (초)</Label>
            <Input
              id="dash-refresh"
              type="number"
              placeholder="비워두면 수동 새로고침"
              value={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.value)}
              min={5}
            />
          </div>
          <div className="flex items-center gap-2">
            <Switch id="dash-shared" checked={isShared} onCheckedChange={setIsShared} />
            <Label htmlFor="dash-shared">공개 대시보드</Label>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>취소</Button>
          <Button
            onClick={handleSubmit}
            disabled={!name.trim() || createDashboard.isPending}
          >
            생성
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function DashboardListPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [tab, setTab] = useState<'mine' | 'shared'>('mine');
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const size = 10;

  const sharedOnly = tab === 'shared';

  const { data: dashboardsData, isLoading } = useDashboards({
    search: search || undefined,
    sharedOnly: sharedOnly || undefined,
    page,
    size,
  });

  const deleteDashboard = useDeleteDashboard();

  const dashboards = dashboardsData?.content ?? [];
  const totalPages = dashboardsData?.totalPages ?? 0;
  const totalElements = dashboardsData?.totalElements;

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteDashboard.mutateAsync(id);
      toast.success(`대시보드 "${name}"이(가) 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '대시보드 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">대시보드</h1>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="mr-2 h-4 w-4" />
          새 대시보드
        </Button>
      </div>

      <Tabs
        value={tab}
        onValueChange={(v) => {
          setTab(v as 'mine' | 'shared');
          setPage(0);
        }}
      >
        <TabsList>
          <TabsTrigger value="mine">내 대시보드</TabsTrigger>
          <TabsTrigger value="shared">공유됨</TabsTrigger>
        </TabsList>
      </Tabs>

      <div className="flex items-center gap-3">
        <div className="relative">
          <Input
            placeholder="대시보드 검색..."
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
            className="w-64"
          />
        </div>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>위젯</TableHead>
              <TableHead>자동 갱신</TableHead>
              <TableHead>수정일</TableHead>
              <TableHead className="w-[80px]" />
            </TableRow>
          </TableHeader>
          <tbody>
            {isLoading ? (
              <TableSkeletonRows columns={5} rows={5} />
            ) : dashboards.length > 0 ? (
              dashboards.map((dashboard) => (
                <TableRow
                  key={dashboard.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors group"
                  onClick={() => navigate(`/analytics/dashboards/${dashboard.id}`)}
                >
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      <LayoutDashboard className="h-4 w-4 shrink-0 text-muted-foreground" />
                      <div>
                        <div className="flex items-center gap-1.5">
                          <span>{dashboard.name}</span>
                          {dashboard.isShared && (
                            <Badge variant="secondary" className="text-xs gap-1 py-0">
                              <Share2 className="h-2.5 w-2.5" />
                              공유
                            </Badge>
                          )}
                        </div>
                        {dashboard.description && (
                          <p className="text-xs text-muted-foreground truncate max-w-xs mt-0.5">
                            {dashboard.description}
                          </p>
                        )}
                        <p className="text-xs text-muted-foreground mt-0.5">
                          {dashboard.createdByName}
                        </p>
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <span className="text-sm text-muted-foreground">
                      {dashboard.widgetCount}개
                    </span>
                  </TableCell>
                  <TableCell>
                    {dashboard.autoRefreshSeconds ? (
                      <Badge variant="outline" className="text-xs gap-1">
                        <RefreshCw className="h-2.5 w-2.5" />
                        {dashboard.autoRefreshSeconds}초
                      </Badge>
                    ) : (
                      <span className="text-xs text-muted-foreground">수동</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <span
                      className="text-sm text-muted-foreground"
                      title={formatDateShort(dashboard.updatedAt)}
                    >
                      {getRelativeTime(dashboard.updatedAt)}
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
                          navigate(`/analytics/dashboards/${dashboard.id}`);
                        }}
                      >
                        <Pencil size={13} />
                      </Button>
                      <DeleteConfirmDialog
                        entityName="대시보드"
                        itemName={dashboard.name}
                        onConfirm={() => handleDelete(dashboard.id, dashboard.name)}
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
                message={search ? '검색 결과가 없습니다.' : '대시보드가 없습니다.'}
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

      <CreateDashboardDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(id) => navigate(`/analytics/dashboards/${id}`)}
      />
    </div>
  );
}
