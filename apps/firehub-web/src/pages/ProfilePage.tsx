import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';

import { FormField } from '@/components/ui/form-field';
import { applyFieldErrors, extractApiError } from '@/lib/api-error';

import { usersApi } from '../api/users';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Separator } from '../components/ui/separator';
import { useAuth } from '../hooks/useAuth';
import type { ChangePasswordFormData,UpdateProfileFormData } from '../lib/validations/user';
import { changePasswordSchema,updateProfileSchema } from '../lib/validations/user';

/**
 * 서버 문구에 한글이 있으면 그대로, 없으면(빈 문구·영어 문구 「Request timed out」 등) 한국어 대체 문구를 쓴다(WD-640).
 * 서버의 구체 문구는 살리되, 한국어 화면에 영어 원문이 새지 않게 한다.
 */
function koreanOr(message: string, fallback: string): string {
  return /[가-힣]/.test(message) ? message : fallback;
}

export default function ProfilePage() {
  const { user, refreshUser } = useAuth();

  const profileForm = useForm<UpdateProfileFormData>({
    resolver: zodResolver(updateProfileSchema),
    defaultValues: {
      name: '',
      email: '',
    },
  });

  // 비밀번호 변경 폼은 onChange 모드 — 새/확인 비밀번호 불일치를 입력 시점에 즉시 인라인으로 노출 (이슈 #70)
  const passwordForm = useForm<ChangePasswordFormData>({
    resolver: zodResolver(changePasswordSchema),
    mode: 'onChange',
    defaultValues: {
      currentPassword: '',
      newPassword: '',
      confirmPassword: '',
    },
  });

  useEffect(() => {
    if (user) {
      // email 필드가 null인 경우 username(로그인 이메일)으로 초기값을 채운다.
      // /api/v1/users/me의 email 컬럼이 null이어도 username을 통해 로그인 계정이 표시된다.
      profileForm.reset({
        name: user.name,
        email: user.email ?? user.username ?? '',
      });
    }
  }, [user, profileForm]);

  const onProfileSubmit = async (data: UpdateProfileFormData) => {
    try {
      profileForm.clearErrors();
      await usersApi.updateMe({
        name: data.name,
        email: data.email || undefined,
      });
      await refreshUser();
      toast.success('프로필이 업데이트되었습니다.');
    } catch (error) {
      // 검증 400 은 해당 입력 아래로 — 이름 오류가 이메일 칸 밑 폼 오류로 보이던 문제 (WD-275)
      if (applyFieldErrors(error, profileForm.setError, Object.keys(updateProfileSchema.shape))) return;
      // 필드 오류가 없으면 서버의 구체 문구(예: 이메일 중복, 서버 오류 안내)를 그대로 보인다(WD-640). 예전에는 문구가 비거나 영어면
      // 무조건 「입력값을 확인하세요.」라 네트워크 실패처럼 입력과 무관한 실패도 입력 탓으로 안내했다. 응답이 없거나 한국어가 아닌 문구
      // (게이트웨이·프록시의 영어 문구 등)면 일반 문구를 쓴다.
      profileForm.setError('root', {
        message: koreanOr(extractApiError(error, ''), '프로필을 저장하지 못했습니다. 잠시 후 다시 시도하세요.'),
      });
    }
  };

  const onPasswordSubmit = async (data: ChangePasswordFormData) => {
    try {
      passwordForm.clearErrors();
      await usersApi.changePassword({
        currentPassword: data.currentPassword,
        newPassword: data.newPassword,
      });
      passwordForm.reset();
      toast.success('비밀번호가 변경되었습니다.');
    } catch (error) {
      // 검증 400 은 해당 입력 아래로 (WD-270). 검증 실패 최상위 문구(「입력값이 올바르지 않습니다.」, 구버전 "Validation failed")는 항상 필드 errors 와 함께 오므로 여기서 끝난다(#72 대체 불필요).
      if (applyFieldErrors(error, passwordForm.setError, Object.keys(changePasswordSchema.shape))) return;
      // 현재 비밀번호 불일치는 서버가 「현재 비밀번호가 올바르지 않습니다」를 보내므로 서버 문구를 쓴다. 응답이 없을 때(네트워크 실패)
      // 대체 문구가 「현재 비밀번호가 올바르지 않습니다.」면 맞는 비밀번호를 넣은 사용자를 오도한다(WD-640).
      passwordForm.setError('root', {
        message: koreanOr(extractApiError(error, ''), '비밀번호를 변경하지 못했습니다. 잠시 후 다시 시도하세요.'),
      });
    }
  };

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">내 프로필</h1>

      <Card>
        <CardHeader>
          <CardTitle>프로필 정보</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={profileForm.handleSubmit(onProfileSubmit)} className="space-y-4">
            {/* 로그인 계정(username)은 변경 불가 — 사용자가 자신의 로그인 이메일을 항상 확인할 수 있도록 읽기 전용으로 표시한다 */}
            <FormField
              label="로그인 계정"
              htmlFor="profile-username"
            >
              <Input
                id="profile-username"
                type="text"
                // 로그인 계정 칸임을 브라우저·비밀번호 관리자에 알려 아래 비밀번호 변경 폼과 계정을 연결하게 한다 (WD-538)
                autoComplete="username"
                value={user?.username ?? ''}
                readOnly
                disabled
                className="bg-muted cursor-not-allowed"
              />
            </FormField>
            <FormField
              label="이름"
              htmlFor="profile-name"
              error={profileForm.formState.errors.name?.message}
            >
              {/* maxLength를 Zod 스키마·DB 컬럼(50자)과 일치시켜 브라우저 레벨에서 입력을 차단한다 (#26, WD-125) */}
              <Input
                id="profile-name"
                type="text"
                maxLength={50}
                // 이름 칸 표준 자동완성 값 — 가입 화면과 같게 맞춘다 (WD-538)
                autoComplete="name"
                {...profileForm.register('name')}
              />
            </FormField>
            <FormField
              label="이메일"
              htmlFor="profile-email"
              error={profileForm.formState.errors.email?.message}
            >
              <Input
                id="profile-email"
                type="email"
                placeholder="email@example.com"
                // 이메일 칸 표준 자동완성 값 — 가입 화면과 같게 맞춘다 (WD-538)
                autoComplete="email"
                {...profileForm.register('email')}
              />
            </FormField>
            {profileForm.formState.errors.root && (
              <p className="text-sm text-destructive">{profileForm.formState.errors.root.message}</p>
            )}
            <Button type="submit" disabled={profileForm.formState.isSubmitting}>
              {profileForm.formState.isSubmitting ? '저장 중...' : '저장'}
            </Button>
          </form>
        </CardContent>
      </Card>

      <Separator />

      <Card>
        <CardHeader>
          <CardTitle>비밀번호 변경</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={passwordForm.handleSubmit(onPasswordSubmit)} className="space-y-4">
            <FormField
              label="현재 비밀번호"
              htmlFor="current-password"
              error={passwordForm.formState.errors.currentPassword?.message}
            >
              <Input
                id="current-password"
                type="password"
                // 저장된 현재 비밀번호를 채울 수 있게 한다 — 강제 변경 화면과 같은 값 (WD-538)
                autoComplete="current-password"
                {...passwordForm.register('currentPassword')}
              />
            </FormField>
            <FormField
              label="새 비밀번호"
              htmlFor="new-password"
              error={passwordForm.formState.errors.newPassword?.message}
            >
              {/* 강제 변경 화면과 같은 규칙 안내 (WD-270) */}
              <Input
                id="new-password"
                type="password"
                // 새 비밀번호 칸임을 알려 비밀번호 관리자가 현재 값 대신 새 값 생성·저장을 제안하게 한다 (WD-538)
                autoComplete="new-password"
                aria-invalid={!!passwordForm.formState.errors.newPassword}
                aria-describedby={
                  passwordForm.formState.errors.newPassword ? 'new-password-help new-password-error' : 'new-password-help'
                }
                {...passwordForm.register('newPassword')}
              />
              <p id="new-password-help" className="text-[13px] text-muted-foreground">
                8~128자, 대·소문자·숫자 포함
              </p>
            </FormField>
            <FormField
              label="비밀번호 확인"
              htmlFor="confirm-password"
              error={passwordForm.formState.errors.confirmPassword?.message}
            >
              <Input
                id="confirm-password"
                type="password"
                // 확인 칸도 새 비밀번호와 같은 값으로 묶어 관리자가 같은 생성 값을 채우게 한다 (WD-538)
                autoComplete="new-password"
                {...passwordForm.register('confirmPassword')}
              />
            </FormField>
            {passwordForm.formState.errors.root && (
              <p className="text-sm text-destructive">{passwordForm.formState.errors.root.message}</p>
            )}
            <Button type="submit" disabled={passwordForm.formState.isSubmitting}>
              {passwordForm.formState.isSubmitting ? '변경 중...' : '비밀번호 변경'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
