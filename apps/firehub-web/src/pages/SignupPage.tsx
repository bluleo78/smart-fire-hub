import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate } from 'react-router-dom';

import { FormField } from '@/components/ui/form-field';
import { Skeleton } from '@/components/ui/skeleton';
import { extractApiError } from '@/lib/api-error';
import type { ErrorResponse } from '@/types/auth';

import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardFooter, CardHeader } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { PasswordInput } from '../components/ui/password-input';
import { useSignupStatus } from '../hooks/queries/useSignupStatus';
import { useAuth } from '../hooks/useAuth';
import type { SignupFormData } from '../lib/validations/auth';
import { signupSchema } from '../lib/validations/auth';

export default function SignupPage() {
  const { signup, isAuthenticated } = useAuth();
  const [serverError, setServerError] = useState('');
  // 공개 가입 열림 여부 — 훅은 모든 조기 return 보다 앞에 둔다(훅 규칙)
  const signupStatus = useSignupStatus();
  const queryClient = useQueryClient();

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SignupFormData>({
    resolver: zodResolver(signupSchema),
  });

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  // 열림 여부를 확인하는 동안 폼/안내가 번쩍이지 않도록 카드 골격만 보인다(레이아웃 점프 방지).
  if (signupStatus.isLoading) {
    return (
      <div className="flex min-h-dvh items-center justify-center px-4">
        <Card className="w-full max-w-md">
          <CardContent className="space-y-4 p-6">
            <Skeleton className="h-8 w-40" />
            <Skeleton className="h-24 w-full" />
          </CardContent>
        </Card>
      </div>
    );
  }

  // 닫힘(또는 조회 실패) — 와이어프레임 ⑥: 안내 + 로그인 링크만.
  if (!signupStatus.data?.open) {
    return (
      <div className="flex min-h-dvh items-center justify-center px-4">
        <Card className="w-full max-w-md">
          <CardHeader className="space-y-1 text-center">
            <h1 className="text-2xl leading-8 font-semibold tracking-tight">회원가입</h1>
            <CardDescription className="break-keep">
              계정은 워크스페이스 관리자가 추가합니다. 관리자에게 계정 발급을 요청하세요.
            </CardDescription>
          </CardHeader>
          <CardFooter className="flex justify-center">
            <Button asChild variant="outline">
              <Link to="/login">로그인으로 이동</Link>
            </Button>
          </CardFooter>
        </Card>
      </div>
    );
  }

  const onSubmit = async (data: SignupFormData) => {
    try {
      setServerError('');
      await signup(data);
    } catch (error) {
      // 열림을 보고 들어왔지만 그 사이 첫 사용자가 생겼다 — 서버가 이미 "닫힘" 을 알려 줬으므로 다시 묻지 않고
      // 캐시를 닫힘으로 바꿔 안내 화면으로 전환한다(재조회에 기대면 그 조회가 실패할 때 폼이 아무 안내 없이 남는다).
      if (
        axios.isAxiosError(error) &&
        (error.response?.data as ErrorResponse | undefined)?.code === 'SIGNUP_DISABLED'
      ) {
        queryClient.setQueryData(['auth', 'signup-status'], { open: false });
        return;
      }
      // 서버가 필드별 errors 맵을 반환하면 각 필드에 인라인으로 표시
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        if (errData.errors && Object.keys(errData.errors).length > 0) {
          Object.entries(errData.errors).forEach(([field, message]) => {
            setError(field as keyof SignupFormData, { message });
          });
          return;
        }
      }
      setServerError(extractApiError(error, '회원가입에 실패했습니다.'));
    }
  };

  return (
    <div className="flex min-h-dvh items-center justify-center px-4">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          {/* CardTitle 은 components/ui/card.tsx 에서 <div> 를 렌더하므로 heading 이 아니다.
              인증 화면은 카드가 곧 페이지라 문서 개요에 h1 이 하나는 있어야 하므로
              CardTitle 대신 h1 을 직접 쓴다 (05-page-patterns §E, #433). */}
          <h1 className="text-2xl leading-8 font-semibold tracking-tight">회원가입</h1>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <FormField
              label="아이디 (이메일)"
              htmlFor="username"
              error={errors.username?.message}
            >
              <Input
                id="username"
                type="text"
                placeholder="email@example.com"
                autoComplete="username"
                {...register('username')}
              />
            </FormField>
            <FormField
              label="비밀번호"
              htmlFor="password"
              error={errors.password?.message}
            >
              <PasswordInput
                id="password"
                autoComplete="new-password"
                {...register('password')}
              />
            </FormField>
            <FormField
              label="비밀번호 확인"
              htmlFor="confirmPassword"
              error={errors.confirmPassword?.message}
            >
              {/* 비밀번호 일치 검증 — Zod .refine으로 password와 동일한지 확인 */}
              <PasswordInput
                id="confirmPassword"
                autoComplete="new-password"
                {...register('confirmPassword')}
              />
            </FormField>
            <FormField
              label="이름"
              htmlFor="name"
              error={errors.name?.message}
            >
              {/* maxLength={100}: DB 컬럼 제약과 맞춰 브라우저 레벨에서 입력 자체를 100자로 제한 */}
              <Input
                id="name"
                type="text"
                maxLength={100}
                autoComplete="name"
                {...register('name')}
              />
            </FormField>
            <FormField
              label="이메일 (선택)"
              htmlFor="email"
              error={errors.email?.message}
            >
              <Input
                id="email"
                type="email"
                placeholder="email@example.com"
                autoComplete="email"
                {...register('email')}
              />
            </FormField>
            {serverError && (
              <p className="text-sm text-destructive">{serverError}</p>
            )}
            <Button type="submit" className="w-full" disabled={isSubmitting}>
              {isSubmitting ? '회원가입 중...' : '회원가입'}
            </Button>
          </form>
          <div className="mt-4 text-center text-sm">
            <Link to="/login" className="text-primary underline-offset-4 hover:underline">
              이미 계정이 있으신가요? 로그인
            </Link>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
