import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { AlertTriangle, CheckCircle2, Copy, Info, Loader2, RefreshCw } from 'lucide-react';
import { type RefObject, useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { toast } from 'sonner';

import { accountsApi } from '@/api/accounts';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { FormField } from '@/components/ui/form-field';
import { InlineBanner } from '@/components/ui/inline-banner';
import { Input } from '@/components/ui/input';
import { PasswordInput } from '@/components/ui/password-input';
import { isForbidden, serverMessage } from '@/lib/http-errors';
import { generateTemporaryPassword } from '@/lib/temp-password';
import type { CreateAccountFormData } from '@/lib/validations/account';
import { createAccountSchema } from '@/lib/validations/account';
import type { ErrorResponse, PlatformAccountResponse } from '@/types/platform';

interface CreateAccountDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 이메일 입력 초기값(예: Owner 검색어가 이메일 형태일 때). 없으면 빈 칸. */
  initialEmail?: string;
  /** 결과 화면의 완료 버튼 문구 — 계정 화면 "확인", 테넌트 생성 "Owner로 선택하고 닫기". */
  finishLabel: string;
  /** 결과 화면에서 완료 버튼을 누를 때 만든 계정을 넘긴다. 다이얼로그는 그 뒤 닫힌다. */
  onCreated?: (account: PlatformAccountResponse) => void;
  /** 다이얼로그를 연 요소가 닫힘과 함께 사라질 때 포커스를 대신 받을 요소(예: Owner 선택 상태). */
  restoreFocusRef?: RefObject<HTMLElement | null>;
}

/** 클립보드 복사 + 토스트(웹 멤버 추가와 같은 규칙: 복사 확인은 toast.info). */
async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    toast.info('클립보드에 복사되었습니다');
  } catch {
    toast.error('복사하지 못했습니다');
  }
}

/**
 * 운영자 콘솔 계정 생성 다이얼로그(WD-46). 웹 멤버 추가(`AddMemberDialog`, WD-2)의 두 단계 구조를 따른다:
 * 입력 폼 → 자격 증명 1회 표시.
 *
 * 열릴 때마다 **새로 마운트**한다(`open=false` 면 아무것도 그리지 않는다). 이유: 결과 화면의 임시 비밀번호·입력값·오류가
 * 다음 열림에 남지 않아야 하고(비밀번호 재노출 방지), 열릴 때마다 새 임시 비밀번호와 prefill 이메일로 시작해야 한다.
 * 상태를 effect 로 되돌리는 것보다 마운트 경계가 확실하다.
 */
export function CreateAccountDialog(props: CreateAccountDialogProps) {
  if (!props.open) return null;
  return <CreateAccountDialogBody {...props} />;
}

function CreateAccountDialogBody({
  onOpenChange,
  initialEmail,
  finishLabel,
  onCreated,
  restoreFocusRef,
}: CreateAccountDialogProps) {
  const queryClient = useQueryClient();
  // 결과 화면 상태: 만든 계정 + 방금 쓴 임시 비밀번호. 닫으면(언마운트) 버린다 — 어디에도 저장·로그하지 않는다.
  const [created, setCreated] = useState<{ account: PlatformAccountResponse; password: string } | null>(null);
  const emailRef = useRef<HTMLInputElement | null>(null);
  const nameRef = useRef<HTMLInputElement | null>(null);
  const copyAllRef = useRef<HTMLButtonElement | null>(null);

  const {
    register,
    handleSubmit,
    setValue,
    setError,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm<CreateAccountFormData>({
    resolver: zodResolver(createAccountSchema),
    mode: 'onBlur',
    reValidateMode: 'onChange',
    // 마운트 시 한 번만 계산된다 — 열 때마다 새 비밀번호.
    defaultValues: { email: initialEmail ?? '', name: '', temporaryPassword: generateTemporaryPassword() },
  });
  const emailField = register('email');
  const nameField = register('name');

  // 결과 화면으로 바뀌면 제출 버튼이 사라져 포커스가 컨테이너로 떨어진다 — 다음 행동(복사)으로 옮긴다.
  useEffect(() => {
    if (created) copyAllRef.current?.focus();
  }, [created]);

  /** 닫기 요청 — 제출 중(응답이 닫힌 뒤 도착)이거나 결과 화면(비밀번호를 다시 볼 수 없음)이면 무시한다. */
  const requestClose = (next: boolean) => {
    if (next) return;
    if (isSubmitting || created) return;
    onOpenChange(false);
  };

  /** 결과 화면 완료 — 호출자에게 계정을 넘기고 닫는다. 이 버튼만이 결과 화면을 닫는다. */
  const finish = () => {
    if (created) onCreated?.(created.account);
    onOpenChange(false);
  };

  const onSubmit = async (values: CreateAccountFormData) => {
    try {
      const { data } = await accountsApi.create({
        email: values.email.trim(),
        name: values.name.trim(),
        temporaryPassword: values.temporaryPassword,
      });
      // 계정 목록과 Owner 검색 캐시를 둘 다 무효화한다 — 남아 있으면 방금 만든 계정이 검색에 안 보여 중복 생성을 시도하게 된다.
      void queryClient.invalidateQueries({ queryKey: ['platform-accounts'] });
      void queryClient.invalidateQueries({ queryKey: ['platform-user-search'] });
      setCreated({ account: data, password: values.temporaryPassword });
    } catch (error) {
      const response = axios.isAxiosError(error) ? error.response : undefined;
      const body = response?.data as ErrorResponse | undefined;
      // 같은 아이디·이메일의 계정이 이미 있다 — 이메일 입력의 문제다. 기존 계정은 서버가 건드리지 않았다.
      if (response?.status === 409) {
        setError(
          'email',
          { message: body?.message ?? '이미 같은 아이디 또는 이메일의 계정이 있습니다' },
          { shouldFocus: true },
        );
        return;
      }
      // Bean Validation 400(코드 없음, 필드별 errors)은 해당 필드 아래로 돌려 준다.
      if (body?.errors && Object.keys(body.errors).length > 0 && body.code == null) {
        Object.entries(body.errors).forEach(([field, message]) => {
          if (field === 'email' || field === 'name' || field === 'temporaryPassword') setError(field, { message });
        });
        return;
      }
      if (isForbidden(error)) {
        setError('root', { message: '이 작업을 수행할 권한이 없습니다.' });
        return;
      }
      setError('root', { message: serverMessage(error) ?? '계정을 만들지 못했습니다.' });
    }
  };

  return (
    <Dialog open onOpenChange={requestClose}>
      <DialogContent
        className="flex max-h-[85vh] flex-col"
        restoreFocusRef={restoreFocusRef}
        // 결과 화면에는 X 를 두지 않는다 — 완료 버튼 하나로만 닫힌다(비밀번호를 다시 볼 수 없다).
        showCloseButton={!created}
        onInteractOutside={(e) => {
          if (created || isSubmitting) e.preventDefault();
        }}
        onEscapeKeyDown={(e) => {
          if (created || isSubmitting) e.preventDefault();
        }}
        // 열릴 때 첫 빈 입력으로 포커스 — 이메일이 미리 채워졌으면 이름부터 입력한다.
        onOpenAutoFocus={(e) => {
          e.preventDefault();
          (initialEmail ? nameRef : emailRef).current?.focus();
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
              <InlineBanner variant="warning" icon={<AlertTriangle />} title="이 화면을 닫으면 비밀번호를 다시 볼 수 없습니다.">
                <span className="break-keep">지금 복사해 본인에게 직접 전달하세요. 첫 로그인 시 비밀번호를 바꿔야 합니다.</span>
              </InlineBanner>
              <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-2 text-sm">
                <dt className="text-muted-foreground">아이디</dt>
                <dd className="min-w-0">
                  <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-sm break-all select-all">
                    {created.account.username}
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
                onClick={() =>
                  void copyText(`아이디: ${created.account.username}\n임시 비밀번호: ${created.password}`)
                }
              >
                <Copy className="h-4 w-4" aria-hidden="true" />
                아이디·비밀번호 함께 복사
              </Button>
            </div>
            <DialogFooter>
              <Button type="button" onClick={finish}>
                {finishLabel}
              </Button>
            </DialogFooter>
          </>
        ) : (
          <form
            // 다이얼로그는 포털로 그려지지만 React 이벤트는 React 트리를 따라 올라간다 — 테넌트 생성 폼 안의 OwnerPicker 에서
            // 열리면 여기 submit 이 바깥 폼의 handleSubmit 까지 깨운다(테넌트 검증 오류 표시·생성 요청). 여기서 끊는다.
            onSubmit={(e) => {
              e.stopPropagation();
              void handleSubmit(onSubmit)(e);
            }}
            noValidate
            className="flex min-h-0 flex-1 flex-col gap-4"
          >
            <DialogHeader>
              <DialogTitle>계정 생성</DialogTitle>
              <DialogDescription className="break-keep">
                임시 비밀번호로 새 계정을 만듭니다. 첫 로그인 시 비밀번호를 바꿔야 합니다.
              </DialogDescription>
            </DialogHeader>
            <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
              <InlineBanner variant="info" icon={<Info />}>
                <span className="break-keep">
                  이 계정은 아직 어느 워크스페이스에도 속하지 않습니다. 테넌트 Owner로 지정하거나 워크스페이스 관리자가
                  멤버로 추가해야 사용할 수 있습니다.
                </span>
              </InlineBanner>
              {errors.root && (
                <p role="alert" className="text-sm text-destructive">
                  {errors.root.message}
                </p>
              )}
              <FormField label="이메일(로그인 아이디)" htmlFor="account-email" error={errors.email?.message} required>
                <Input
                  id="account-email"
                  type="email"
                  autoComplete="off"
                  maxLength={50}
                  aria-invalid={!!errors.email}
                  aria-describedby={errors.email ? 'account-email-error' : undefined}
                  {...emailField}
                  ref={(el) => {
                    emailField.ref(el);
                    emailRef.current = el;
                  }}
                />
              </FormField>
              <FormField label="이름" htmlFor="account-name" error={errors.name?.message} required>
                <Input
                  id="account-name"
                  autoComplete="off"
                  maxLength={50}
                  aria-invalid={!!errors.name}
                  aria-describedby={errors.name ? 'account-name-error' : undefined}
                  {...nameField}
                  ref={(el) => {
                    nameField.ref(el);
                    nameRef.current = el;
                  }}
                />
              </FormField>
              <FormField
                label="임시 비밀번호"
                htmlFor="account-password"
                error={errors.temporaryPassword?.message}
                required
              >
                <div className="flex gap-2">
                  <div className="min-w-0 flex-1">
                    <PasswordInput
                      id="account-password"
                      className="font-mono"
                      autoComplete="new-password"
                      spellCheck={false}
                      maxLength={128}
                      aria-invalid={!!errors.temporaryPassword}
                      aria-describedby={
                        errors.temporaryPassword ? 'account-password-help account-password-error' : 'account-password-help'
                      }
                      {...register('temporaryPassword')}
                    />
                  </div>
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
                    재생성
                  </Button>
                  {/* aria-label 에 "임시 비밀번호" 를 넣지 않는다 — 입력란 라벨과 이름이 겹쳐 보조기기에서 구분되지 않는다. */}
                  <Button
                    type="button"
                    variant="outline"
                    size="icon"
                    aria-label="비밀번호 복사"
                    onClick={() => void copyText(getValues('temporaryPassword'))}
                  >
                    <Copy className="h-4 w-4" aria-hidden="true" />
                  </Button>
                </div>
                <p id="account-password-help" className="text-[13px] text-muted-foreground break-keep">
                  8~128자, 대·소문자·숫자 포함. 열 때마다 자동으로 만들어집니다.
                </p>
              </FormField>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" disabled={isSubmitting} onClick={() => requestClose(false)}>
                취소
              </Button>
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
                계정 생성
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
