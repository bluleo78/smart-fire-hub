import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { Flame } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useNavigate } from 'react-router-dom';

import { platformAuthApi } from '@/api/platform-auth';
import { PageSkeleton } from '@/components/PageSkeleton';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { FormField } from '@/components/ui/form-field';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Input } from '@/components/ui/input';
import { PasswordInput } from '@/components/ui/password-input';
import { SignupSessionError } from '@/hooks/signup-session-error';
import { useAuth } from '@/hooks/useAuth';
import { applyServerFieldErrors, serverMessage } from '@/lib/http-errors';
import { PASSWORD_HELP_TEXT } from '@/lib/temp-password';
import type { SignupFormData } from '@/lib/validations/auth';
import { signupSchema } from '@/lib/validations/auth';
import type { ErrorResponse } from '@/types/platform';

/** 부트스트랩 가능 여부 쿼리 키 — 로그인 화면과 같은 캐시를 공유한다. */
const BOOTSTRAP_KEY = ['platform-auth', 'bootstrap-status'];

/** 서버가 아는 가입 폼 칸 — 400 필드 오류를 이 칸 아래에 단다. confirmPassword 는 서버에 없다. */
const SERVER_FIELDS = ['username', 'name', 'email', 'password'] as const;

/**
 * 첫 운영자 가입(WD-749). 셸 밖 공개 화면이다.
 *
 * 사용자가 0명일 때만 열린다: 상태 조회가 false 이거나 실패하면 /login 으로 보낸다(진입점이 없는 화면을
 * 직접 주소로 열어도 가입 폼이 보이지 않게). 폼을 쓰는 사이 다른 사람이 먼저 가입하면 서버가
 * 403 SIGNUP_DISABLED 를 주고, 그때는 폼 대신 안내와 로그인 링크를 보인다.
 */
export default function SignupPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { signup, isAuthenticated } = useAuth();
  const [serverError, setServerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  // 그 사이 다른 사람이 가입해 서버가 가입을 닫은 경우 — 폼을 안내로 바꾼다.
  const [signupClosed, setSignupClosed] = useState(false);
  // 가입은 됐는데 세션을 여는 단계가 실패한 경우 — 폼 대신 "로그인해 주세요" 안내를 보인다.
  const [createdNeedsLogin, setCreatedNeedsLogin] = useState(false);

  const status = useQuery({
    queryKey: BOOTSTRAP_KEY,
    queryFn: async () => (await platformAuthApi.bootstrapStatus()).data,
    retry: false,
  });

  // 문서 제목 — index.html 의 정적 제목은 /login 용이라 이 화면에서만 바꾸고 떠날 때 되돌린다.
  useEffect(() => {
    const previous = document.title;
    document.title = '첫 운영자 계정 · 운영자 콘솔 · Smart Fire Hub';
    return () => {
      document.title = previous;
    };
  }, []);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<SignupFormData>({
    resolver: zodResolver(signupSchema),
    defaultValues: { username: '', name: '', email: '', password: '', confirmPassword: '' },
  });

  const onSubmit = async (data: SignupFormData) => {
    setServerError(null);
    setIsSubmitting(true);
    try {
      // confirmPassword 는 클라이언트 전용이라 보내지 않고, 빈 email 은 생략한다.
      const { username, name, email, password } = data;
      await signup(email ? { username, name, email, password } : { username, name, password });
      navigate('/tenants', { replace: true });
      // 가입이 됐으므로 로그인 화면이 가입 진입점을 숨기도록 캐시를 닫는다(403 경로와 같다).
      queryClient.setQueryData(BOOTSTRAP_KEY, { available: false });
    } catch (error) {
      if (error instanceof SignupSessionError) {
        // 계정은 만들어졌다 — "만들지 못했습니다" 가 아니라 로그인 안내.
        queryClient.setQueryData(BOOTSTRAP_KEY, { available: false });
        setCreatedNeedsLogin(true);
        return;
      }
      const code = axios.isAxiosError(error)
        ? (error.response?.data as ErrorResponse | undefined)?.code
        : undefined;
      if (axios.isAxiosError(error) && error.response?.status === 403 && code === 'SIGNUP_DISABLED') {
        // 로그인 화면이 이 캐시를 보고 진입점을 숨기도록 false 로 갱신한다.
        queryClient.setQueryData(BOOTSTRAP_KEY, { available: false });
        setSignupClosed(true);
        return;
      }
      const rest = applyServerFieldErrors(error, setError, SERVER_FIELDS, { shouldFocus: true });
      if (rest !== undefined) {
        // 폼에 칸이 없는 오류 문구는 버리지 않고 배너로 보인다.
        if (rest) setServerError(rest);
        return;
      }
      // 429(LOGIN_BUSY 등)는 서버 메시지를 그대로 쓴다. 그 밖은 고정 문구.
      setServerError(
        serverMessage(error, [429]) ?? '계정을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.',
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  // 이미 세션이 열렸으면(가입 직후 이동 중) 가입 불가 판정으로 /login 에 되돌려 보내지 않는다.
  if (!signupClosed && !createdNeedsLogin && !isAuthenticated) {
    if (status.isPending) return <PageSkeleton />;
    if (status.isError || !status.data.available) return <Navigate to="/login" replace />;
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-6">
      <div className="w-full max-w-sm space-y-6">
        <div className="flex flex-col items-center gap-1">
          <div className="flex items-center gap-2">
            <Flame className="h-6 w-6" aria-hidden />
            <span className="text-lg font-semibold">Smart Fire Hub</span>
          </div>
          <span className="text-sm text-muted-foreground">운영자 콘솔</span>
        </div>

        <Card>
          <CardContent className="space-y-4 pt-6">
            <div className="space-y-1">
              <h1 className="text-xl font-semibold">첫 운영자 계정 만들기</h1>
              <p className="text-sm text-muted-foreground">
                이 계정은 플랫폼 최고 관리자(SUPER_ADMIN)가 되고 기본 워크스페이스 관리자도 겸합니다.
              </p>
            </div>

            {createdNeedsLogin ? (
              <div className="space-y-3">
                <InlineBanner variant="info">계정은 만들어졌습니다. 로그인해 주세요.</InlineBanner>
                <Link to="/login" className="block text-center text-sm font-medium underline">
                  로그인으로 이동
                </Link>
              </div>
            ) : signupClosed ? (
              <div className="space-y-3">
                <InlineBanner variant="info">이미 운영자 계정이 있습니다. 로그인하세요.</InlineBanner>
                <Link to="/login" className="block text-center text-sm font-medium underline">
                  로그인으로 이동
                </Link>
              </div>
            ) : (
              <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
                <FormField label="아이디(이메일)" htmlFor="username" error={errors.username?.message}>
                  <Input
                    id="username"
                    autoComplete="username"
                    autoCapitalize="none"
                    autoCorrect="off"
                    spellCheck={false}
                    disabled={isSubmitting}
                    {...register('username')}
                  />
                </FormField>

                <FormField label="이름" htmlFor="name" error={errors.name?.message}>
                  <Input id="name" autoComplete="name" disabled={isSubmitting} {...register('name')} />
                </FormField>

                <FormField label="이메일(선택)" htmlFor="email" error={errors.email?.message}>
                  <Input
                    id="email"
                    type="email"
                    autoComplete="email"
                    disabled={isSubmitting}
                    {...register('email')}
                  />
                </FormField>

                <FormField label="비밀번호" htmlFor="password" error={errors.password?.message}>
                  <PasswordInput
                    id="password"
                    autoComplete="new-password"
                    disabled={isSubmitting}
                    {...register('password')}
                  />
                  <p className="text-xs text-muted-foreground">{PASSWORD_HELP_TEXT}</p>
                </FormField>

                <FormField
                  label="비밀번호 확인"
                  htmlFor="confirmPassword"
                  error={errors.confirmPassword?.message}
                >
                  <PasswordInput
                    id="confirmPassword"
                    autoComplete="new-password"
                    disabled={isSubmitting}
                    {...register('confirmPassword')}
                  />
                </FormField>

                {serverError && <InlineBanner variant="warning">{serverError}</InlineBanner>}

                <Button type="submit" size="lg" className="w-full" disabled={isSubmitting}>
                  {isSubmitting ? '만드는 중...' : '계정 만들기'}
                </Button>
              </form>
            )}
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
