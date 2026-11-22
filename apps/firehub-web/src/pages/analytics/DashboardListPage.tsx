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
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { analyticsApi } from '../../api/analytics';
import { DashboardShareNotice } from '../../components/analytics/DashboardShareNotice';
import { WorkspaceAnalyticsTable } from '../../components/analytics/WorkspaceAnalyticsTable';
import { EditConflictDialog } from '../../components/EditConflictDialog';
import { PageHeading } from '../../components/PageHeading';
import { TruncatedLink } from '../../components/TruncatedLink';
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
import { FormField } from '../../components/ui/form-field';
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
import { TableEmptyRow, TableErrorRow } from '../../components/ui/table-empty';
import { TableSkeletonRows } from '../../components/ui/table-skeleton';
import { Tabs, TabsControlsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
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
import { useSubmitGuard } from '../../hooks/useSubmitGuard';
import { extractApiError, handleApiError, isDuplicateName, isEditConflict, listLoadErrorProps } from '../../lib/api-error';
import { formatDateShort, formatRelativeTime } from '../../lib/formatters';
import { getPageAfterDelete } from '../../lib/pagination';
import { isIntegerSyntax } from '../../lib/settings-number';
import { iGa } from '../../lib/utils';
import {
  type CreateDashboardRequest,
  type DashboardListItem,
  MAX_AUTO_REFRESH_SECONDS,
  MAX_DASHBOARD_DESCRIPTION_LENGTH,
  MIN_AUTO_REFRESH_SECONDS,
} from '../../types/analytics';

/**
 * "자동 새로고침 (초)" 입력 원문을 검증한다 — 빈 칸(공백뿐 포함)은 "수동"(null), 부호 없는 십진 정수만 숫자로 받는다.
 * 칸이 type=number 면 브라우저가 덜 된 숫자(1e, 5., -)를 "" 로 넘겨 "수동"으로 저장돼 저장된 주기가 조용히 지워졌다.
 * 그래서 텍스트 칸의 원문을 직접 검증하고, IME 전각 숫자(３０)는 NFKC 로 반각으로 바꿔 받는다(WD-888).
 * 숫자 표기가 아니거나(5.5·1e3·+5) 5 미만이면 하한 문구, 24시간(86400초) 초과면 상한 문구다(#568, WD-327).
 */
function parseAutoRefreshInput(raw: string): { value: number | null } | { error: string } {
  const trimmed = raw.normalize('NFKC').trim();
  if (!trimmed) return { value: null };
  const num = Number(trimmed);
  if (!isIntegerSyntax(trimmed) || num < MIN_AUTO_REFRESH_SECONDS) {
    return { error: `자동 새로고침은 ${MIN_AUTO_REFRESH_SECONDS} 이상의 정수(초)로 입력해 주세요.` };
  }
  if (num > MAX_AUTO_REFRESH_SECONDS) {
    return { error: `자동 새로고침은 ${MAX_AUTO_REFRESH_SECONDS}초(${MAX_AUTO_REFRESH_SECONDS / 3600}시간) 이하로 입력해 주세요.` };
  }
  return { value: num };
}

// 자동 새로고침 칸 id — 칸에 주는 id 와 검증 실패 때 포커스를 옮길 id 가 어긋나지 않게 한 곳에 둔다(WD-876)
const CREATE_REFRESH_ID = 'dash-refresh';
const EDIT_REFRESH_ID = 'edit-dash-refresh';

/**
 * 검증에 걸린 칸으로 포커스를 옮긴다(WD-876) — 같은 잘못된 값으로 다시 눌러 오류 문구가 그대로여도
 * (React 가 같은 문구를 다시 그리지 않아 role=alert 가 다시 읽히지 않는다) 포커스가 칸으로 가 연결된 오류를 읽게 한다.
 * 제출 잠금(guardSubmit)에 넘기는 함수 안에서 ref 를 읽으면 react-hooks/refs 규칙에 걸려 id 로 찾는다.
 * 포커스는 오류가 그려진 **뒤**(setTimeout 0)에 옮긴다 — setRefreshError 직후 바로 옮기면 포커스가 닿는 순간 칸에
 * 아직 aria-invalid·오류 id 를 가리키는 aria-describedby 가 없어 화면낭독기가 라벨·안내만 읽고, 오류는 role=alert 도
 * 없어(announceError={false}) 처음 실패한 오류가 아예 읽히지 않는다(WD-876 리뷰 후속 반려).
 */
function focusById(id: string) {
  setTimeout(() => document.getElementById(id)?.focus(), 0);
}

/**
 * "자동 새로고침 (초)" 입력 칸 — 허용 범위 안내(description)와 검증 오류(error)를 칸 곁에 함께 보인다(WD-876).
 * 토스트는 몇 초 뒤 사라져 무엇이 틀렸는지 다시 볼 수 없고 칸과 이어지지 않아, 칸 아래 인라인 오류로 둔다.
 * 라벨·오류·안내의 aria 연결은 공용 FormField 가 한다(오류 → 안내 순으로 읽힌다).
 * 오류 때 화면이 이 칸으로 포커스를 옮기므로 오류 문구의 role=alert 는 끈다(announceError={false}) — 켜 두면
 * alert 낭독과 포커스 뒤 aria-describedby 낭독이 같은 문장을 두 번 읽는다(WD-876 리뷰 후속).
 */
function AutoRefreshField({
  id,
  value,
  onChange,
  error,
}: {
  id: string;
  value: string;
  onChange: (value: string) => void;
  error: string | null;
}) {
  return (
    <FormField
      label="자동 새로고침 (초)"
      htmlFor={id}
      error={error ?? undefined}
      announceError={false}
      description={`${MIN_AUTO_REFRESH_SECONDS}~${MAX_AUTO_REFRESH_SECONDS}초(${MAX_AUTO_REFRESH_SECONDS / 3600}시간) 사이의 정수. 비워 두면 수동 새로고침`}
    >
      <Input
        id={id}
        // 텍스트 칸으로 원문을 받는다 — 까닭은 parseAutoRefreshInput 주석(WD-888)
        type="text"
        inputMode="numeric"
        placeholder="비워두면 수동 새로고침"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </FormField>
  );
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
  // 이름 중복 409(WD-824)의 서버 문구 — 이름 칸 아래에 보이고 이름을 고치면 사라진다
  const [nameError, setNameError] = useState<string | null>(null);
  // 자동 새로고침 검증 오류(WD-876) — 토스트 대신 입력 칸 아래에 보이고 값을 고치면 사라진다
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const createDashboard = useCreateDashboard();
  // isPending은 mutateAsync 호출 후 리렌더를 거쳐야 반영되는 비동기 상태라,
  // 같은 틱에 도착하는 빠른 연속 클릭(더블클릭)을 막지 못한다(#546).
  // 클릭 즉시 동기로 잠그는 공용 제출 잠금으로 재진입을 막는다(WD-688 — 화면 전용 ref 플래그를 옮김).
  const guardSubmit = useSubmitGuard();

  const handleSubmit = guardSubmit(async () => {
    if (!name.trim()) return;
    setNameError(null);
    setRefreshError(null);
    const parsedAutoRefresh = parseAutoRefreshInput(autoRefresh);
    if ('error' in parsedAutoRefresh) {
      setRefreshError(parsedAutoRefresh.error);
      focusById(CREATE_REFRESH_ID);
      return;
    }
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
      setRefreshError(null);
    } catch (error) {
      // 이름 중복(409 DUPLICATE_NAME, WD-824)은 서버 문구를 이름 칸 아래에 보이고 토스트는 생략한다
      if (isDuplicateName(error)) {
        setNameError(extractApiError(error, '이미 있는 대시보드 이름입니다. 다른 이름을 입력하세요.'));
        return;
      }
      handleApiError(error, '대시보드 생성에 실패했습니다.');
    }
  });

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
              onChange={(e) => {
                setName(e.target.value);
                setNameError(null);
              }}
              maxLength={200}
              aria-invalid={nameError ? true : undefined}
              aria-describedby={nameError ? 'dash-name-error' : undefined}
            />
            {nameError && (
              <p id="dash-name-error" role="alert" className="text-sm text-destructive">
                {nameError}
              </p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="dash-desc">설명</Label>
            <Textarea
              id="dash-desc"
              placeholder="설명 (선택)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              // 서버 상한과 같은 길이까지만 입력받는다 — 넘으면 저장이 400 으로 거부된다(WD-425)
              maxLength={MAX_DASHBOARD_DESCRIPTION_LENGTH}
            />
          </div>
          <AutoRefreshField
            id={CREATE_REFRESH_ID}
            value={autoRefresh}
            onChange={(v) => {
              setAutoRefresh(v);
              setRefreshError(null);
            }}
            error={refreshError}
          />
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
            onClick={() => handleSubmit()}
            disabled={!name.trim() || createDashboard.isPending}
          >
            만들기
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
  // 이름 중복 409(WD-824)의 서버 문구 — 이름 칸 아래에 보이고 이름을 고치면 사라진다
  const [nameError, setNameError] = useState<string | null>(null);
  // 자동 새로고침 검증 오류(WD-876) — 토스트 대신 입력 칸 아래에 보이고 값을 고치면 사라진다
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const updateDashboard = useUpdateDashboard();
  // 공유 스위치 안내용 영향 개수(WD-325) — 창이 열려 있을 때만 센다. 실패하면 개수 없는 안내로 물러난다
  const { data: shareImpact } = useDashboardShareImpact(dashboard?.id);
  const queryClient = useQueryClient();
  // isPending 비동기 반영 지연으로 인한 중복 제출 방지용 동기 잠금 (CreateDashboardDialog와 동일, #546, WD-688).
  // 저장·「내 변경으로 덮어쓰기」가 같은 잠금을 나눠 써, 한쪽이 진행 중이면 다른 쪽을 무시한다.
  // 무시된 덮어쓰기는 진행 중 저장이 끝날 때 끝나므로 충돌 창이 그보다 먼저 잠금을 풀지 않는다(WD-678).
  const guardSubmit = useSubmitGuard();
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
    setNameError(null);
    setRefreshError(null);
  } else if (!dashboard && prevDashboard) {
    setPrevDashboard(null);
  }

  /** force: 기준 버전을 보내지 않아 서버가 비교하지 않는다 — 편집 충돌 뒤 「내 변경으로 덮어쓰기」(WD-364) */
  const submitSettings = async (force: boolean) => {
    if (!dashboard || !name.trim()) return;
    setNameError(null);
    setRefreshError(null);
    const parsedAutoRefresh = parseAutoRefreshInput(autoRefresh);
    if ('error' in parsedAutoRefresh) {
      setRefreshError(parsedAutoRefresh.error);
      focusById(EDIT_REFRESH_ID);
      return;
    }
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
      // 이름 중복(409 DUPLICATE_NAME, WD-824)은 서버 문구를 이름 칸 아래에 보이고 토스트는 생략한다
      if (isDuplicateName(error)) {
        setNameError(extractApiError(error, '이미 있는 대시보드 이름입니다. 다른 이름을 입력하세요.'));
        return;
      }
      handleApiError(error, '대시보드 수정에 실패했습니다.');
    }
  };
  const handleSave = guardSubmit(() => submitSettings(false));
  const handleOverwrite = guardSubmit(() => submitSettings(true));

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
      // 서버 값으로 바꿨으니 앞서 내 입력에 붙은 검증 오류는 지운다 — 남기면 올바른 값에 오류·aria-invalid 가 남는다(WD-876 리뷰 후속)
      setRefreshError(null);
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
              onChange={(e) => {
                setName(e.target.value);
                setNameError(null);
              }}
              maxLength={200}
              aria-invalid={nameError ? true : undefined}
              aria-describedby={nameError ? 'edit-dash-name-error' : undefined}
            />
            {nameError && (
              <p id="edit-dash-name-error" role="alert" className="text-sm text-destructive">
                {nameError}
              </p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="edit-dash-desc">설명</Label>
            <Textarea
              id="edit-dash-desc"
              placeholder="설명 (선택)"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              // 서버 상한과 같은 길이까지만 입력받는다 — 넘으면 저장이 400 으로 거부된다(WD-425)
              maxLength={MAX_DASHBOARD_DESCRIPTION_LENGTH}
            />
          </div>
          <AutoRefreshField
            id={EDIT_REFRESH_ID}
            value={autoRefresh}
            onChange={(v) => {
              setAutoRefresh(v);
              setRefreshError(null);
            }}
            error={refreshError}
          />
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
            onClick={() => handleSave()}
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
        onOverwrite={handleOverwrite}
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
  const [selectedTab, setSelectedTab] = useState<ListTab>('mine');
  // 관리자(analytics:admin)에게만 「워크스페이스 전체」 보기를 둔다 (WD-199) — 정지·제거된 작성자가 공유한 자산을 정리할 수 있게 한다.
  const { canAdmin } = useCanAdminAnalytics();
  // 관리자 권한이 사라져 「워크스페이스 전체」 탭이 없어지면 「내 …」로 되돌린다 — 없는 탭이 패널에 남지 않게(WD-514)
  const tab: ListTab = selectedTab === 'workspace' && !canAdmin ? 'mine' : selectedTab;
  const workspaceView = tab === 'workspace';
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [editDashboard, setEditDashboard] = useState<DashboardListItem | null>(null);
  const size = 10;

  const sharedOnly = tab === 'shared';
  // 「내 대시보드」 탭은 내가 만든 대시보드만 — 필터 없이 부르면 서버가 남의 공유 대시보드까지 섞어 준다(WD-328). 「공유됨」은 sharedOnly 그대로.
  const mineOnly = tab === 'mine';

  const { data: dashboardsData, isLoading, isError, error: dashboardsError } = useDashboards({
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
        <PageHeading>대시보드</PageHeading>
        <Button onClick={() => setCreateOpen(true)}>
          <Plus className="h-4 w-4" />
          새 대시보드
        </Button>
      </div>

      {/* 필터형 탭 — 목록을 TabsControlsContent 로 감싸 선택 탭이 실제 패널을 가리킨다(WD-514, ui/tabs.tsx). gap-6 은 이전 space-y-6 유지 */}
      <Tabs
        className="gap-6"
        value={tab}
        onValueChange={(v) => {
          setSelectedTab(v as ListTab);
          setPage(0);
        }}
      >
        {/* 탭 히트 영역 32px(WD-877)은 공용 tabs.tsx(가로 목록 h-10 + 트리거 min-h-8, WD-883)가 맡는다. */}
        <TabsList>
          <TabsTrigger value="mine">내 대시보드</TabsTrigger>
          <TabsTrigger value="shared">공유됨</TabsTrigger>
          {canAdmin && <TabsTrigger value="workspace">워크스페이스 전체</TabsTrigger>}
        </TabsList>

        <TabsControlsContent value={tab} className="space-y-6">
          {workspaceView ? (
            <WorkspaceAnalyticsTable kind="dashboards" />
          ) : (
            <>
              <div className="flex items-center gap-3">
                <SearchInput
                  placeholder="대시보드 검색..."
                  // 입력이 멈춘 뒤 한 번만 검색한다(WD-513) — 글자마다 목록 요청·페이지 초기화가 나가지 않게
                  debounceMs={300}
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
                      {/* WD-877 리뷰 후속: 선언 폭을 실제 내용에 맞춘다 — 버튼 32px×3 + 간격 4px×2 = 104px, 셀 좌우 여백 p-2(16px) 포함 120px.
                          (auto 표 레이아웃이라 80px 힌트도 내용 폭으로 늘었지만, 선언과 내용이 어긋나 읽는 사람을 헷갈리게 했다)
                          빈 머리는 스크린리더에 이름 없는 열로 읽혀 sr-only 「작업」 이름을 준다 */}
                      <TableHead className="w-[120px]">
                        <span className="sr-only">작업</span>
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <tbody>
                    {isLoading ? (
                      <TableSkeletonRows columns={5} rows={5} />
                    ) : isError && !dashboardsData ? (
                      // 목록 조회 실패 — 0건 안내로 오해되지 않게 실패 안내를 보인다. 받아 둔 목록이 있으면 재조회 실패여도 유지한다 (WD-978)
                      <TableErrorRow
                        colSpan={5}
                        // 403 은 권한 안내(흐린 톤), 그 밖은 서버 사유 — 권한 문제를 일시 장애처럼 보이지 않게 한다 (WD-978 리뷰 후속)
                        {...listLoadErrorProps(dashboardsError, '대시보드')}
                      />
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
                                  {/* WD-877 리뷰 후속: 작업 열이 32px 버튼 3개로 넓어져(120px) 768 폭에서 긴 이름+공유 배지 행이 표를 14px 넘겼다 —
                                      lg 미만에서는 이름 상한을 288px(18rem)로 줄여 표가 컨테이너 안에 들어오게 한다 */}
                                  {/* WD-933: 행 클릭만으로는 키보드 경로가 없어(남이 공유한 대시보드는 편집 버튼도 없다) 이름을 상세 링크로 둔다. title·말줄임은 TruncatedLink 가 한다 */}
                                  <TruncatedLink
                                    to={`/analytics/dashboards/${dashboard.id}`}
                                    value={dashboard.name}
                                    className="block max-w-[18rem] lg:max-w-xs"
                                  />
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
                            {/* 행 작업 버튼 — 마우스를 올리거나 키보드 포커스가 안에 들어오면 보인다(WD-412).
                                WD-877: 28px(h-7 w-7)였던 버튼을 icon-sm(32px)으로 키워 최소 히트 영역을 맞춘다 */}
                            {isOwner(dashboard.createdBy) && (
                              <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                                <Button
                                  variant="ghost"
                                  size="icon-sm"
                                  title="편집"
                                  aria-label="편집"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    navigate(`/analytics/dashboards/${dashboard.id}`);
                                  }}
                                >
                                  <Pencil className="size-3.5" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="icon-sm"
                                  title="설정"
                                  aria-label="설정"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setEditDashboard(dashboard);
                                  }}
                                >
                                  <Settings className="size-3.5" />
                                </Button>
                                <DeleteConfirmDialog
                                  entityName="대시보드"
                                  itemName={dashboard.name}
                                  onConfirm={() => handleDelete(dashboard.id, dashboard.name)}
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
        </TabsControlsContent>
      </Tabs>

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
