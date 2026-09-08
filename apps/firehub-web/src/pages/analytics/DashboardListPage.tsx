import {
  LayoutDashboard,
  Pencil,
  Plus,
  RefreshCw,
  Settings,
  Share2,
  Trash2,
} from 'lucide-react';
import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { DeleteConfirmDialog } from '../../components/ui/delete-confirm-dialog';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { SearchInput } from '../../components/ui/search-input';
import { SimplePagination } from '../../components/ui/simple-pagination';
import { Switch } from '../../components/ui/switch';
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
import { Textarea } from '../../components/ui/textarea';
import {
  useCreateDashboard,
  useDashboards,
  useDeleteDashboard,
  useUpdateDashboard,
} from '../../hooks/queries/useAnalytics';
import { handleApiError } from '../../lib/api-error';
import { formatDateShort } from '../../lib/formatters';
import { getPageAfterDelete } from '../../lib/pagination';
import { iGa } from '../../lib/utils';
import type { CreateDashboardRequest, DashboardListItem } from '../../types/analytics';

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
  // isPending은 mutateAsync 호출 후 리렌더를 거쳐야 반영되는 비동기 상태라,
  // 같은 틱에 도착하는 빠른 연속 클릭(더블클릭)을 막지 못한다.
  // 클릭 즉시 동기적으로 세팅되는 ref 플래그로 재진입을 차단한다.
  const submittingRef = useRef(false);

  const handleSubmit = async () => {
    if (!name.trim() || submittingRef.current) return;
    submittingRef.current = true;
    const req: CreateDashboardRequest = {
      name: name.trim(),
      description: description.trim() || undefined,
      isShared,
      autoRefreshSeconds: autoRefresh ? parseInt(autoRefresh, 10) : null,
    };
    try {
      const result = await createDashboard.mutateAsync(req);
      toast.success(`대시보드 "${result.name}"${iGa(result.name)} 생성되었습니다.`);
      onCreated(result.id);
      onOpenChange(false);
      setName('');
      setDescription('');
      setIsShared(false);
      setAutoRefresh('');
    } catch (error) {
      handleApiError(error, '대시보드 생성에 실패했습니다.');
    } finally {
      submittingRef.current = false;
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>새 대시보드</DialogTitle>
          <DialogDescription className="sr-only">새 대시보드 이름과 설명을 입력하여 생성합니다.</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="dash-name">이름 *</Label>
            <Input
              id="dash-name"
              placeholder="대시보드 이름"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={200}
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

/** 대시보드 메타데이터 편집 다이얼로그 */
interface EditDialogProps {
  dashboard: DashboardListItem | null;
  onClose: () => void;
}

function EditDashboardDialog({ dashboard, onClose }: EditDialogProps) {
  const [name, setName] = useState(dashboard?.name ?? '');
  const [description, setDescription] = useState(dashboard?.description ?? '');
  const [isShared, setIsShared] = useState(dashboard?.isShared ?? false);
  const [autoRefresh, setAutoRefresh] = useState(
    dashboard?.autoRefreshSeconds != null ? String(dashboard.autoRefreshSeconds) : '',
  );
  const updateDashboard = useUpdateDashboard();
  // isPending 비동기 반영 지연으로 인한 중복 제출 방지용 동기 가드 (CreateDashboardDialog와 동일 패턴, #546)
  const submittingRef = useRef(false);

  // dashboard prop 변경 시 폼 필드 초기화 (render-time 조정 패턴)
  // 취소 후 재오픈 시 부모가 동일 참조의 dashboard 객체를 다시 넘기므로
  // (null -> 객체 전환에서만) truthy 변경만 감지하면 리셋이 스킵된다(#553).
  // 닫힐 때(dashboard가 null이 됨) prevDashboard도 null로 되돌려 두면
  // 다음에 같은 참조로 다시 열려도 "null -> 객체" 전환으로 인식되어 리셋이 항상 일어난다.
  const [prevDashboard, setPrevDashboard] = useState(dashboard);
  if (dashboard && prevDashboard !== dashboard) {
    setPrevDashboard(dashboard);
    setName(dashboard.name);
    setDescription(dashboard.description ?? '');
    setIsShared(dashboard.isShared);
    setAutoRefresh(dashboard.autoRefreshSeconds != null ? String(dashboard.autoRefreshSeconds) : '');
  } else if (!dashboard && prevDashboard) {
    setPrevDashboard(null);
  }

  const handleSubmit = async () => {
    if (!dashboard || !name.trim() || submittingRef.current) return;
    submittingRef.current = true;
    try {
      await updateDashboard.mutateAsync({
        id: dashboard.id,
        data: {
          name: name.trim(),
          description: description.trim() || undefined,
          isShared,
          autoRefreshSeconds: autoRefresh ? parseInt(autoRefresh, 10) : null,
        },
      });
      toast.success(`대시보드 "${name}"${iGa(name)} 수정되었습니다.`);
      onClose();
    } catch (error) {
      handleApiError(error, '대시보드 수정에 실패했습니다.');
    } finally {
      submittingRef.current = false;
    }
  };

  return (
    <Dialog open={!!dashboard} onOpenChange={(open) => { if (!open) onClose(); }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>대시보드 설정</DialogTitle>
          <DialogDescription className="sr-only">대시보드 이름, 설명, 공개 여부, 자동 새로고침을 수정합니다.</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="edit-dash-name">이름 *</Label>
            <Input
              id="edit-dash-name"
              placeholder="대시보드 이름"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={200}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="edit-dash-desc">설명</Label>
            <Textarea
              id="edit-dash-desc"
              placeholder="설명 (선택)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="edit-dash-refresh">자동 새로고침 (초)</Label>
            <Input
              id="edit-dash-refresh"
              type="number"
              placeholder="비워두면 수동 새로고침"
              value={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.value)}
              min={5}
            />
          </div>
          <div className="flex items-center gap-2">
            <Switch id="edit-dash-shared" checked={isShared} onCheckedChange={setIsShared} />
            <Label htmlFor="edit-dash-shared">공개 대시보드</Label>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>취소</Button>
          <Button
            onClick={handleSubmit}
            disabled={!name.trim() || updateDashboard.isPending}
          >
            저장
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
  const [editDashboard, setEditDashboard] = useState<DashboardListItem | null>(null);
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
      // 현재 페이지의 마지막 항목을 삭제한 경우, 페이지를 앞으로 보정해
      // "검색 결과 없음" 오표시(#549)를 방지한다.
      setPage((prev) => getPageAfterDelete(dashboards.length, prev));
      toast.success(`대시보드 "${name}"${iGa(name)} 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '대시보드 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">대시보드</h1>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4" />
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
        <SearchInput
          placeholder="대시보드 검색..."
          value={search}
          onChange={(value) => {
            setSearch(value);
            setPage(0);
          }}
          className="w-64"
        />
      </div>

      <div className="rounded-md border">
        <Table aria-label="대시보드 목록">
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
                  className="cursor-pointer hover:bg-muted/50 transition-colors group row-hover"
                  onClick={() => navigate(`/analytics/dashboards/${dashboard.id}`)}
                >
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      <LayoutDashboard className="h-4 w-4 shrink-0 text-muted-foreground" />
                      {/* min-w-0: flex 아이템 기본값(min-width:auto)이 truncate 축소를 막는 문제 방지 */}
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5">
                          <span className="block truncate max-w-xs" title={dashboard.name}>
                            {dashboard.name}
                          </span>
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
                    <span className="text-sm text-muted-foreground tabular-nums">
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
                        aria-label="편집"
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/analytics/dashboards/${dashboard.id}`);
                        }}
                      >
                        <Pencil size={13} />
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 w-7 p-0"
                        title="설정"
                        aria-label="설정"
                        onClick={(e) => {
                          e.stopPropagation();
                          setEditDashboard(dashboard);
                        }}
                      >
                        <Settings size={13} />
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
                            aria-label="삭제"
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
                message="대시보드가 없습니다."
                searchKeyword={search || undefined}
                onResetSearch={search ? () => { setSearch(''); setPage(0); } : undefined}
                emptyAction={
                  !search ? (
                    <Button size="sm" variant="outline" onClick={() => setCreateOpen(true)}>
                      <Plus className="h-4 w-4" />
                      새 대시보드 만들기
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

      <CreateDashboardDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(id) => navigate(`/analytics/dashboards/${id}`)}
      />
      <EditDashboardDialog
        dashboard={editDashboard}
        onClose={() => setEditDashboard(null)}
      />
    </div>
  );
}
