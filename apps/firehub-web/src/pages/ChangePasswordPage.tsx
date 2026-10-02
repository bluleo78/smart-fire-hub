import { zodResolver } from '@hookform/resolvers/zod';
import { Loader2 } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { Navigate, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { usersApi } from '../api/users';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardFooter, CardHeader } from '../components/ui/card';
import { FormField } from '../components/ui/form-field';
import { PasswordInput } from '../components/ui/password-input';
import { useAuth } from '../hooks/useAuth';
import { extractApiError } from '../lib/api-error';
import type { ChangePasswordFormData } from '../lib/validations/user';
import { forcedPasswordChangeSchema } from '../lib/validations/user';

/**
 * 첫 로그인 비밀번호 변경 강제 화면(WD-2, 와이어프레임 ⑤).
 *
 * <p>AppLayout 밖의 인증 레이아웃(디자인 시스템 05 §E)이다 — 변경 전에는 사이드바·헤더가 부르는 API 가
 * 전부 403 이므로 레이아웃을 그리지 않는다. 가드 순서는 스펙대로 인증 → 비밀번호 → 테넌트 — 테넌트가 없어도
 * 이 화면에서 변경할 수 있고, 성공 후 `/` 로 가면 ProtectedRoute 가 멤버십 1개면 바로 진입, 여러 개면
 * 워크스페이스 선택 화면을 보여 준다. 화면에서 벗어나는 길은 로그아웃 링크 하나뿐이다.
 */
export default function ChangePasswordPage() {
  const { isLoading, isAuthenticated, mustChangePassword, completePasswordChange, logout } = useAuth();
  const navigate = useNavigate();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordFormData>({
    resolver: zodResolver(forcedPasswordChangeSchema),
    mode: 'onBlur',
    reValidateMode: 'onChange',
  });

  // 부팅 refresh 가 끝나기 전에는 표식을 모른다 — 성급히 리다이렉트하지 않고 스피너만 보인다.
  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" aria-label="불러오는 중" />
      </div>
    );
  }
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  // 표식이 꺼진 사용자(이미 변경했거나 대상이 아님)가 주소로 들어오면 홈으로 돌려보낸다.
  if (!mustChangePassword) return <Navigate to="/" replace />;

  /** 로그아웃 후 로그인 화면으로 — 변경 화면의 유일한 탈출 경로. */
  const handleLogout = async () => {
    await logout();
    navigate('/login', { replace: true });
  };

  const onSubmit = async (data: ChangePasswordFormData) => {
    try {
      await usersApi.changePassword({ currentPassword: data.currentPassword, newPassword: data.newPassword });
    } catch (error) {
      // 현재(임시) 비밀번호가 틀리면 서버가 400 + 메시지를 준다 — 폼 상단 alert 로 보인다.
      setError('root', { message: extractApiError(error, '현재 비밀번호가 올바르지 않습니다') });
      return;
    }
    // 서버 표식은 이미 내려갔다 — refresh 로 pwc 가 꺼진 토큰을 받아야 다른 API 가 열린다.
    // refresh 는 테넌트를 유지(멤버십 1개면 로그인 때 자동 선택됨)하거나 null(여러 개)로 준다 →
    // '/' 에서 ProtectedRoute 가 바로 진입 또는 선택 화면으로 나눈다.
    try {
      await completePasswordChange();
    } catch {
      // 비밀번호는 이미 바뀌어 이 폼을 다시 제출하면 400 이다(데드엔드). refresh 가 실패했다면 세션을
      // 정리하고 새 비밀번호로 다시 로그인하게 보낸다. 변경 자체는 성공했으므로 error 가 아니라 비차단 경고(06 §D),
      // 문구는 토스트 규칙대로 한 문장·마침표 없음.
      toast.warning('비밀번호가 변경되어 새 비밀번호로 다시 로그인해야 합니다');
      await handleLogout();
      return;
    }
    toast.success('비밀번호가 변경되었습니다');
    navigate('/', { replace: true });
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <Card className="w-full max-w-md">
        <CardHeader className="space-y-1 text-center">
          {/* CardTitle 은 <div> 라 heading 이 아니다 — 인증 화면은 카드가 곧 페이지이므로 h1 을 직접 쓴다(#433). */}
          <h1 className="text-2xl leading-8 font-semibold tracking-tight">비밀번호 변경</h1>
          <CardDescription className="break-keep">관리자가 발급한 임시 비밀번호입니다. 계속하려면 새 비밀번호를 설정하세요.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
            {errors.root && (
              <p role="alert" className="text-sm text-destructive">
                {errors.root.message}
              </p>
            )}
            <FormField label="현재(임시) 비밀번호" htmlFor="current-password" error={errors.currentPassword?.message} required>
              <PasswordInput
                id="current-password"
                autoComplete="current-password"
                autoFocus
                aria-invalid={!!errors.currentPassword}
                {...register('currentPassword')}
              />
            </FormField>
            <FormField label="새 비밀번호" htmlFor="new-password" error={errors.newPassword?.message} required>
              <PasswordInput
                id="new-password"
                autoComplete="new-password"
                aria-invalid={!!errors.newPassword}
                aria-describedby="new-password-help"
                {...register('newPassword')}
              />
              <p id="new-password-help" className="text-[13px] text-muted-foreground">
                8~128자, 대·소문자·숫자 포함
              </p>
            </FormField>
            <FormField label="새 비밀번호 확인" htmlFor="confirm-password" error={errors.confirmPassword?.message} required>
              <PasswordInput
                id="confirm-password"
                autoComplete="new-password"
                aria-invalid={!!errors.confirmPassword}
                {...register('confirmPassword')}
              />
            </FormField>
            <Button type="submit" className="w-full" size="lg" disabled={isSubmitting}>
              {isSubmitting && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              변경하고 계속
            </Button>
          </form>
        </CardContent>
        <CardFooter className="flex justify-center">
          <Button variant="link" type="button" onClick={() => void handleLogout()}>
            로그아웃
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}
