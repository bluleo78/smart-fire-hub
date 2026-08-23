import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { Flame } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { FormField } from '@/components/ui/form-field';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Input } from '@/components/ui/input';
import { PasswordInput } from '@/components/ui/password-input';
import { useAuth } from '@/hooks/useAuth';
import type { LoginFormData } from '@/lib/validations/auth';
import { loginSchema } from '@/lib/validations/auth';
import type { ErrorResponse } from '@/types/platform';

/**
 * 운영자 로그인.
 *
 * **D-1**: 오류 문구는 자격증명 오류와 "플랫폼 롤 없음"을 구별하지 않는다. 백엔드가 두 경우를
 * 같은 예외·같은 메시지로 던지는 것은 의도된 반(反)열거 설계이고, 프런트가 전용 문구를 만들면
 * 그 오라클을 다시 연다. 콘솔 정체성은 전부 로그인 **전** 화면(고정 라벨·안내문·문서 제목)에 둔다.
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const { login } = useAuth();
  const [serverError, setServerError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormData>({ resolver: zodResolver(loginSchema) });

  const onSubmit = async (data: LoginFormData) => {
    setServerError(null);
    setIsSubmitting(true);
    try {
      await login(data.username, data.password);
      navigate('/tenants', { replace: true });
    } catch (error) {
      // 401 은 서버 메시지를 그대로 쓴다 — 자격증명 오류·계정 잠금·비활성이 모두 여기로 온다.
      // 5xx/네트워크만 고정 문구로 대체한다(서버 메시지가 없거나 내부 사정이라 쓸모없다).
      if (axios.isAxiosError(error) && error.response?.status === 401) {
        const message = (error.response.data as ErrorResponse | undefined)?.message;
        setServerError(message ?? '아이디 또는 비밀번호가 올바르지 않습니다.');
      } else {
        setServerError('로그인 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <main className="flex min-h-screen items-center justify-center p-6">
      <div className="w-full max-w-sm space-y-6">
        <div className="flex flex-col items-center gap-1">
          <div className="flex items-center gap-2">
            <Flame className="h-6 w-6" aria-hidden />
            <span className="text-lg font-semibold">Smart Fire Hub</span>
          </div>
          {/* 평면 라벨(고정). 테넌트 브랜딩을 읽지 않는다 — 운영자 토큰에는 테넌트가 없다. */}
          <span className="text-sm text-muted-foreground">운영자 콘솔</span>
        </div>

        <Card>
          <CardContent className="pt-6">
            <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
              <FormField label="아이디" htmlFor="username" error={errors.username?.message}>
                <Input
                  id="username"
                  autoComplete="username"
                  disabled={isSubmitting}
                  {...register('username')}
                />
              </FormField>

              <FormField label="비밀번호" htmlFor="password" error={errors.password?.message}>
                <PasswordInput
                  id="password"
                  autoComplete="current-password"
                  disabled={isSubmitting}
                  {...register('password')}
                />
              </FormField>

              {serverError && <InlineBanner variant="warning">{serverError}</InlineBanner>}

              <Button type="submit" size="lg" className="w-full" disabled={isSubmitting}>
                {isSubmitting ? '로그인 중...' : '로그인'}
              </Button>
            </form>
          </CardContent>
        </Card>

        {/* 회원가입·비밀번호 찾기 링크를 두지 않는다 — 운영자 계정은 승격으로만 생긴다. */}
        <p className="text-center text-sm text-muted-foreground">
          이 콘솔은 플랫폼 운영자 전용입니다. 워크스페이스 사용자는 조직 주소로 로그인하세요.
        </p>
      </div>
    </main>
  );
}
