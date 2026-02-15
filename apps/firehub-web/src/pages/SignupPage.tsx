import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, Navigate } from 'react-router-dom';
import { signupSchema } from '../lib/validations/auth';
import type { SignupFormData } from '../lib/validations/auth';
import { useAuth } from '../hooks/useAuth';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import type { ErrorResponse } from '../types/auth';
import axios from 'axios';

export default function SignupPage() {
  const { signup, isAuthenticated } = useAuth();
  const [serverError, setServerError] = useState('');

  const {
    register,
    handleSubmit,
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
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        setServerError(errData.message || '회원가입에 실패했습니다.');
      } else {
        setServerError('회원가입에 실패했습니다.');
      }
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
            <div className="space-y-2">
              <Label htmlFor="username">아이디 (이메일)</Label>
              <Input
                id="username"
                type="text"
                placeholder="email@example.com"
                {...register('username')}
              />
              {errors.username && (
                <p className="text-sm text-destructive">{errors.username.message}</p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">비밀번호</Label>
              <Input
                id="password"
                type="password"
                {...register('password')}
              />
              {errors.password && (
                <p className="text-sm text-destructive">{errors.password.message}</p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="name">이름</Label>
              <Input
                id="name"
                type="text"
                {...register('name')}
              />
              {errors.name && (
                <p className="text-sm text-destructive">{errors.name.message}</p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="email">이메일 (선택)</Label>
              <Input
                id="email"
                type="email"
                placeholder="email@example.com"
                {...register('email')}
              />
              {errors.email && (
                <p className="text-sm text-destructive">{errors.email.message}</p>
              )}
            </div>
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
