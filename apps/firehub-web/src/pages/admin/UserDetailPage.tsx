import axios from 'axios';
import { ArrowLeft } from 'lucide-react';
import { useEffect,useState } from 'react';
import { useNavigate,useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { rolesApi } from '../../api/roles';
import { usersApi } from '../../api/users';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Checkbox } from '../../components/ui/checkbox';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { Switch } from '../../components/ui/switch';
import type { ErrorResponse } from '../../types/auth';
import type { RoleResponse } from '../../types/role';
import type { UserDetailResponse } from '../../types/user';

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [user, setUser] = useState<UserDetailResponse | null>(null);
  const [allRoles, setAllRoles] = useState<RoleResponse[]>([]);
  const [selectedRoleIds, setSelectedRoleIds] = useState<number[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSavingRoles, setIsSavingRoles] = useState(false);
  const [isTogglingActive, setIsTogglingActive] = useState(false);

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
        setSelectedRoleIds(userRes.data.roles.map(r => r.id));
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

  const handleSaveRoles = async () => {
    if (!user) return;
    setIsSavingRoles(true);
    try {
      await usersApi.setUserRoles(user.id, { roleIds: selectedRoleIds });
      const { data: updatedUser } = await usersApi.getUserById(user.id);
      setUser(updatedUser);
      setSelectedRoleIds(updatedUser.roles.map(r => r.id));
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

  const handleToggleActive = async () => {
    if (!user) return;
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
        <Button variant="ghost" size="icon" onClick={() => navigate('/admin/users')}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-2xl font-bold">사용자 상세</h1>
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
            <span>{new Date(user.createdAt).toLocaleDateString('ko-KR')}</span>
            <span className="text-muted-foreground">상태</span>
            <span>
              <Badge variant={user.isActive ? 'default' : 'secondary'}>
                {user.isActive ? '활성' : '비활성'}
              </Badge>
            </span>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>활성 상태</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center gap-3">
            <Switch
              checked={user.isActive}
              onCheckedChange={handleToggleActive}
              disabled={isTogglingActive}
            />
            <Label>{user.isActive ? '활성' : '비활성'}</Label>
          </div>
        </CardContent>
      </Card>

      <Separator />

      <Card>
        <CardHeader>
          <CardTitle>역할 할당</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-3">
            {allRoles.map((role) => (
              <div key={role.id} className="flex items-center gap-3">
                <Checkbox
                  id={`role-${role.id}`}
                  checked={selectedRoleIds.includes(role.id)}
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
            ))}
          </div>
          <Button onClick={handleSaveRoles} disabled={isSavingRoles}>
            {isSavingRoles ? '저장 중...' : '역할 저장'}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
