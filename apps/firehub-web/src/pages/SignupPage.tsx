import { zodResolver } from '@hookform/resolvers/zod';
import axios from 'axios';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate } from 'react-router-dom';

import { FormField } from '@/components/ui/form-field';
import { extractApiError } from '@/lib/api-error';
import type { ErrorResponse } from '@/types/auth';

import { Button } from '../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Input } from '../components/ui/input';
import { PasswordInput } from '../components/ui/password-input';
import { useAuth } from '../hooks/useAuth';
import type { SignupFormData } from '../lib/validations/auth';
import { signupSchema } from '../lib/validations/auth';

export default function SignupPage() {
  const { signup, isAuthenticated } = useAuth();
  const [serverError, setServerError] = useState('');

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

  const onSubmit = async (data: SignupFormData) => {
    try {
      setServerError('');
      await signup(data);
    } catch (error) {
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
    <div className="flex min-h-screen items-center justify-center px-4">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center">
          <CardTitle className="text-2xl">회원가입</CardTitle>
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
