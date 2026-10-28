import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import type { Location } from 'react-router-dom';
import { Link, Navigate, useLocation } from 'react-router-dom';

import { BrandLogo } from '../components/BrandLogo';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { PasswordInput } from '../components/ui/password-input';
import { useSignupStatus } from '../hooks/queries/useSignupStatus';
import { useAuth } from '../hooks/useAuth';
import { useBranding } from '../hooks/useBranding';
import type { LoginFormData } from '../lib/validations/auth';
import { loginSchema } from '../lib/validations/auth';
import type { ErrorResponse } from '../types/auth';

export default function LoginPage() {
  const { login, isAuthenticated } = useAuth();
  const { brandName } = useBranding(); // 런타임 브랜드명 (로그인 화면 제목)
  const [serverError, setServerError] = useState('');
  const location = useLocation();
  // 공개 가입 열림 여부 — 열림이 확인될 때만 가입 링크를 그린다(WD-2)
  const signupStatus = useSignupStatus();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
  });

  if (isAuthenticated) {
    // 딥링크 보존(#675): ProtectedRoute가 미인증 상태로 리다이렉트하며 실어 보낸
    // state.from이 있으면 원래 접근하려던 경로로, 없으면 기존처럼 홈으로 이동한다.
    // state.from은 ProtectedRoute의 useLocation()이 만든 내부 객체이므로 외부 입력으로
    // 조작 불가능하다(open redirect 위험 없음).
    const from = (location.state as { from?: Location } | null)?.from;
    const redirectTo = from ? `${from.pathname}${from.search}${from.hash}` : '/';
    return <Navigate to={redirectTo} replace />;
  }

  const onSubmit = async (data: LoginFormData) => {
    try {
      setServerError('');
      await login(data);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        // 서버 문구를 그대로 보여 준다 — 자격증명 오류·비활성 계정(WD-278) 등 401 의 이유는 서버만 안다. 예전에는 401 을
        // 고정 문구로 덮어 비활성 계정도 「비밀번호가 틀렸다」로 보여, 비밀번호를 의심해 다시 시도하다 잠금까지 갔다.
        // 운영자 콘솔 로그인과 같은 규칙. 문구가 없을 때만 상태별 기본 문구를 쓴다.
        const errData = error.response.data as ErrorResponse;
        const fallback =
          error.response.status === 401 ? '이메일 또는 비밀번호가 올바르지 않습니다.' : '로그인에 실패했습니다.';
        setServerError(errData.message || fallback);
      } else {
        setServerError('로그인에 실패했습니다.');
      }
    }
  };

  return (
    <div className="flex min-h-dvh items-center justify-center px-4">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          {/* 브랜드 로고 — logoUrl 설정 시 이미지, 없으면 기본 아이콘(Flame) */}
          <div className="mb-2 flex justify-center">
            <BrandLogo className="h-10 w-10" />
          </div>
          {/* CardTitle 은 components/ui/card.tsx 에서 <div> 를 렌더하므로 heading 이 아니다.
              인증 화면은 카드가 곧 페이지라 문서 개요에 h1 이 하나는 있어야 하므로
              CardTitle 대신 h1 을 직접 쓴다 (05-page-patterns §E, #433). */}
          <h1 className="text-2xl leading-8 font-semibold tracking-tight">{brandName}</h1>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="username">아이디 (이메일)</Label>
              <Input
                id="username"
                type="text"
                placeholder="email@example.com"
                autoComplete="username"
                {...register('username')}
              />
              {errors.username && (
                <p className="text-sm text-destructive">{errors.username.message}</p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">비밀번호</Label>
              <PasswordInput
                id="password"
                autoComplete="current-password"
                {...register('password')}
              />
              {errors.password && (
                <p className="text-sm text-destructive">{errors.password.message}</p>
              )}
            </div>
            {serverError && (
              <p className="text-sm text-destructive">{serverError}</p>
            )}
            <Button type="submit" className="w-full" disabled={isSubmitting}>
              {isSubmitting ? '로그인 중...' : '로그인'}
            </Button>
          </form>
          {/* 공개 가입은 시스템 첫 사용자만(WD-2). 열림이 확인될 때만 링크를 그린다(로딩·실패 = 숨김). */}
          {signupStatus.data?.open && (
            <div className="mt-4 text-center text-sm">
              <Link to="/signup" className="text-primary underline-offset-4 hover:underline">
                계정이 없으신가요? 회원가입
              </Link>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
