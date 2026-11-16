import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import axios from 'axios';
import { Loader2 } from 'lucide-react';
import { type Ref, useId, useRef, useState } from 'react';
import { type FieldErrors, useForm, type UseFormReturn } from 'react-hook-form';
import { toast } from 'sonner';

import { tenantsApi } from '@/api/tenants';
import { CredentialsResult } from '@/components/CredentialsResult';
import { TemporaryPasswordField } from '@/components/TemporaryPasswordField';
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
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { UserSearchPicker } from '@/components/UserSearchPicker';
import { usePlatformUserSearch } from '@/hooks/usePlatformUserSearch';
import { applyServerFieldErrors, isForbidden, isNotFound, serverMessage } from '@/lib/http-errors';
import { generateTemporaryPassword } from '@/lib/temp-password';
import type { CreateAccountFormData } from '@/lib/validations/account';
import { createAccountSchema } from '@/lib/validations/account';
import type { AddTenantMemberRequest, ErrorResponse, PlatformUserResponse } from '@/types/platform';

interface AddTenantMemberDialogProps {
  tenantId: number;
  /** 설명 문구에 넣는다 — 운영자가 엉뚱한 테넌트 화면에서 열지 않았는지 확인하게 한다. */
  tenantName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 추가에 성공하면(새 계정이든 기존 계정이든) 부른다 — 호출자가 멤버 목록·인원수 캐시를 무효화한다. */
  onAdded: () => void;
}

/** 추가 방식 — 이미 있는 계정을 고르거나, 이메일·이름·임시 비밀번호로 새 계정을 만든다. */
type Mode = 'existing' | 'new';

/** 새 계정을 만든 직후 1회만 보여 줄 자격 증명. 닫으면(언마운트) 버린다 — 어디에도 저장·로그하지 않는다. */
interface CreatedCredentials {
  email: string;
  password: string;
}

/** 한 번의 추가 요청. `credentials` 는 새 계정 탭에서만 실린다 — 서버가 실제로 계정을 만들었을 때(created=true) 결과 화면에 보인다. */
interface AddVariables {
  body: AddTenantMemberRequest;
  /** 기존 계정을 붙였을 때 토스트에 넣을 이름. */
  displayName: string;
  credentials?: CreatedCredentials;
}

/**
 * 오류 코드별 운영자 콘솔 안내. 409 멤버 코드는 서버 문구(「워크스페이스」)가 아니라 콘솔 어휘(「테넌트」)로 바꿔 보이고,
 * 404 USER_NOT_FOUND 는 테넌트가 없다고 오해하지 않게 구분한다. 표에 없는 코드는 서버 문구 등 아래 규칙을 따른다.
 */
const ERROR_MESSAGES: Record<string, string> = {
  MEMBER_ALREADY_EXISTS: '이미 이 테넌트의 멤버입니다',
  // 정지 멤버 id 는 errors.userId 로 오지만 운영자 콘솔에는 멤버 상세 화면이 없다 — 상세 링크 대신 멤버 표의 상태를 보라고 안내한다.
  MEMBER_SUSPENDED: '이미 이 테넌트의 멤버입니다(정지됨). 멤버 상태를 확인하세요',
  // 기존 계정 탭에서 고른 계정이 그새 지워졌을 때.
  USER_NOT_FOUND: '고른 계정을 찾을 수 없습니다. 다시 검색해 고르세요.',
};

/** 새 계정 탭의 칸 — 서버 400 필드 오류를 이 칸 아래로 보낸다(기존 계정 요청은 이 필드를 보내지 않아 필드 오류가 오지 않는다). */
const NEW_ACCOUNT_FIELDS = ['email', 'name', 'temporaryPassword'] as const;

/**
 * 추가 실패를 폼 상단 문구로 바꾼다. 400 필드 오류는 `applyFieldErrors` 가 칸 아래에 달고, 칸 밖 문구만 돌려받는다.
 *
 * @returns 폼 상단에 보일 문구. 빈 문자열이면(필드 오류만 있음) 상단 문구를 두지 않는다.
 */
function addErrorMessage(error: unknown, applyFieldErrors: () => string | undefined): string {
  const response = axios.isAxiosError(error) ? error.response : undefined;
  const code = (response?.data as ErrorResponse | undefined)?.code;
  const known = code ? ERROR_MESSAGES[code] : undefined;
  if (known) return known;
  if (response?.status === 409) {
    // TENANT_SUSPENDED·ACCOUNT_INACTIVE 등 — 서버 문구가 사유와 할 일을 가장 정확히 말한다.
    return serverMessage(error, [409]) ?? '멤버를 추가할 수 없는 상태입니다.';
  }
  // Bean Validation 400 — 칸이 있는 오류는 칸 아래로, 나머지 문구는 폼 상단으로 모은다.
  const rest = applyFieldErrors();
  if (rest !== undefined) return rest;
  if (isForbidden(error)) return '이 작업을 수행할 권한이 없습니다.';
  if (isNotFound(error)) return '테넌트를 찾을 수 없습니다.';
  return serverMessage(error) ?? '멤버를 추가하지 못했습니다.';
}

/**
 * 테넌트 상세의 멤버 추가 다이얼로그(WD-750). 웹 멤버 추가(`AddMemberDialog`, WD-2)와 운영자 계정 생성
 * (`CreateAccountDialog`, WD-46)의 구조를 따른다: 입력 → (새 계정이면) 자격 증명 1회 표시, 기존 계정이면 토스트 후 닫기.
 *
 * 탭 두 개:
 * - 「기존 계정」: Owner 선택기와 같은 디바운스 검색(2자 이상)으로 한 명을 고른다. 고른 계정은 `userId` 로만
 *   지정한다 — username 과 email 은 별개라 이메일로 보내면 username ≠ email 인 계정 대신 새 계정이 생긴다.
 *   이메일이 없는 계정도 고를 수 있다.
 * - 「새 계정」: 이메일·이름·임시 비밀번호 입력(`createAccountSchema` 를 그대로 쓴다 — 서버 검증 경계가 같다).
 *
 * USER 역할은 서버가 항상 붙이므로 고르지 않는다. 「관리자(ADMIN) 역할도 부여」 체크만 둔다.
 *
 * 두 탭은 하위 컴포넌트로 나눴다 — 검색어 입력마다 대화상자 전체(새 계정 폼 포함)가 다시 그려지지 않게 검색어 상태를 「기존 계정」 탭
 * 안에 둔다. 고른 계정·ADMIN 체크·제출·오류는 두 탭이 함께 쓰므로 본체에 둔다.
 *
 * 열릴 때마다 **새로 마운트**한다(`open=false` 면 아무것도 그리지 않는다) — 결과 화면의 비밀번호·입력·오류가 다음
 * 열림에 남지 않고, 열 때마다 새 임시 비밀번호로 시작하게 하기 위해서다(`CreateAccountDialog` 와 같은 판단).
 */
export function AddTenantMemberDialog(props: AddTenantMemberDialogProps) {
  if (!props.open) return null;
  return <AddTenantMemberDialogBody {...props} />;
}

function AddTenantMemberDialogBody({ tenantId, tenantName, onOpenChange, onAdded }: AddTenantMemberDialogProps) {
  const [mode, setMode] = useState<Mode>('existing');
  const [admin, setAdmin] = useState(false);
  const [picked, setPicked] = useState<PlatformUserResponse | null>(null);
  const [created, setCreated] = useState<CreatedCredentials | null>(null);
  // 열 때(마운트) 한 번만 만든다 — useForm 의 defaultValues 인자는 렌더마다 평가되므로 거기서 바로 만들면 렌더마다 버려지는 비밀번호를 생성한다.
  const [initialPassword] = useState(generateTemporaryPassword);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const adminId = useId();
  const adminHelpId = useId();

  const form = useForm<CreateAccountFormData>({
    resolver: zodResolver(createAccountSchema),
    mode: 'onBlur',
    reValidateMode: 'onChange',
    defaultValues: { email: '', name: '', temporaryPassword: initialPassword },
  });
  const {
    handleSubmit,
    setError,
    clearErrors,
    formState: { errors },
  } = form;

  /**
   * 두 탭이 함께 쓰는 전송. 새 계정이면 결과 화면으로 넘어가고, 기존 계정이면 토스트를 띄우고 닫는다.
   * 어느 쪽이든 멤버가 늘었으므로 `onAdded` 를 먼저 불러 뒤 화면의 목록을 갱신한다.
   */
  const addMutation = useMutation({
    mutationFn: ({ body }: AddVariables) => tenantsApi.addMember(tenantId, body).then((r) => r.data),
    onSuccess: (data, { displayName, credentials }) => {
      onAdded();
      // created=true 는 새 계정 탭(email·임시 비밀번호를 보낸 요청)에서만 온다 — userId 경로는 계정을 만들지 않는다.
      // 새 계정 탭이라도 이미 있는 계정이면 created=false 라 기존 계정처럼 토스트 후 닫는다.
      if (data.created && credentials) {
        setCreated(credentials);
      } else {
        toast.success(`${displayName}님을 멤버로 추가했습니다`);
        onOpenChange(false);
      }
    },
    onError: (error) => {
      const message = addErrorMessage(error, () => applyServerFieldErrors(error, setError, NEW_ACCOUNT_FIELDS));
      if (message) setError('root', { message });
    },
  });
  const submitting = addMutation.isPending;

  /** 닫기 요청 — 제출 중(응답이 닫힌 뒤 도착)이거나 결과 화면(비밀번호를 다시 볼 수 없음)이면 무시한다. */
  const requestClose = (next: boolean) => {
    if (next) return;
    if (submitting || created) return;
    onOpenChange(false);
  };

  const submit = (variables: AddVariables) => {
    clearErrors('root');
    addMutation.mutate(variables);
  };

  const onSubmitNew = (values: CreateAccountFormData) => {
    const email = values.email.trim();
    const name = values.name.trim();
    submit({
      body: { email, name, temporaryPassword: values.temporaryPassword, admin },
      displayName: name,
      // 서버는 아이디를 소문자·공백 제거 이메일로 만든다 — 결과 화면도 실제 로그인 아이디를 보인다.
      credentials: { email: email.toLowerCase(), password: values.temporaryPassword },
    });
  };

  const onSubmitExisting = () => {
    if (!picked) {
      setError('root', { message: '추가할 계정을 검색해 고르세요.' });
      searchInputRef.current?.focus();
      return;
    }
    // 기존 계정은 id 로만 지정한다 — 서버가 그 계정을 그대로 붙인다(email·이름·비밀번호는 보내지 않는다).
    submit({ body: { userId: picked.id, admin }, displayName: picked.name });
  };

  return (
    <Dialog open onOpenChange={requestClose}>
      <DialogContent
        className="flex max-h-[85vh] flex-col"
        // 결과 화면에는 X 를 두지 않는다 — 완료 버튼 하나로만 닫힌다(비밀번호를 다시 볼 수 없다).
        showCloseButton={!created}
        onInteractOutside={(e) => {
          if (created || submitting) e.preventDefault();
        }}
        onEscapeKeyDown={(e) => {
          if (created || submitting) e.preventDefault();
        }}
      >
        {created ? (
          <CredentialsResult
            title="새 계정을 만들어 멤버로 추가했습니다"
            description="새 계정의 이메일과 임시 비밀번호입니다."
            warningTitle="이 비밀번호는 다시 볼 수 없습니다."
            idLabel="이메일"
            idValue={created.email}
            password={created.password}
            finishLabel="확인"
            onFinish={() => onOpenChange(false)}
          />
        ) : (
          <form
            onSubmit={(e) => {
              // 새 계정 탭만 폼 검증을 거친다 — 기존 계정 탭은 입력 칸이 없어 zod 검증을 돌리면 항상 실패한다.
              if (mode === 'new') {
                void handleSubmit(onSubmitNew)(e);
              } else {
                e.preventDefault();
                onSubmitExisting();
              }
            }}
            noValidate
            className="flex min-h-0 flex-1 flex-col gap-4"
          >
            <DialogHeader>
              <DialogTitle>멤버 추가</DialogTitle>
              <DialogDescription className="break-keep">
                {`"${tenantName}" 테넌트에 기존 계정을 넣거나 새 계정을 만들어 넣습니다.`}
              </DialogDescription>
            </DialogHeader>
            <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
              {errors.root && (
                <p role="alert" className="text-sm text-destructive">
                  {errors.root.message}
                </p>
              )}
              <Tabs
                value={mode}
                onValueChange={(v) => {
                  // 탭을 바꾸면 앞 탭의 오류 안내는 더 이상 맞지 않는다.
                  setMode(v as Mode);
                  clearErrors();
                }}
              >
                <TabsList variant="default">
                  <TabsTrigger value="existing" disabled={submitting}>
                    기존 계정
                  </TabsTrigger>
                  <TabsTrigger value="new" disabled={submitting}>
                    새 계정
                  </TabsTrigger>
                </TabsList>

                <TabsContent value="existing" className="space-y-2 pt-2">
                  <ExistingAccountTab
                    picked={picked}
                    onPick={(user) => {
                      setPicked(user);
                      clearErrors('root');
                    }}
                    disabled={submitting}
                    inputRef={searchInputRef}
                  />
                </TabsContent>

                <TabsContent value="new" className="space-y-4 pt-2">
                  <NewAccountTab form={form} errors={errors} />
                </TabsContent>
              </Tabs>

              {/* 두 탭 공통 — USER 는 서버가 항상 붙이므로 고를 것이 ADMIN 하나뿐이다. */}
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <input
                    id={adminId}
                    type="checkbox"
                    className="h-4 w-4 accent-primary"
                    aria-describedby={adminHelpId}
                    checked={admin}
                    disabled={submitting}
                    onChange={(e) => setAdmin(e.target.checked)}
                  />
                  <Label htmlFor={adminId}>관리자(ADMIN) 역할도 부여</Label>
                </div>
                <p id={adminHelpId} className="text-[13px] text-muted-foreground">
                  USER 역할은 항상 부여됩니다.
                </p>
              </div>
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" disabled={submitting} onClick={() => requestClose(false)}>
                취소
              </Button>
              <Button type="submit" disabled={submitting}>
                {submitting && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
                추가
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}

interface ExistingAccountTabProps {
  picked: PlatformUserResponse | null;
  /** 목록에서 고르면 그 계정, 지우면 null. */
  onPick: (user: PlatformUserResponse | null) => void;
  disabled: boolean;
  inputRef: Ref<HTMLInputElement>;
}

/**
 * 「기존 계정」 탭 — Owner 선택기와 같은 디바운스 검색(2자 이상)으로 한 명을 고른다. 검색어 상태를 여기 둬서 입력마다 이 탭만 다시 그린다.
 * 서버가 id 로 찾으므로 이메일이 없는 계정도 고를 수 있다.
 */
function ExistingAccountTab({ picked, onPick, disabled, inputRef }: ExistingAccountTabProps) {
  const [keyword, setKeyword] = useState('');
  // OwnerPicker 와 같은 쿼리 키를 써서 캐시를 함께 쓴다(같은 검색어면 같은 결과).
  const search = usePlatformUserSearch(keyword, picked === null);
  return (
    <UserSearchPicker
      value={picked}
      onChange={onPick}
      keyword={keyword}
      onKeywordChange={setKeyword}
      search={search}
      label="추가할 계정"
      clearLabel="계정 선택 해제"
      listLabel="계정 검색 결과"
      emptyText="검색 결과가 없습니다. 「새 계정」 탭에서 만들 수 있습니다."
      capNoticeHint="찾는 계정이 없으면 검색어를 더 좁혀보세요."
      disabled={disabled}
      inputRef={inputRef}
    />
  );
}

interface NewAccountTabProps {
  form: UseFormReturn<CreateAccountFormData>;
  errors: FieldErrors<CreateAccountFormData>;
}

/** 「새 계정」 탭 — 이메일·이름·임시 비밀번호(`createAccountSchema` 를 그대로 쓴다 — 서버 검증 경계가 같다). */
function NewAccountTab({ form, errors }: NewAccountTabProps) {
  const { register } = form;
  return (
    <>
      <FormField label="이메일(로그인 아이디)" htmlFor="member-email" error={errors.email?.message} required>
        <Input
          id="member-email"
          type="email"
          autoComplete="off"
          maxLength={50}
          aria-invalid={!!errors.email}
          aria-describedby={errors.email ? 'member-email-error' : undefined}
          {...register('email')}
        />
      </FormField>
      <FormField label="이름" htmlFor="member-name" error={errors.name?.message} required>
        <Input
          id="member-name"
          autoComplete="off"
          maxLength={50}
          aria-invalid={!!errors.name}
          aria-describedby={errors.name ? 'member-name-error' : undefined}
          {...register('name')}
        />
      </FormField>
      <TemporaryPasswordField idPrefix="member" form={form} error={errors.temporaryPassword?.message} />
    </>
  );
}
