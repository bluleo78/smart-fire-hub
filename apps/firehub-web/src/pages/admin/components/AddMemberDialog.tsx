import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { AlertTriangle, CheckCircle2, Copy, Loader2, Plus, RefreshCw } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';

import { rolesApi } from '../../../api/roles';
import { usersApi } from '../../../api/users';
import { Button } from '../../../components/ui/button';
import { Checkbox } from '../../../components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '../../../components/ui/dialog';
import { FormField } from '../../../components/ui/form-field';
import { InlineBanner } from '../../../components/ui/inline-banner';
import { Input } from '../../../components/ui/input';
import { Label } from '../../../components/ui/label';
import { Skeleton } from '../../../components/ui/skeleton';
import { extractApiError } from '../../../lib/api-error';
import { generateTemporaryPassword } from '../../../lib/temp-password';
import type { AddMemberFormData } from '../../../lib/validations/user';
import { addMemberSchema } from '../../../lib/validations/user';
import type { ErrorResponse } from '../../../types/auth';

/** 새 계정 생성 직후 1회만 보여 줄 자격 증명. 닫으면 버린다(어디에도 저장·로그하지 않는다). */
interface CreatedCredentials {
  username: string;
  password: string;
}

/** 폼 초기값 — 닫을 때도 이 값으로 되돌려 임시 비밀번호가 폼 상태에 남지 않게 한다. */
const EMPTY_FORM: AddMemberFormData = { email: '', name: '', temporaryPassword: '', roleIds: [] };

/** 클립보드 복사 + 디자인 시스템 토스트 규칙(06 §D: 복사 확인은 toast.info). */
async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    toast.info('클립보드에 복사되었습니다');
  } catch {
    toast.error('복사하지 못했습니다');
  }
}

/**
 * 멤버 추가 다이얼로그(WD-2, 와이어프레임 ②③).
 *
 * <p>두 단계: 입력 폼 → (새 계정이면) 자격 증명 1회 표시. 기존 계정이면 토스트만 띄우고 닫는다.
 * USER 는 워크스페이스 기본 역할로 서버가 항상 붙이므로 체크·비활성(해제 불가)으로 보여 주고 전송 목록에는
 * 넣지 않는다. 선택 역할은 USER 에 추가된다. 문구는 스펙 §4 인용문 기준(pre-flight F8).
 *
 * @param canAssignRoles role:assign 보유 여부 — 없으면 다른 역할을 고를 수 없고 roleIds 는 빈 배열
 */
export function AddMemberDialog({ canAssignRoles }: { canAssignRoles: boolean }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [created, setCreated] = useState<CreatedCredentials | null>(null);
  const [suspendedUserId, setSuspendedUserId] = useState<number | null>(null);
  const emailRef = useRef<HTMLInputElement | null>(null);
  const copyAllRef = useRef<HTMLButtonElement | null>(null);

  // 다이얼로그를 열 때만 역할 목록을 읽는다(목록 페이지 진입만으로 불필요한 호출을 만들지 않음).
  const rolesQuery = useQuery({
    queryKey: ['roles'],
    queryFn: () => rolesApi.getRoles().then((r) => r.data),
    enabled: open,
  });

  const form = useForm<AddMemberFormData>({
    resolver: zodResolver(addMemberSchema),
    mode: 'onBlur',
    reValidateMode: 'onChange',
    defaultValues: EMPTY_FORM,
  });
  const {
    register,
    handleSubmit,
    control,
    setValue,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = form;
  // 이메일을 고치면 정지 멤버 상세 링크는 더 이상 그 입력에 대한 것이 아니므로 숨긴다.
  const emailField = register('email', { onChange: () => setSuspendedUserId(null) });

  // 결과 화면으로 바뀌면 제출 버튼이 사라져 포커스가 다이얼로그 컨테이너로 떨어진다 — 다음 행동(복사)으로 옮긴다.
  useEffect(() => {
    if (created) copyAllRef.current?.focus();
  }, [created]);

  /** 열 때마다 새 임시 비밀번호로 초기화, 닫을 때 결과·오류·입력을 모두 버린다(비밀번호 재노출 방지). */
  const handleOpenChange = (next: boolean) => {
    // 제출 중 닫으면 응답이 닫힌 뒤 도착해 결과 화면 상태가 남는다 — 응답을 기다린다.
    if (!next && isSubmitting) return;
    setOpen(next);
    setCreated(null);
    setSuspendedUserId(null);
    reset(next ? { ...EMPTY_FORM, temporaryPassword: generateTemporaryPassword() } : EMPTY_FORM);
  };

  const onSubmit = async (values: AddMemberFormData) => {
    const email = values.email.trim();
    try {
      const { data } = await usersApi.addMember({
        email,
        name: values.name.trim(),
        temporaryPassword: values.temporaryPassword,
        roleIds: canAssignRoles ? values.roleIds : [],
      });
      await queryClient.invalidateQueries({ queryKey: ['users'] });
      if (data.created) {
        // 서버는 새 계정 username 을 소문자 이메일로 저장한다 — 실제 로그인 아이디와 같게 보여 준다.
        setCreated({ username: email.toLowerCase(), password: values.temporaryPassword });
        return;
      }
      // 스펙 §4 문장 그대로(F8). 기존 계정이라 입력한 임시 비밀번호는 쓰이지 않았다.
      toast.success('이미 가입된 계정을 이 워크스페이스에 추가했습니다. 기존 비밀번호로 로그인합니다');
      setOpen(false);
      reset(EMPTY_FORM);
    } catch (error) {
      const response = axios.isAxiosError(error) ? error.response : undefined;
      const data = response?.data as ErrorResponse | undefined;
      // 정지된 멤버: 다시 추가가 아니라 상세에서 재활성화해야 한다 — 이메일 아래에 상세 링크.
      if (data?.code === 'MEMBER_SUSPENDED') {
        const userId = Number(data.errors?.userId);
        setSuspendedUserId(Number.isFinite(userId) ? userId : null);
        setError('email', { message: '이미 이 워크스페이스의 멤버입니다(정지됨)' }, { shouldFocus: true });
        return;
      }
      // 그 밖의 409(이미 멤버, 다른 계정이 쓰는 이메일)는 모두 이메일 입력의 문제다.
      if (response?.status === 409) {
        setError('email', { message: data?.message ?? '이미 이 워크스페이스의 멤버입니다' }, { shouldFocus: true });
        return;
      }
      // Bean Validation 400(코드 없음, 필드별 errors)은 해당 필드 아래로 돌려 준다.
      if (data?.errors && Object.keys(data.errors).length > 0 && data.code == null) {
        Object.entries(data.errors).forEach(([field, message]) => {
          if (field === 'email' || field === 'name' || field === 'temporaryPassword') setError(field, { message });
        });
        return;
      }
      // INVALID_ROLE·권한 부족·서버 오류 등은 폼 상단 오류로.
      setError('root', { message: extractApiError(error, '멤버를 추가하지 못했습니다') });
    }
  };

  const roles = rolesQuery.data ?? [];

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogTrigger asChild>
        <Button>
          <Plus className="h-4 w-4" aria-hidden="true" />
          멤버 추가
        </Button>
      </DialogTrigger>
      <DialogContent
        className="flex max-h-[85vh] flex-col"
        // 결과 화면은 "닫기" 단일 주 버튼만 둔다(우상단 X 와 이름이 겹치지 않게). Esc 는 그대로 허용.
        showCloseButton={!created}
        // 결과 화면은 비밀번호를 다시 볼 수 없으므로 바깥 클릭으로 실수로 닫히지 않게 한다.
        onInteractOutside={(e) => {
          if (created) e.preventDefault();
        }}
        // 열릴 때 첫 입력(이메일)으로 포커스 — 기본 동작(첫 포커스 요소=닫기 X)보다 바로 입력할 수 있게.
        onOpenAutoFocus={(e) => {
          e.preventDefault();
          emailRef.current?.focus();
        }}
      >
        {created ? (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <CheckCircle2 className="h-5 w-5 text-success" aria-hidden="true" />
                계정을 만들었습니다
              </DialogTitle>
              <DialogDescription className="sr-only">새 계정의 아이디와 임시 비밀번호입니다.</DialogDescription>
            </DialogHeader>
            <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
              <InlineBanner variant="warning" icon={<AlertTriangle />} title="이 화면을 닫으면 비밀번호를 다시 볼 수 없습니다">
                <span className="break-keep">직접 전달하세요. 첫 로그인 시 변경해야 합니다</span>
              </InlineBanner>
              <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-2 text-sm">
                <dt className="text-muted-foreground">아이디</dt>
                <dd className="min-w-0">
                  <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-sm break-all select-all">
                    {created.username}
                  </code>
                </dd>
                <dt className="text-muted-foreground">임시 비밀번호</dt>
                <dd className="min-w-0">
                  <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-sm break-all select-all">
                    {created.password}
                  </code>
                </dd>
              </dl>
              <Button
                ref={copyAllRef}
                type="button"
                variant="outline"
                size="sm"
                onClick={() => void copyText(`아이디: ${created.username}\n임시 비밀번호: ${created.password}`)}
              >
                <Copy className="h-4 w-4" aria-hidden="true" />
                아이디·비밀번호 함께 복사
              </Button>
            </div>
            <DialogFooter>
              <Button type="button" onClick={() => handleOpenChange(false)}>
                닫기
              </Button>
            </DialogFooter>
          </>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex min-h-0 flex-1 flex-col gap-4">
            <DialogHeader>
              <DialogTitle>멤버 추가</DialogTitle>
              <DialogDescription className="break-keep">
                이 워크스페이스에 사용자를 추가합니다. 이미 가입된 이메일이면 기존 계정이 추가됩니다.
              </DialogDescription>
            </DialogHeader>
            <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
              {errors.root && (
                <p role="alert" className="text-sm text-destructive">
                  {errors.root.message}
                </p>
              )}
              {/* 정지 멤버 상세 링크는 오류 문구 바로 뒤(같은 줄)에 둔다(와이어프레임 ③). 오류 문구가 이메일 입력의
                  aria-describedby 라 링크도 그 입력의 설명으로 함께 읽힌다. */}
              <FormField
                label="이메일"
                htmlFor="member-email"
                error={errors.email?.message}
                required
                errorAction={
                  suspendedUserId !== null && (
                    <Link
                      to={`/admin/users/${suspendedUserId}`}
                      className="rounded-sm whitespace-nowrap text-primary hover:underline focus-visible:ring-[3px] focus-visible:ring-ring focus-visible:outline-none"
                      onClick={() => handleOpenChange(false)}
                    >
                      상세에서 재활성화 →
                    </Link>
                  )
                }
              >
                <Input
                  id="member-email"
                  type="email"
                  autoComplete="off"
                  maxLength={50}
                  aria-invalid={!!errors.email}
                  aria-describedby={errors.email ? 'member-email-error' : undefined}
                  {...emailField}
                  ref={(el) => {
                    emailField.ref(el);
                    emailRef.current = el;
                  }}
                />
              </FormField>
              <FormField label="이름" htmlFor="member-name" error={errors.name?.message} required>
                <Input
                  id="member-name"
                  maxLength={50}
                  aria-invalid={!!errors.name}
                  aria-describedby={errors.name ? 'member-name-error' : undefined}
                  {...register('name')}
                />
              </FormField>
              <FormField
                label="임시 비밀번호"
                htmlFor="member-password"
                error={errors.temporaryPassword?.message}
                required
              >
                <div className="flex gap-2">
                  <Input
                    id="member-password"
                    className="font-mono"
                    autoComplete="off"
                    spellCheck={false}
                    maxLength={128}
                    aria-invalid={!!errors.temporaryPassword}
                    aria-describedby={
                      errors.temporaryPassword ? 'member-password-help member-password-error' : 'member-password-help'
                    }
                    {...register('temporaryPassword')}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() =>
                      setValue('temporaryPassword', generateTemporaryPassword(), {
                        shouldValidate: true,
                        shouldDirty: true,
                      })
                    }
                  >
                    <RefreshCw className="h-4 w-4" aria-hidden="true" />
                    자동 생성
                  </Button>
                  {/* aria-label 에 "임시 비밀번호" 를 넣지 않는다 — 입력란 라벨과 이름이 겹쳐 보조기기에서 구분되지 않는다. */}
                  <Button
                    type="button"
                    variant="outline"
                    size="icon"
                    aria-label="비밀번호 복사"
                    onClick={() => void copyText(form.getValues('temporaryPassword'))}
                  >
                    <Copy className="h-4 w-4" aria-hidden="true" />
                  </Button>
                </div>
                <p id="member-password-help" className="text-[13px] text-muted-foreground break-keep">
                  8~128자, 대·소문자·숫자 포함. 기존 계정이면 사용되지 않습니다.
                </p>
              </FormField>
              <fieldset className="space-y-2">
                <legend className="text-sm leading-5 font-medium">역할</legend>
                {rolesQuery.isLoading && (
                  <div className="space-y-2" aria-hidden="true">
                    <Skeleton className="h-5 w-40" />
                    <Skeleton className="h-5 w-32" />
                  </div>
                )}
                {rolesQuery.isError && (
                  <p className="text-sm text-destructive">역할 목록을 불러오지 못했습니다. USER 역할로 추가할 수 있습니다.</p>
                )}
                <Controller
                  control={control}
                  name="roleIds"
                  render={({ field }) => (
                    <div className="space-y-2">
                      {roles.map((role) => {
                        // USER 는 서버가 항상 부여하는 기본 역할 — 체크 고정·해제 불가, 전송 목록에는 넣지 않는다.
                        const isUser = role.name === 'USER';
                        const id = `member-role-${role.id}`;
                        return (
                          <div key={role.id} className="flex items-center gap-2">
                            <Checkbox
                              id={id}
                              checked={isUser || field.value.includes(role.id)}
                              disabled={isUser || !canAssignRoles}
                              onCheckedChange={(checked) =>
                                field.onChange(
                                  checked ? [...field.value, role.id] : field.value.filter((v) => v !== role.id),
                                )
                              }
                            />
                            <Label htmlFor={id} className="font-normal">
                              {role.name}
                              {isUser && (
                                <>
                                  {/* 공백 텍스트 노드: flex 배치엔 영향 없이 접근 가능한 이름을 "USER 기본 역할" 로 띄운다. */}{' '}
                                  <span className="text-muted-foreground" aria-hidden="true">
                                    ·
                                  </span>
                                  <span className="text-muted-foreground">기본 역할</span>
                                </>
                              )}
                            </Label>
                          </div>
                        );
                      })}
                    </div>
                  )}
                />
                <p className="text-[13px] text-muted-foreground break-keep">
                  USER 는 워크스페이스 기본 역할로 항상 부여됩니다. 선택한 역할은 여기에 추가됩니다.
                </p>
                {!canAssignRoles && (
                  <p className="text-[13px] text-muted-foreground">역할 지정 권한이 없어 USER 역할로만 추가됩니다</p>
                )}
              </fieldset>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" disabled={isSubmitting} onClick={() => handleOpenChange(false)}>
                취소
              </Button>
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
                추가
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
