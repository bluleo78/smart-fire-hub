import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { ArrowLeft } from 'lucide-react';
import { useEffect, useMemo,useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate,useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { permissionsApi } from '../../api/permissions';
import { rolesApi } from '../../api/roles';
import { EditConflictDialog } from '../../components/EditConflictDialog';
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
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { useMyPermissions } from '../../hooks/queries/useMyPermissions';
import { useAuth } from '../../hooks/useAuth';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { isEditConflict } from '../../lib/api-error';
import type { UpdateRoleFormData } from '../../lib/validations/role';
import { updateRoleSchema } from '../../lib/validations/role';
import type { ErrorResponse } from '../../types/auth';
import type { RoleDetailResponse } from '../../types/role';
import type { PermissionResponse } from '../../types/role';
import { RoleClearanceCard } from './components/RoleClearanceCard';

/** 권한 카테고리 표시 이름 — 없는 키는 원문 그대로. 보안 등급 S1 의 신규 카테고리(목업 s3 "보안"). */
const PERMISSION_CATEGORY_LABELS: Record<string, string> = { security: '보안' };
/**
 * 읽기 범위 안내를 붙일 권한. WD-29 이후 Python 스텝은 우회가 아니라 실행 주체 열람 등급 안에서만 읽으므로
 * 위험 경고 대신 등급 범위·허용 목록 등급 제약을 알린다.
 */
const PYTHON_READ_NOTICE_PERMISSIONS = new Set(['pipeline:python_execute']);

export default function RoleDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  // 화면 안 조작은 서버가 막는 권한 코드로만 노출한다(WD-121) — 서버에 관리자 우회가 없으므로 역할 이름은 보지 않는다.
  // 이름·설명·권한·열람 등급 저장 = role:write. 권한 체크박스에 쓸 전체 권한 목록(GET /permissions)은 permission:read.
  // 라우트 가드가 권한 조회를 끝낸 뒤에만 이 화면을 그리므로 여기서는 로딩을 따로 기다리지 않는다.
  const { permissions: myPermissions } = useMyPermissions();
  const { isAdmin: isCallerAdmin } = useAuth();
  const canWriteRole = myPermissions.has('role:write');
  // 전체 목록 조회 여부는 화면에 들어온 시점 값으로 고정한다 — 아래 조회 effect 가 이 값에 걸려 있어, 백그라운드 권한
  // 재조회로 값이 바뀌면 역할을 다시 읽으며 form.reset 으로 입력 중인 이름·설명과 체크가 지워진다. 바뀐 권한은 다음 진입부터
  // 반영된다. (라우트 가드가 권한 조회를 마친 뒤 그리므로 첫 렌더 값이 곧 확정값이다.)
  const [canReadCatalog] = useState(() => myPermissions.has('permission:read'));
  // 전체 목록이 없으면 빠진 권한을 보여 줄 수 없어 고를 수도 없다 — 권한 편집은 목록까지 읽을 수 있을 때만.
  const canEditPermissions = canWriteRole && canReadCatalog;
  const [role, setRole] = useState<RoleDetailResponse | null>(null);
  // 전체 권한 목록. null = 읽지 않음(permission:read 없음) — 이때는 이 역할이 가진 권한만 읽기 전용으로 보인다.
  const [catalog, setCatalog] = useState<PermissionResponse[] | null>(null);
  const [selectedPermissionIds, setSelectedPermissionIds] = useState<number[]>([]);
  // 권한 편집의 기준 목록(WD-440) — 불러올 때·권한을 저장할 때만 바뀐다. 이름 저장 등으로 role 을 다시 읽어도 바꾸지 않아야,
  // 그사이 다른 관리자가 회수한 권한을 옛 체크 상태가 「기준과 같다」로 통과시켜 되살리지 않는다.
  const [permissionBaselineIds, setPermissionBaselineIds] = useState<number[]>([]);
  // 권한 저장이 편집 충돌(409 EDIT_CONFLICT)로 거부됐는가(WD-440)
  const [isPermissionsConflictOpen, setIsPermissionsConflictOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isSavingRole, setIsSavingRole] = useState(false);
  const [isSavingPermissions, setIsSavingPermissions] = useState(false);
  const [roleError, setRoleError] = useState('');
  // 권한 전체 해제 확인 다이얼로그 표시 상태 — "권한 저장" 시점에 선택된 권한이 0개일 때만 열린다 (#567)
  // 사용자 상세(#512)의 "역할 전체 해제"와 동일한 위험 패턴: 이 역할을 사용 중인 모든 사용자가 즉시 권한을 전부 잃는다.
  const [isClearPermissionsDialogOpen, setIsClearPermissionsDialogOpen] = useState(false);

  const form = useForm<UpdateRoleFormData>({
    resolver: zodResolver(updateRoleSchema),
    defaultValues: {
      name: '',
      description: '',
    },
  });

  // 권한 체크박스가 마지막으로 저장된(불러온) 권한과 달라졌는지 (WD-118) — 순서 무관 비교.
  // 이름·설명 폼만 보던 가드가 권한 체크 변경을 몰라, 저장 없이 이동하면 경고 없이 버려졌다.
  // 집합으로 비교해 같은 id 가 두 번 들어가도 변경으로 보지 않는다.
  const permissionsDirty = useMemo(() => {
    const saved = new Set(role?.permissions.map(p => p.id) ?? []);
    const selected = new Set(selectedPermissionIds);
    return selected.size !== saved.size || [...selected].some(id => !saved.has(id));
  }, [selectedPermissionIds, role]);
  // 열람 등급 카드(RoleClearanceCard)는 자기 draft 를 따로 들고 있어 변경 여부를 콜백으로 받아 둔다 (WD-118).
  const [clearanceDirty, setClearanceDirty] = useState(false);

  // 이름/설명 폼·권한 체크·열람 등급이 저장되지 않은 채로 이탈하는 것을 막는 가드 (#636, WD-118) — 목록으로 돌아가기 버튼은
  // navigate() 대신 requestNavigate를 통해서만 이동해야 dirty 시 확인 다이얼로그가 뜬다.
  const { dialog: unsavedChangesDialog, requestNavigate } = useUnsavedChangesGuard(
    form.formState.isDirty || permissionsDirty || clearanceDirty,
  );

  useEffect(() => {
    const fetchData = async () => {
      if (!id) return;
      setIsLoading(true);
      try {
        // 전체 권한 목록은 permission:read 가 있을 때만 읽는다 — 역할 조회(role:read)만 있는 사람에게도 같이 읽으면
        // 403 이 역할 조회까지 실패로 만들어 화면이 열리지 않고 목록으로 튕겼다(WD-121).
        const [roleRes, permRes] = await Promise.all([
          rolesApi.getRoleById(Number(id)),
          canReadCatalog ? permissionsApi.getPermissions() : Promise.resolve(null),
        ]);
        setRole(roleRes.data);
        setCatalog(permRes?.data ?? null);
        setSelectedPermissionIds(roleRes.data.permissions.map(p => p.id));
        setPermissionBaselineIds(roleRes.data.permissions.map(p => p.id));
        form.reset({
          name: roleRes.data.name,
          description: roleRes.data.description ?? '',
        });
      } catch {
        toast.error('역할 정보를 불러오는데 실패했습니다.');
        navigate('/admin/roles');
      } finally {
        setIsLoading(false);
      }
    };

    fetchData();
  }, [id, navigate, form, canReadCatalog]);

  // 체크박스로 그릴 권한 — 전체 목록을 읽었으면 그것, 아니면 이 역할이 가진 권한만.
  const allPermissions = useMemo(() => catalog ?? role?.permissions ?? [], [catalog, role]);

  // 범위 밖 역할(WD-285) — ADMIN 이 아닌데 이 역할에 자기에게 없는 권한이 붙어 있으면, 서버는 이 역할의 권한 추가·제거를 모두 403 으로 막는다
  // (제거는 보유자 모두에게서의 회수라 삭제와 같은 범위 검사). 화면에서 미리 잠가 「빼 보니 거부」를 겪지 않게 한다. 등급·허용 목록으로 범위 밖인
  // 경우는 화면이 판정할 근거가 없어 서버 403 문구(토스트)로 안내한다.
  const isScopeLocked =
    canEditPermissions && !isCallerAdmin && !!role && role.permissions.some(p => !myPermissions.has(p.code));
  // 새로 붙일 수 없는 권한(WD-116 RoleGrantGuard) — ADMIN 이 아니면 자기에게 없는 권한은 역할에 추가할 수 없다. 범위 안 역할은 붙은 권한이
  // 모두 자기 것이라 「이미 저장된 권한」 예외를 따로 둘 필요가 없다.
  const isAddLocked = (perm: PermissionResponse) => !isCallerAdmin && !myPermissions.has(perm.code);
  const hasAddLocked = canEditPermissions && !isScopeLocked && allPermissions.some(isAddLocked);
  // 체크박스가 잠긴 이유(설명 요소 id) — 없으면 잠기지 않는다. disabled 와 aria-describedby 가 같은 판정을 쓰게 한 곳에 둔다.
  const lockHintId = (perm: PermissionResponse) => {
    if (!canEditPermissions) return undefined;
    if (isScopeLocked) return 'perm-scope-lock-hint';
    return isAddLocked(perm) ? 'perm-add-lock-hint' : undefined;
  };

  const permissionsByCategory = useMemo(() => {
    const grouped: Record<string, PermissionResponse[]> = {};
    for (const perm of allPermissions) {
      if (!grouped[perm.category]) {
        grouped[perm.category] = [];
      }
      grouped[perm.category].push(perm);
    }
    return grouped;
  }, [allPermissions]);

  const handlePermissionToggle = (permId: number, checked: boolean) => {
    // 이미 선택된 id 를 다시 넣지 않는다 — 중복 id 가 PUT 에 실리거나 변경 판정을 흐리지 않게.
    setSelectedPermissionIds(prev =>
      checked ? (prev.includes(permId) ? prev : [...prev, permId]) : prev.filter(id => id !== permId)
    );
  };

  const onRoleSubmit = async (data: UpdateRoleFormData) => {
    if (!role) return;
    setIsSavingRole(true);
    setRoleError('');
    try {
      await rolesApi.updateRole(role.id, {
        name: data.name,
        description: data.description || undefined,
      });
      // 저장한 값을 폼의 새 기준값으로 삼는다 (WD-119) — 안 하면 isDirty 가 true 로 남아 저장 직후 이동에도
      // 「저장하지 않은 변경사항」 경고가 뜬다. keepValues: 저장 요청 중에 더 입력한 내용은 지우지 않는다 —
      // reset 은 isDirty 를 false 로 두므로, 저장값과 달라진 칸은 setValue 로 다시 dirty 표시한다.
      const saved = { name: data.name, description: data.description ?? '' };
      form.reset(saved, { keepValues: true });
      for (const key of ['name', 'description'] as const) {
        const current = form.getValues(key) ?? '';
        // 이름은 스키마가 앞뒤 공백을 잘라 보낸다(WD-279) — 입력란에 남은 공백만 다르면 더 고친 게 아니므로 잘린 값으로 맞춘다.
        if (key === 'name' && current.trim() === saved.name) {
          form.setValue('name', saved.name);
          continue;
        }
        if (current !== saved[key]) form.setValue(key, current, { shouldDirty: true });
      }
      toast.success('역할 정보가 업데이트되었습니다.');
      try {
        const { data: updatedRole } = await rolesApi.getRoleById(role.id);
        setRole(updatedRole);
      } catch {
        // 저장은 이미 됐다 — 재조회만 실패했으면 실패 안내 대신 화면 값을 저장한 값으로 맞춘다.
        setRole(prev => (prev ? { ...prev, name: data.name, description: data.description || null } : prev));
      }
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        setRoleError(errData.message || '역할 업데이트에 실패했습니다.');
      } else {
        setRoleError('역할 업데이트에 실패했습니다.');
      }
    } finally {
      setIsSavingRole(false);
    }
  };

  /**
   * "권한 저장" 버튼 클릭 핸들러 — 선택된 권한이 0개(전체 해제)면 확인 다이얼로그를 먼저 띄우고,
   * 그 외에는 바로 저장을 진행한다. 이 역할을 사용 중인 모든 사용자가 즉시 권한을 전부 잃는
   * 파급력 큰 작업이므로 #512(사용자의 역할 전체 해제)와 동일한 확인 절차를 둔다 (#567).
   */
  const handleSavePermissionsClick = () => {
    if (selectedPermissionIds.length === 0) {
      setIsClearPermissionsDialogOpen(true);
      return;
    }
    void handleSavePermissions();
  };

  /** 서버의 최신 역할·권한으로 체크를 다시 맞춘다 — 권한 저장 뒤와 충돌 안내의 「다시 불러오기」가 쓴다(내 체크 변경은 버린다). */
  const reloadPermissions = async (roleId: number) => {
    const { data: updatedRole } = await rolesApi.getRoleById(roleId);
    setRole(updatedRole);
    const ids = updatedRole.permissions.map(p => p.id);
    setSelectedPermissionIds(ids);
    setPermissionBaselineIds(ids);
  };

  /**
   * 권한 저장. 기준 목록을 함께 보내 그사이 다른 관리자가 권한을 바꿨으면 서버가 409 로 거부한다(WD-440) — 예전엔 옛 화면에서 권한 하나를 더해
   * 저장하면 회수된 권한이 경고 없이 되살아났다. overwrite 는 충돌 안내의 「내 변경으로 덮어쓰기」다.
   */
  const handleSavePermissions = async (overwrite = false) => {
    if (!role) return;
    setIsSavingPermissions(true);
    try {
      await rolesApi.setPermissions(role.id, {
        permissionIds: selectedPermissionIds,
        ...(overwrite ? {} : { expectedPermissionIds: permissionBaselineIds }),
      });
      await reloadPermissions(role.id);
      setIsPermissionsConflictOpen(false);
      toast.success('권한이 저장되었습니다.');
    } catch (error) {
      if (isEditConflict(error)) {
        setIsPermissionsConflictOpen(true);
      } else if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '권한 저장에 실패했습니다.');
      } else {
        toast.error('권한 저장에 실패했습니다.');
      }
    } finally {
      setIsSavingPermissions(false);
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
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!role) return null;

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div className="flex items-center gap-4">
        {/* 역할 목록으로 돌아가는 뒤로가기 버튼 — 접근성 보강 (#102)
            (#636) navigate() 직접 호출 대신 requestNavigate 사용 — dirty 상태면 이탈 가드 다이얼로그를 띄운다 */}
        <Button variant="ghost" size="icon" onClick={() => requestNavigate('/admin/roles')} aria-label="목록으로 돌아가기" title="목록으로 돌아가기">
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">역할 상세</h1>
        {role.isSystem && (
          <Badge variant="outline">시스템 역할</Badge>
        )}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>역할 정보</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={form.handleSubmit(onRoleSubmit)} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="role-name">역할 이름</Label>
              <Input
                id="role-name"
                {...form.register('name')}
                disabled={role.isSystem || !canWriteRole}
              />
              {form.formState.errors.name && (
                <p className="text-sm text-destructive">{form.formState.errors.name.message}</p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="role-description">설명</Label>
              <Input
                id="role-description"
                {...form.register('description')}
                disabled={role.isSystem || !canWriteRole}
              />
            </div>
            {roleError && (
              <p className="text-sm text-destructive">{roleError}</p>
            )}
            {/* 시스템 역할은 수정 불가 — isSystem 플래그로 저장 버튼도 비활성화. role:write 가 없으면 버튼 자체를 숨긴다(WD-121). */}
            {canWriteRole && (
              <Button type="submit" disabled={isSavingRole || role.isSystem}>
                {isSavingRole ? '저장 중...' : '저장'}
              </Button>
            )}
          </form>
        </CardContent>
      </Card>

      {/* 데이터 열람 등급 — 권한과 성격이 달라 별도 Card·별도 저장(목업 s3) */}
      <RoleClearanceCard roleId={role.id} canEdit={canWriteRole} onDirtyChange={setClearanceDirty} />

      <Separator />

      {/* 권한 전체 해제 확인 AlertDialog — "권한 저장" 시점에 선택된 권한이 0개일 때만 표시된다 (#567) */}
      {/* 권한 저장 편집 충돌 안내(WD-440) — 다시 불러오기는 최신 권한으로 체크를 되돌리고, 덮어쓰기는 기준 없이 다시 저장한다 */}
      <EditConflictDialog
        open={isPermissionsConflictOpen}
        onOpenChange={setIsPermissionsConflictOpen}
        resourceLabel="역할의 권한"
        pending={isSavingPermissions}
        onReload={() => {
          if (!role) return;
          void reloadPermissions(role.id)
            .then(() => setIsPermissionsConflictOpen(false))
            .catch(() => toast.error('역할 정보를 불러오는데 실패했습니다.'));
        }}
        onOverwrite={() => void handleSavePermissions(true)}
      />

      <AlertDialog open={isClearPermissionsDialogOpen} onOpenChange={setIsClearPermissionsDialogOpen}>
        <AlertDialogContent size="sm">
          <AlertDialogHeader>
            <AlertDialogTitle>권한 전체 해제</AlertDialogTitle>
            <AlertDialogDescription>
              이 역할의 모든 권한을 해제하면 이 역할을 사용 중인 모든 사용자가 해당 권한을 즉시 잃습니다. 계속하시겠습니까?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                setIsClearPermissionsDialogOpen(false);
                void handleSavePermissions();
              }}
            >
              권한 해제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Card>
        <CardHeader>
          <CardTitle>권한 할당</CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {isScopeLocked && (
            <p id="perm-scope-lock-hint" className="text-[13px] text-muted-foreground">
              자신에게 없는 권한을 가진 역할이라 권한을 바꿀 수 없습니다
            </p>
          )}
          {hasAddLocked && (
            <p id="perm-add-lock-hint" className="text-[13px] text-muted-foreground">
              자신에게 없는 권한은 추가할 수 없습니다
            </p>
          )}
          {Object.entries(permissionsByCategory).map(([category, perms]) => (
            <div key={category} className="space-y-3">
              <h3 className="text-sm font-semibold uppercase text-muted-foreground">
                {PERMISSION_CATEGORY_LABELS[category] ?? category}
              </h3>
              <div className="space-y-2">
                {perms.map((perm) => (
                  // flex-wrap: 범위 안내처럼 긴 보조 문구가 설명을 좁은 칸으로 짜부라뜨리지 않고 다음 줄로 내려가게 한다
                  <div key={perm.id} className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <Checkbox
                      id={`perm-${perm.id}`}
                      checked={selectedPermissionIds.includes(perm.id)}
                      onCheckedChange={(checked) => handlePermissionToggle(perm.id, checked === true)}
                      disabled={role.isSystem || !canEditPermissions || lockHintId(perm) !== undefined}
                      aria-describedby={lockHintId(perm)}
                    />
                    <Label htmlFor={`perm-${perm.id}`} className="text-sm">
                      {perm.code}
                    </Label>
                    {perm.description && (
                      <span className="text-sm text-muted-foreground">- {perm.description}</span>
                    )}
                    {PYTHON_READ_NOTICE_PERMISSIONS.has(perm.code) && (
                      // 더 이상 위험 경고가 아니라 범위 안내라 warning 대신 muted 로 둔다(WD-29).
                      // 한 줄을 다 쓰고(basis-full) 체크박스 폭+간격(pl-7)만큼 들여 권한 이름과 왼쪽을 맞춘다.
                      <span className="basis-full pl-7 text-xs text-muted-foreground">
                        Python 스크립트는 실행 주체의 열람 등급 안의 데이터만 읽습니다(트리거 실행은 트리거를 만든 사용자
                        기준). 허용 목록이 필요한 등급은 읽을 수 없습니다.
                      </span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
          {allPermissions.length === 0 && (
            <p className="text-sm text-muted-foreground">{catalog ? '등록된 권한이 없습니다.' : '이 역할에 할당된 권한이 없습니다.'}</p>
          )}
          {/* 권한 저장은 role:write + 전체 목록(permission:read)이 있을 때만(WD-121) — 없으면 가진 권한만 읽기 전용으로 보인다. */}
          {canEditPermissions && (
            <Button
              onClick={handleSavePermissionsClick}
              disabled={isSavingPermissions || role.isSystem || isScopeLocked}
            >
              {isSavingPermissions ? '저장 중...' : '권한 저장'}
            </Button>
          )}
        </CardContent>
      </Card>

      {/* 미저장 변경 이탈 확인 다이얼로그 (#636) */}
      {unsavedChangesDialog}
    </div>
  );
}
