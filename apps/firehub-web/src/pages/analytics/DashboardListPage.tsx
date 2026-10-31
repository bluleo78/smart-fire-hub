import { useQueryClient } from '@tanstack/react-query';
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

import { analyticsApi } from '../../api/analytics';
import { DashboardShareNotice } from '../../components/analytics/DashboardShareNotice';
import { WorkspaceAnalyticsTable } from '../../components/analytics/WorkspaceAnalyticsTable';
import { EditConflictDialog } from '../../components/EditConflictDialog';
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
  useCanAdminAnalytics,
  useCreateDashboard,
  useDashboards,
  useDashboardShareImpact,
  useDeleteDashboard,
  useUpdateDashboard,
} from '../../hooks/queries/useAnalytics';
import { useIsOwner } from '../../hooks/useIsOwner';
import { handleApiError, isEditConflict } from '../../lib/api-error';
import { formatDateShort, formatRelativeTime } from '../../lib/formatters';
import { getPageAfterDelete } from '../../lib/pagination';
import { iGa } from '../../lib/utils';
import { type CreateDashboardRequest, type DashboardListItem, MAX_AUTO_REFRESH_SECONDS } from '../../types/analytics';

/**
 * "자동 새로고침 (초)" 입력 문자열을 검증한다.
 * 빈 문자열은 "수동"(null)으로 취급하고, 정수가 아니거나 5 미만이면 에러를 반환한다.
 * 24시간(86400초)을 넘으면 서버가 거부하므로 저장 전에 같은 문구로 막는다(WD-327).
 * 기존 parseInt(autoRefresh, 10)는 "5.5" 같은 소수점 입력을 조용히 "5"로 잘라
 * 사용자에게 알림 없이 다른 값이 저장되는 문제가 있었다(#568).
 */
function parseAutoRefreshInput(raw: string): { value: number | null } | { error: string } {
  const trimmed = raw.trim();
  if (!trimmed) return { value: null };
  const num = Number(trimmed);
  if (Number.isNaN(num) || !Number.isInteger(num) || num < 5) {
    return { error: '자동 새로고침은 5 이상의 정수(초)로 입력해 주세요.' };
  }
  if (num > MAX_AUTO_REFRESH_SECONDS) {
    return { error: `자동 새로고침은 ${MAX_AUTO_REFRESH_SECONDS}초(${MAX_AUTO_REFRESH_SECONDS / 3600}시간) 이하로 입력해 주세요.` };
  }
  return { value: num };
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
    const parsedAutoRefresh = parseAutoRefreshInput(autoRefresh);
    if ('error' in parsedAutoRefresh) {
      toast.error(parsedAutoRefresh.error);
      return;
    }
    submittingRef.current = true;
    const req: CreateDashboardRequest = {
      name: name.trim(),
      description: description.trim() || undefined,
      isShared,
      autoRefreshSeconds: parsedAutoRefresh.value,
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
              max={MAX_AUTO_REFRESH_SECONDS}
              step={1}
            />
          </div>
          <div className="space-y-1.5">
            <div className="flex items-center gap-2">
              <Switch id="dash-shared" checked={isShared} onCheckedChange={setIsShared} />
              <Label htmlFor="dash-shared">공개 대시보드</Label>
            </div>
            {/* 새 대시보드는 위젯이 없어 개수 없이 앞으로 넣는 차트·쿼리가 함께 공개된다고만 알린다(WD-325) */}
            <DashboardShareNotice wasShared={false} isShared={isShared} />
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
  // 공유 스위치 안내용 영향 개수(WD-325) — 창이 열려 있을 때만 센다. 실패하면 개수 없는 안내로 물러난다
  const { data: shareImpact } = useDashboardShareImpact(dashboard?.id ?? null);
  const queryClient = useQueryClient();
  // isPending 비동기 반영 지연으로 인한 중복 제출 방지용 동기 가드 (CreateDashboardDialog와 동일 패턴, #546)
  const submittingRef = useRef(false);
  /**
   * 지금 폼 내용의 기준 버전 — 그 내용을 채운(창을 연 / 다시 불러온) 시점의 서버 updatedAt(WD-364 낙관적 잠금).
   * 저장할 때 expectedUpdatedAt 으로 보내 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다.
   * 서버 문자열 그대로 둔다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다).
   */
  const [baseUpdatedAt, setBaseUpdatedAt] = useState<string | null>(dashboard?.updatedAt ?? null);
  // 마지막 저장이 편집 충돌로 거부됐는가 — 참이면 EditConflictDialog 를 설정 창 위에 띄운다
  const [editConflict, setEditConflict] = useState(false);
  // 충돌 창의 「다시 불러오기」 진행 중 — 저장 중과 함께 창의 버튼을 막는다
  const [isReloading, setIsReloading] = useState(false);

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
    // 폼을 채운 목록 행의 버전이 기준이다(WD-364)
    setBaseUpdatedAt(dashboard.updatedAt);
    setEditConflict(false);
  } else if (!dashboard && prevDashboard) {
    setPrevDashboard(null);
  }

  /** force: 기준 버전을 보내지 않아 서버가 비교하지 않는다 — 편집 충돌 뒤 「내 변경으로 덮어쓰기」(WD-364) */
  const handleSubmit = async (force = false) => {
    if (!dashboard || !name.trim() || submittingRef.current) return;
    const parsedAutoRefresh = parseAutoRefreshInput(autoRefresh);
    if ('error' in parsedAutoRefresh) {
      toast.error(parsedAutoRefresh.error);
      return;
    }
    submittingRef.current = true;
    try {
      await updateDashboard.mutateAsync({
        id: dashboard.id,
        data: {
          name: name.trim(),
          // 지운 설명은 빈 문자열로 보낸다 — undefined 면 필드가 빠져 서버가 "변경 없음"으로 보고 옛 설명을 남긴다(WD-197).
          description: description.trim(),
          isShared,
          autoRefreshSeconds: parsedAutoRefresh.value,
          // 값을 지워 "수동"으로 되돌리는 요청임을 명시. 백엔드는 autoRefreshSeconds가 null이어도
          // 이 플래그가 없으면 "미제공(변경 없음)"으로 해석해 기존 값을 유지한다(#568).
          clearAutoRefresh: parsedAutoRefresh.value === null,
          // 편집 기준 버전(WD-364) — 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다
          ...(force ? {} : { expectedUpdatedAt: baseUpdatedAt }),
        },
      });
      setEditConflict(false);
      toast.success(`대시보드 "${name}"${iGa(name)} 수정되었습니다.`);
      onClose();
    } catch (error) {
      // 편집 충돌은 토스트 대신 안내 창으로 — 사용자가 다시 불러올지 덮어쓸지 고른다(WD-364)
      if (isEditConflict(error)) {
        setEditConflict(true);
        return;
      }
      handleApiError(error, '대시보드 수정에 실패했습니다.');
    } finally {
      submittingRef.current = false;
    }
  };

  /**
   * 편집 충돌의 「다시 불러오기」(WD-364) — 최신 대시보드를 서버에서 새로 받아 폼과 기준 버전을 그것으로 바꾸고 내 변경을 버린다.
   * 목록 행은 낡았을 수 있어 상세를 읽는다. 설정 창은 열어 둔다 — 최신 값을 보고 이어 고칠 수 있게 한다.
   */
  const handleReloadLatest = async () => {
    if (!dashboard) return;
    setIsReloading(true);
    try {
      const latest = await queryClient.fetchQuery({
        queryKey: ['analytics', 'dashboards', dashboard.id],
        queryFn: () => analyticsApi.getDashboard(dashboard.id).then((r) => r.data),
        staleTime: 0,
      });
      setName(latest.name);
      setDescription(latest.description ?? '');
      setIsShared(latest.isShared);
      setAutoRefresh(latest.autoRefreshSeconds != null ? String(latest.autoRefreshSeconds) : '');
      setBaseUpdatedAt(latest.updatedAt);
      setEditConflict(false);
      // 목록 행(이름·공개 여부)도 최신으로 맞춘다
      queryClient.invalidateQueries({ queryKey: ['analytics', 'dashboards'] });
    } catch (error) {
      handleApiError(error, '최신 내용을 불러오지 못했습니다.');
    } finally {
      setIsReloading(false);
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
              max={MAX_AUTO_REFRESH_SECONDS}
              step={1}
            />
          </div>
          <div className="space-y-1.5">
            <div className="flex items-center gap-2">
              <Switch id="edit-dash-shared" checked={isShared} onCheckedChange={setIsShared} />
              <Label htmlFor="edit-dash-shared">공개 대시보드</Label>
            </div>
            {/* 함께 공개될·계속 공개로 남는 내 차트·쿼리 개수 안내(WD-325) */}
            <DashboardShareNotice
              wasShared={dashboard?.isShared ?? false}
              isShared={isShared}
              impact={shareImpact}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>취소</Button>
          <Button
            onClick={() => handleSubmit()}
            disabled={!name.trim() || updateDashboard.isPending}
          >
            저장
          </Button>
        </DialogFooter>
      </DialogContent>
      {/* 편집 충돌 안내 창(WD-364) — 저장이 409 EDIT_CONFLICT 로 거부되면 설정 창 위에 뜬다 */}
      <EditConflictDialog
        open={editConflict}
        onOpenChange={(open) => {
          if (!open) setEditConflict(false);
        }}
        resourceLabel="대시보드"
        onReload={handleReloadLatest}
        onOverwrite={() => handleSubmit(true)}
        pending={updateDashboard.isPending || isReloading}
      />
    </Dialog>
  );
}

/** 목록 보기 — 「워크스페이스 전체」는 analytics:admin 에게만 보인다(WD-199). */
type ListTab = 'mine' | 'shared' | 'workspace';

export default function DashboardListPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [tab, setTab] = useState<ListTab>('mine');
  // 관리자(analytics:admin)에게만 「워크스페이스 전체」 보기를 둔다 (WD-199) — 정지·제거된 작성자가 공유한 자산을 정리할 수 있게 한다.
  const { canAdmin } = useCanAdminAnalytics();
  const workspaceView = canAdmin && tab === 'workspace';
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [editDashboard, setEditDashboard] = useState<DashboardListItem | null>(null);
  const size = 10;

  const sharedOnly = tab === 'shared';
  // 「내 대시보드」 탭은 내가 만든 대시보드만 — 필터 없이 부르면 서버가 남의 공유 대시보드까지 섞어 준다(WD-328). 「공유됨」은 sharedOnly 그대로.
  const mineOnly = tab === 'mine';

  const { data: dashboardsData, isLoading } = useDashboards({
    search: search || undefined,
    sharedOnly: sharedOnly || undefined,
    mineOnly: mineOnly || undefined,
    page,
    size,
  });

  const deleteDashboard = useDeleteDashboard();
  // 남이 공유한 대시보드 줄에서는 편집·설정(이름·공유)·삭제를 숨긴다 — 서버가 작성자 외 요청을 404 로 거절한다(WD-196). 줄 클릭으로 보기는 그대로.
  const isOwner = useIsOwner();

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
          setTab(v as ListTab);
          setPage(0);
        }}
      >
        <TabsList>
          <TabsTrigger value="mine">내 대시보드</TabsTrigger>
          <TabsTrigger value="shared">공유됨</TabsTrigger>
          {canAdmin && <TabsTrigger value="workspace">워크스페이스 전체</TabsTrigger>}
        </TabsList>
      </Tabs>

      {workspaceView ? (
        <WorkspaceAnalyticsTable kind="dashboards" />
      ) : (
        <>
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
                          {formatRelativeTime(dashboard.updatedAt)}
                        </span>
                      </TableCell>
                      <TableCell>
                        {/* 행 작업 버튼 — 마우스를 올리거나 키보드 포커스가 안에 들어오면 보인다(WD-412) */}
                        {isOwner(dashboard.createdBy) && (
                          <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
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
                        )}
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
        </>
      )}

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
