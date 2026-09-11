import axios from 'axios';
import { ArrowLeft } from 'lucide-react';
import { useEffect,useId,useMemo,useState } from 'react';
import { useNavigate,useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { rolesApi } from '../../api/roles';
import { usersApi } from '../../api/users';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../components/ui/alert-dialog';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Checkbox } from '../../components/ui/checkbox';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { Switch } from '../../components/ui/switch';
import { useAuth } from '../../hooks/useAuth';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { formatDateShort } from '../../lib/formatters';
import type { ErrorResponse } from '../../types/auth';
import type { RoleResponse } from '../../types/role';
import type { UserDetailResponse } from '../../types/user';

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user: currentUser } = useAuth();
  const [user, setUser] = useState<UserDetailResponse | null>(null);
  const [allRoles, setAllRoles] = useState<RoleResponse[]>([]);
  const [selectedRoleIds, setSelectedRoleIds] = useState<number[]>([]);
  // 저장 없이 이탈 시 유실 경고를 위한 dirty 비교 기준 스냅샷 — 로드/저장 성공 시마다 갱신 (#636)
  const [initialRoleIds, setInitialRoleIds] = useState<number[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSavingRoles, setIsSavingRoles] = useState(false);
  const [isTogglingActive, setIsTogglingActive] = useState(false);
  // 비활성화 확인 다이얼로그 표시 상태 — 활성→비활성 방향일 때만 열린다
  const [isDeactivateDialogOpen, setIsDeactivateDialogOpen] = useState(false);
  // 역할 전체 해제 확인 다이얼로그 표시 상태 — "역할 저장" 시점에 선택된 역할이 0개일 때만 열린다 (#512)
  const [isClearRolesDialogOpen, setIsClearRolesDialogOpen] = useState(false);
  // 접근성: 활성 상태 스위치의 라벨↔컨트롤 연결용 id (#432). 조기 return 이 아래에 있으므로 훅 구간에서 호출.
  const activeSwitchId = useId();

  useEffect(() => {
    const fetchData = async () => {
      if (!id) return;
      setIsLoading(true);
      try {
        const [userRes, rolesRes] = await Promise.all([
          usersApi.getUserById(Number(id)),
          rolesApi.getRoles(),
        ]);
        setUser(userRes.data);
        setAllRoles(rolesRes.data);
        const roleIds = userRes.data.roles.map(r => r.id);
        setSelectedRoleIds(roleIds);
        setInitialRoleIds(roleIds);
      } catch {
        toast.error('사용자 정보를 불러오는데 실패했습니다.');
        navigate('/admin/users');
      } finally {
        setIsLoading(false);
      }
    };

    fetchData();
  }, [id, navigate]);

  const handleRoleToggle = (roleId: number, checked: boolean) => {
    setSelectedRoleIds(prev =>
      checked ? [...prev, roleId] : prev.filter(id => id !== roleId)
    );
  };

  // 역할 체크박스가 로드된 초기값과 달라졌는지 여부 (#636) — 순서 무관 비교를 위해 정렬 후 비교
  const rolesDirty = useMemo(() => {
    if (selectedRoleIds.length !== initialRoleIds.length) return true;
    const a = [...selectedRoleIds].sort((x, y) => x - y);
    const b = [...initialRoleIds].sort((x, y) => x - y);
    return a.some((v, i) => v !== b[i]);
  }, [selectedRoleIds, initialRoleIds]);

  // "역할 저장" 없이 이탈 시 경고를 위한 가드 — 목록으로 돌아가기 버튼은 navigate() 대신
  // requestNavigate를 통해서만 이동해야 dirty 시 확인 다이얼로그가 뜬다 (#636).
  const { dialog: unsavedChangesDialog, requestNavigate } = useUnsavedChangesGuard(rolesDirty);

  /**
   * "역할 저장" 버튼 클릭 핸들러 — 선택된 역할이 0개(전체 해제)면 확인 다이얼로그를 먼저 띄우고,
   * 그 외에는 바로 저장을 진행한다. 역할 0개는 로그인 후 접근 가능한 기능이 없어질 수 있는
   * 활성 상태 비활성화와 동등하거나 더 위험한 변경이므로 동일한 확인 절차를 둔다 (#512).
   */
  const handleSaveRolesClick = () => {
    if (selectedRoleIds.length === 0) {
      setIsClearRolesDialogOpen(true);
      return;
    }
    void handleSaveRoles();
  };

  const handleSaveRoles = async () => {
    if (!user) return;
    setIsSavingRoles(true);
    try {
      await usersApi.setUserRoles(user.id, { roleIds: selectedRoleIds });
      const { data: updatedUser } = await usersApi.getUserById(user.id);
      setUser(updatedUser);
      const roleIds = updatedUser.roles.map(r => r.id);
      setSelectedRoleIds(roleIds);
      setInitialRoleIds(roleIds);
      toast.success('역할이 저장되었습니다.');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '역할 저장에 실패했습니다.');
      } else {
        toast.error('역할 저장에 실패했습니다.');
      }
    } finally {
      setIsSavingRoles(false);
    }
  };

  /**
   * 활성 상태 토글 실행 — AlertDialog 확인 후 호출되거나, 활성화(비활성→활성) 방향에서 직접 호출된다.
   * 자기 자신의 계정은 비활성화할 수 없다.
   */
  const handleToggleActive = async () => {
    if (!user) return;
    // 자기 자신의 계정 비활성화 차단 (#73)
    if (currentUser?.id === user.id && user.isActive) {
      toast.error('자신의 계정을 비활성화할 수 없습니다.');
      return;
    }
    setIsTogglingActive(true);
    try {
      await usersApi.setUserActive(user.id, { active: !user.isActive });
      setUser({ ...user, isActive: !user.isActive });
      toast.success(user.isActive ? '사용자가 비활성화되었습니다.' : '사용자가 활성화되었습니다.');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '상태 변경에 실패했습니다.');
      } else {
        toast.error('상태 변경에 실패했습니다.');
      }
    } finally {
      setIsTogglingActive(false);
    }
  };

  /**
   * Switch onCheckedChange 핸들러 — 비활성화 방향(활성→비활성)일 때 AlertDialog를 열고,
   * 활성화 방향(비활성→활성)일 때는 즉시 API를 호출한다. (#50)
   */
  const handleSwitchChange = () => {
    if (!user) return;
    if (user.isActive) {
      // 활성 → 비활성: 파괴적 액션이므로 확인 다이얼로그 표시
      setIsDeactivateDialogOpen(true);
    } else {
      // 비활성 → 활성: 즉시 처리
      void handleToggleActive();
    }
  };

  if (isLoading) {
    return (
      <div className="mx-auto max-w-2xl space-y-6">
        <Skeleton className="h-8 w-48" />
        <Card>
          <CardContent className="space-y-4 pt-6">
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-4 w-48" />
            <Skeleton className="h-4 w-56" />
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!user) return null;

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div className="flex items-center gap-4">
        {/* 사용자 목록으로 돌아가는 뒤로가기 버튼 — 접근성 보강 (#102)
            (#636) navigate() 직접 호출 대신 requestNavigate 사용 — dirty 상태면 이탈 가드 다이얼로그를 띄운다 */}
        <Button variant="ghost" size="icon" onClick={() => requestNavigate('/admin/users')} aria-label="목록으로 돌아가기" title="목록으로 돌아가기">
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">사용자 상세</h1>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>기본 정보</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
            <span className="text-muted-foreground">아이디</span>
            <span>{user.username}</span>
            <span className="text-muted-foreground">이름</span>
            <span>{user.name}</span>
            <span className="text-muted-foreground">이메일</span>
            <span>{user.email ?? '-'}</span>
            <span className="text-muted-foreground">가입일</span>
            <span>{formatDateShort(user.createdAt)}</span>
            <span className="text-muted-foreground">상태</span>
            <span>
              <Badge variant={user.isActive ? 'default' : 'secondary'}>
                {user.isActive ? '활성' : '비활성'}
              </Badge>
            </span>
          </div>
        </CardContent>
      </Card>

      {/* 비활성화 확인 AlertDialog — 활성→비활성 방향 토글 시에만 표시된다 (#50) */}
      <AlertDialog open={isDeactivateDialogOpen} onOpenChange={setIsDeactivateDialogOpen}>
        <AlertDialogContent size="sm">
          <AlertDialogHeader>
            <AlertDialogTitle>사용자 비활성화</AlertDialogTitle>
            <AlertDialogDescription>
              이 사용자를 비활성화하면 로그인이 불가합니다. 계속하시겠습니까?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                setIsDeactivateDialogOpen(false);
                void handleToggleActive();
              }}
            >
              비활성화
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Card>
        <CardHeader>
          <CardTitle>활성 상태</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-3">
            <Switch
              id={activeSwitchId}
              checked={user.isActive}
              onCheckedChange={handleSwitchChange}
              disabled={isTogglingActive}
            />
            <Label htmlFor={activeSwitchId}>{user.isActive ? '활성' : '비활성'}</Label>
          </div>
        </CardContent>
      </Card>

      <Separator />

      {/* 역할 전체 해제 확인 AlertDialog — "역할 저장" 시점에 선택된 역할이 0개일 때만 표시된다 (#512) */}
      <AlertDialog open={isClearRolesDialogOpen} onOpenChange={setIsClearRolesDialogOpen}>
        <AlertDialogContent size="sm">
          <AlertDialogHeader>
            <AlertDialogTitle>역할 전체 해제</AlertDialogTitle>
            <AlertDialogDescription>
              이 사용자의 모든 역할을 해제하면 로그인 후 접근 가능한 기능이 없을 수 있습니다. 계속하시겠습니까?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                setIsClearRolesDialogOpen(false);
                void handleSaveRoles();
              }}
            >
              역할 해제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Card>
        <CardHeader>
          <CardTitle>역할 할당</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-3">
            {allRoles.map((role) => {
              // 자기 자신의 ADMIN 역할 제거 차단 (#57)
              const isSelf = currentUser?.id === Number(id);
              const isOwnAdminRole = isSelf && role.name === 'ADMIN';
              return (
              <div key={role.id} className="flex items-center gap-3">
                <Checkbox
                  id={`role-${role.id}`}
                  checked={selectedRoleIds.includes(role.id)}
                  disabled={isOwnAdminRole}
                  onCheckedChange={(checked) => handleRoleToggle(role.id, checked === true)}
                />
                <Label htmlFor={`role-${role.id}`} className="flex items-center gap-2">
                  {role.name}
                  {role.isSystem && (
                    <Badge variant="outline" className="text-xs">시스템</Badge>
                  )}
                </Label>
                {role.description && (
                  <span className="text-sm text-muted-foreground">{role.description}</span>
                )}
              </div>
            );
            })}
          </div>
          <Button onClick={handleSaveRolesClick} disabled={isSavingRoles}>
            {isSavingRoles ? '저장 중...' : '역할 저장'}
          </Button>
        </CardContent>
      </Card>

      {/* 미저장 변경 이탈 확인 다이얼로그 (#636) */}
      {unsavedChangesDialog}
    </div>
  );
}
