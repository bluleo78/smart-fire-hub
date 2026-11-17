import { zodResolver } from '@hookform/resolvers/zod';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { AlertTriangle, CheckCircle2, Copy, Info, Loader2, Plus, RefreshCw } from 'lucide-react';
import type { FocusEvent } from 'react';
import { useEffect, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';

import { ROLE_LIST_KEY, rolesApi } from '../../../api/roles';
import { usersApi } from '../../../api/users';
import { PendingSubmitButton } from '../../../components/PendingSubmitButton';
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
import { useAuth } from '../../../hooks/useAuth';
import { useSubmitGuard } from '../../../hooks/useSubmitGuard';
import { applyFieldErrors, extractApiError } from '../../../lib/api-error';
import { generateTemporaryPassword, PASSWORD_HELP_TEXT, PASSWORD_MAX_BYTES } from '../../../lib/temp-password';
import type { AddMemberFormData } from '../../../lib/validations/user';
import { addExistingMemberSchema, addMemberSchema } from '../../../lib/validations/user';
import type { ErrorResponse } from '../../../types/auth';
import type { AddMemberRequest, MemberLookupResponse } from '../../../types/user';

/** 새 계정 생성 직후 1회만 보여 줄 자격 증명. 닫으면 버린다(어디에도 저장·로그하지 않는다). */
interface CreatedCredentials {
  username: string;
  password: string;
}

/** 시스템 ADMIN 역할 잠금 안내 — 서버 RoleGrantGuard 의 같은 거부 문구. */
const ADMIN_ROLE_LOCK_REASON = '시스템 ADMIN 역할은 ADMIN 만 부여하거나 회수할 수 있습니다';

/** 폼 초기값 — 닫을 때도 이 값으로 되돌려 임시 비밀번호가 폼 상태에 남지 않게 한다. */
const EMPTY_FORM: AddMemberFormData = { email: '', name: '', temporaryPassword: '', roleIds: [] };

/**
 * 이메일 확인 결과(WD-751)와 그 결과가 어느 이메일에 대한 것인지. 이메일은 서버와 같은 방식(trim·소문자)으로 정규화해 둔다 —
 * 늦게 온 응답이 지금 입력과 다른 이메일의 판정이면 버리기 위해서다.
 */
interface MemberLookup {
  email: string;
  result: MemberLookupResponse;
}

/**
 * 존재 확인 한 번의 결과(WD-751 리뷰). 「실패」와 「낡음」을 가른다 — 실패면 판정 없이 지금 화면대로 제출해도 되지만, 낡음(응답을 기다리는
 * 사이 이메일이 바뀌었거나 대화상자를 닫았다)이면 확인하지 않은 이메일을 제출하게 되므로 멈춰야 한다.
 */
type LookupOutcome =
  | { kind: 'result'; result: MemberLookupResponse }
  | { kind: 'failed' }
  | { kind: 'stale' };

/** 진행 중인 존재 확인 — 같은 이메일이면 blur 와 제출이 이 요청 하나를 나눠 기다린다. */
interface InFlightLookup {
  email: string;
  promise: Promise<LookupOutcome>;
  /** 이 요청을 가리키는 표식 — 끝날 때 자기 자리만 지우기 위해 쓴다 */
  token: object;
}

/** 서버 UserService.normalizeMemberEmail 과 같은 정규화(trim + 소문자) — 판정과 실제 추가가 같은 계정을 가리키게. */
function normalizeEmail(email: string) {
  return email.trim().toLowerCase();
}

/** 이메일 형식이 맞을 때만 존재 확인을 부른다 — 형식 오류는 칸 검증이 먼저 알린다. */
function isLookupableEmail(email: string) {
  return addMemberSchema.shape.email.safeParse(email).success;
}

// 확인 결과에 따라 검증 규칙을 바꾼다(WD-751) — 기존 계정이면 숨긴 이름·임시 비밀번호 칸을 검사하지 않는다.
const resolveNewAccount = zodResolver(addMemberSchema);
const resolveExistingAccount = zodResolver(addExistingMemberSchema);

/**
 * 제출 뒤 409 코드별 처리 표(WD-771·WD-779). submittedMessage 는 응답을 기다리는 사이 이메일을 고쳐 판정을 두지 못했을 때 버튼 위에 보일
 * 「제출한 이메일(…)은 …」 문구다 — 모든 409 의 이 문구를 여기 한곳에서 만든다. status 는 바꿀 판정으로, 있는 코드만 공통 분기(판정 교체·배너)를 탄다.
 * MEMBER_SUSPENDED 는 상세 링크·칸 오류가, ACCOUNT_ALREADY_EXISTS 는 다시 확인이 따로 있어 status 를 두지 않는다. 새 코드는 한 줄만 더한다.
 */
const CONFLICTS: Record<string, { status?: 'INACTIVE' | 'MEMBER'; submittedMessage: (email: string) => string }> = {
  ACCOUNT_INACTIVE: {
    status: 'INACTIVE',
    submittedMessage: (email) => `제출한 이메일(${email})은 비활성화된 계정이라 추가하지 못했습니다`,
  },
  MEMBER_ALREADY_EXISTS: {
    status: 'MEMBER',
    submittedMessage: (email) => `제출한 이메일(${email})은 이미 이 워크스페이스의 멤버입니다`,
  },
  MEMBER_SUSPENDED: {
    submittedMessage: (email) => `제출한 이메일(${email})은 정지된 멤버라 추가하지 못했습니다`,
  },
  // 계정 생성 경합 — 이메일을 고쳤으면 다시 확인하지 않으므로 「다시 확인한 뒤」로 안내한다.
  ACCOUNT_ALREADY_EXISTS: {
    submittedMessage: (email) => `제출한 이메일(${email})은 계정이 방금 만들어져 추가하지 못했습니다 — 다시 확인한 뒤 추가하세요`,
  },
};

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
 *
 * <p>이메일 확인(WD-751): 이메일 칸을 벗어나면(형식이 맞을 때) 서버에 존재 여부를 묻는다. 새 계정이면 지금처럼 이름·임시 비밀번호를 받고,
 * 기존 계정이면 그 칸을 숨긴 채 「기존 계정 추가」로 안내하며(서버가 쓰지 않는 값을 묻지 않는다), 이미 멤버·정지 멤버·비활성 계정(WD-771)이면
 * 안내만 하고 제출을 잠근다. 제출 뒤 409 를 받으면 판정을 서버 답의 코드(이미 멤버·정지 멤버·비활성)대로 바꾼다(WD-771).
 * 확인이 실패하면 판정 없이 모든 칸을 받는다 — 서버의 409·400 처리는 판정과 제출 사이에 상태가 바뀌는 경우를 위해 그대로 둔다.
 * USER 는 워크스페이스 기본 역할로 서버가 항상 붙이므로 체크·비활성(해제 불가)으로 보여 주고 전송 목록에는
 * 넣지 않는다. 선택 역할은 USER 에 추가된다. 문구는 스펙 §4 인용문 기준(pre-flight F8).
 *
 * @param canAssignRoles role:assign 보유 여부 — 없으면 다른 역할을 고를 수 없고 roleIds 는 빈 배열
 * @param canReadRoles role:read 보유 여부 — 없으면 역할 목록(GET /roles)을 읽지 않는다(WD-121). 읽으면 확실히 403 이라
 *   권한 상태가 「역할 목록을 불러오지 못했습니다」라는 오류로 그려진다.
 */
export function AddMemberDialog({ canAssignRoles, canReadRoles }: { canAssignRoles: boolean; canReadRoles: boolean }) {
  const queryClient = useQueryClient();
  // 시스템 ADMIN 역할은 ADMIN 만 부여할 수 있다(WD-116) — 위임받은 멤버 관리자에게는 그 체크박스를 잠근다.
  const { isAdmin: isCallerAdmin, user: caller } = useAuth();
  const [open, setOpen] = useState(false);
  const [created, setCreated] = useState<CreatedCredentials | null>(null);
  const [suspendedUserId, setSuspendedUserId] = useState<number | null>(null);
  // 이메일 확인 결과(WD-751). 이메일을 고치면 지운다 — 남아 있으면 다른 이메일의 판정으로 칸을 숨기게 된다.
  const [lookup, setLookup] = useState<MemberLookup | null>(null);
  // 판정 없이 제출해 먼저 확인하는 중 — 그동안 제출 버튼을 진행 중으로 보인다.
  const [checkingOnSubmit, setCheckingOnSubmit] = useState(false);
  const verdict = lookup?.result.status ?? null;
  const isExistingAccount = verdict === 'EXISTING';
  // 비활성 계정(비멤버)은 추가할 수 없다(WD-771) — 서버가 409 ACCOUNT_INACTIVE 로 거부하고, 워크스페이스 관리자는 계정을 활성화할 수 없다.
  const isInactiveAccount = verdict === 'INACTIVE';
  // 제출을 잠그는 판정 — 이미 멤버(활성·정지)이거나 비활성 계정이면 다시 추가할 수 없다.
  const blocksSubmit = verdict === 'MEMBER' || verdict === 'SUSPENDED' || isInactiveAccount;
  // 기존 계정이면(멤버 여부·활성 여부와 무관) 이름·임시 비밀번호는 쓰이지 않으므로 숨긴다.
  const hidesAccountFields = isExistingAccount || blocksSubmit;
  const emailRef = useRef<HTMLInputElement | null>(null);
  // 진행 중 존재 확인(정규화 이메일 기준) — 이메일을 쓰고 바로 「추가」를 누르면 blur 확인과 제출 확인이 겹치는데, 두 번 묻지 않고 하나를 나눠 기다린다.
  const inFlightLookupRef = useRef<InFlightLookup | null>(null);
  // 대화상자를 열고 닫을 때마다 올린다 — 닫기 전에 보낸 확인의 응답이 다시 연 대화상자(같은 이메일이어도)에 반영되거나 제출로 이어지지 않게.
  const sessionRef = useRef(0);
  // 제출 전 확인을 기다리는 제출을 닫기와 함께 끝내는 함수(WD-771). 확인만 도는 동안에는 닫을 수 있는데, 붙잡힌 확인 응답을 계속 기다리면
  // 제출 잠금(useSubmitGuard)이 풀리지 않아 다시 연 대화상자의 제출이 무시된다 — 닫을 때 그 기다림을 「낡음」으로 바로 끝낸다.
  const cancelCheckRef = useRef<(() => void) | null>(null);
  // 실제 제출(form.handleSubmit)이 도는 동안 선다(WD-779). 렌더 시점 isSubmitting 은 제출을 시작한 직후 다시 그려지기 전까지 false 라,
  // 그 틈의 Esc·닫기가 제출 응답을 닫힌 대화상자로 흘려보낼 수 있다 — 닫기 잠금이 이 ref 도 함께 본다.
  const submittingRef = useRef(false);
  // 기존 계정 → 새 계정으로 판정이 바뀐 뒤 다시 보일 칸에 붙일 서버 칸 오류(WD-751). 칸이 숨겨진 동안 setError 하면 칸이 다시 마운트·등록될 때
  // 오류가 사라지므로, 칸이 그려진 뒤(아래 effect) 붙인다.
  const pendingFieldErrorsRef = useRef<Partial<Record<'name' | 'temporaryPassword', string>> | null>(null);
  const copyAllRef = useRef<HTMLButtonElement | null>(null);

  // 다이얼로그를 열 때만 역할 목록을 읽는다(목록 페이지 진입만으로 불필요한 호출을 만들지 않음).
  const rolesQuery = useQuery({
    queryKey: ROLE_LIST_KEY,
    queryFn: () => rolesApi.getRoles().then((r) => r.data),
    enabled: open && canReadRoles,
  });

  // 위임받은 멤버 관리자가 부여할 수 없는 역할(WD-291) — 서버가 실제 부여와 같은 판정(RoleGrantGuard)으로 알려 준다. 화면이 등급·권한 기준을
  // 따로 흉내 내면 서버와 어긋나므로 목록만 받아 잠근다. ADMIN 은 막히는 역할이 없어 묻지 않는다. 읽기에 실패하면 잠그지 않고 그 사실만 알린다
  // (서버가 추가 때 막는다).
  const grantBlockedQuery = useQuery({
    // 호출자마다 다른 판정이라 사용자 id 를 키에 넣는다 — 새로고침 없이 다른 사용자로 로그인해도 앞 사람의 잠금을 보이지 않게.
    queryKey: ['roles', 'grant-blocked', caller?.id],
    queryFn: () => rolesApi.getGrantBlockedRoles().then((r) => r.data),
    enabled: open && canAssignRoles && !isCallerAdmin,
  });
  const grantBlockedData = grantBlockedQuery.data;
  const blockedReasonById = new Map((grantBlockedData ?? []).map((b) => [b.roleId, b.message]));

  // 입력란 blur 검증은 아래 validateFilledOnBlur 로 직접 한다(WD-142) — RHF 의 onBlur 모드는 빈 칸까지 검사한다.
  // 검증 규칙은 렌더마다 갱신되는 context 로 고른다(WD-751) — 기존 계정이면 숨긴 칸을 검사하지 않는다.
  const form = useForm<AddMemberFormData, { hidesAccountFields: boolean }>({
    resolver: (values, context, options) =>
      (context?.hidesAccountFields ? resolveExistingAccount : resolveNewAccount)(values, context, options),
    context: { hidesAccountFields },
    mode: 'onSubmit',
    reValidateMode: 'onChange',
    defaultValues: EMPTY_FORM,
  });
  const {
    register,
    control,
    setValue,
    setError,
    setFocus,
    trigger,
    reset,
    formState: { errors, isSubmitting },
  } = form;
  /**
   * 값을 넣은 칸만 벗어날 때 검사한다(디자인 시스템 09 §E 의 blur 검증 유지). 빈 칸은 제출 때 「필수」를 알린다(WD-142).
   * 열자마자 이메일에 포커스가 있어, 빈 칸까지 blur 검사하면 다른 곳을 누르는 순간 「이메일은 필수입니다」 줄이 생기며
   * 내용이 밀리고, 누르던 역할 체크박스가 클릭을 놓쳤다. 이미 오류가 떠 있는 칸은 비워도 다시 검사해 낡은 문구를 남기지 않는다.
   */
  const validateFilledOnBlur =
    (name: 'email' | 'name' | 'temporaryPassword') => (e: FocusEvent<HTMLInputElement>) => {
      if (e.target.value.trim() !== '' || form.getFieldState(name).error) void trigger(name);
    };
  /**
   * 이메일 존재 확인(WD-751). 결과를 돌려주고 화면 상태에도 반영한다.
   * - 실패(네트워크·권한 등)면 `failed` — 판정 없이 모든 칸을 받는다.
   * - 응답이 왔을 때 입력이 이미 다른 이메일로 바뀌었거나 대화상자를 닫았다 열었으면 `stale` 로 버린다 — blur 가 여러 번 일어나 늦게 온
   *   앞선 응답이 지금 이메일의 판정을 덮지 않게, 그리고 제출이 확인하지 않은 이메일로 이어지지 않게.
   * - 같은 이메일의 확인이 이미 진행 중이면 새로 묻지 않고 그 요청을 함께 기다린다(blur 직후 제출).
   */
  const runLookup = (rawEmail: string): Promise<LookupOutcome> => {
    const requested = normalizeEmail(rawEmail);
    const pendingLookup = inFlightLookupRef.current;
    if (pendingLookup && pendingLookup.email === requested) return pendingLookup.promise;
    const session = sessionRef.current;
    const token = {};
    const promise = (async (): Promise<LookupOutcome> => {
      try {
        const { data } = await usersApi.lookupMember(rawEmail.trim());
        if (sessionRef.current !== session || normalizeEmail(form.getValues('email')) !== requested) {
          return { kind: 'stale' };
        }
        setLookup({ email: requested, result: data });
        return { kind: 'result', result: data };
      } catch {
        return sessionRef.current === session ? { kind: 'failed' } : { kind: 'stale' };
      } finally {
        // 끝난 요청만 지운다 — 그사이 다른 이메일의 확인이 자리를 차지했으면 그대로 둔다.
        if (inFlightLookupRef.current?.token === token) inFlightLookupRef.current = null;
      }
    })();
    inFlightLookupRef.current = { email: requested, promise, token };
    return promise;
  };

  // 이메일을 고치면 정지 멤버 상세 링크와 확인 결과는 더 이상 그 입력에 대한 것이 아니므로 지우고 처음 상태로 돌아간다.
  const emailField = register('email', {
    onChange: () => {
      setSuspendedUserId(null);
      setLookup(null);
    },
    onBlur: (e: FocusEvent<HTMLInputElement>) => {
      validateFilledOnBlur('email')(e);
      // 형식이 맞고 아직 그 이메일의 판정이 없을 때만 묻는다 — 같은 이메일로 칸을 오가며 매번 부르지 않게.
      const value = e.target.value;
      if (isLookupableEmail(value) && lookup?.email !== normalizeEmail(value)) void runLookup(value);
    },
  });

  // 부여 불가 목록이 체크한 뒤에 도착하면(느린 응답) 그 역할을 선택에서 실제로 뺀다 — 화면에만 해제로 보이고 값에 남으면, 다음 재조회에서
  // 잠금이 풀릴 때 사용자가 고르지 않은 채 다시 체크되어 전송된다.
  useEffect(() => {
    if (!grantBlockedData?.length) return;
    const blocked = new Set(grantBlockedData.map((b) => b.roleId));
    const current = form.getValues('roleIds');
    if (current.some((id) => blocked.has(id))) {
      setValue('roleIds', current.filter((id) => !blocked.has(id)), { shouldDirty: true });
    }
  }, [grantBlockedData, form, setValue]);

  // 숨겼던 이름·임시 비밀번호 칸이 다시 그려진 뒤 미뤄 둔 서버 칸 오류를 붙인다(WD-751). 지금 비어 있는 칸에만 붙인다 — 임시 비밀번호는 열 때
  // 자동 생성된 값이 남아 있어, 값이 보이는 칸 아래에 「필수」가 뜨면 오히려 헷갈린다. 첫 오류 칸으로 포커스해 무엇을 넣을지 바로 보인다.
  useEffect(() => {
    const pendingErrors = pendingFieldErrorsRef.current;
    if (hidesAccountFields || !pendingErrors) return;
    pendingFieldErrorsRef.current = null;
    const blankFields = (['name', 'temporaryPassword'] as const).filter(
      (field) => pendingErrors[field] && form.getValues(field).trim() === '',
    );
    blankFields.forEach((field, index) =>
      setError(field, { message: pendingErrors[field] }, { shouldFocus: index === 0 }),
    );
  }, [hidesAccountFields, setError, form]);

  // 결과 화면으로 바뀌면 제출 버튼이 사라져 포커스가 다이얼로그 컨테이너로 떨어진다 — 다음 행동(복사)으로 옮긴다.
  useEffect(() => {
    if (created) copyAllRef.current?.focus();
  }, [created]);

  /** 열 때마다 새 임시 비밀번호로 초기화, 닫을 때 결과·오류·입력을 모두 버린다(비밀번호 재노출 방지). */
  const handleOpenChange = (next: boolean) => {
    // 실제 제출 중 닫으면 응답이 닫힌 뒤 도착해 결과 화면 상태가 남는다 — 응답을 기다린다.
    // 제출 전 존재 확인(checkingOnSubmit)만 도는 동안에는 닫을 수 있다(WD-771) — 늦은 확인 응답은 세션이 바뀌어 버려진다.
    // 제출을 막 시작해 아직 다시 그려지지 않은 틈(isSubmitting 이 낡은 false)도 막는다(WD-779, submittingRef).
    if (!next && (isSubmitting || submittingRef.current)) return;
    // 진행 중 확인은 이 세션의 것이 아니게 된다 — 응답이 와도 stale 로 버려진다(runLookup).
    sessionRef.current += 1;
    inFlightLookupRef.current = null;
    pendingFieldErrorsRef.current = null;
    // 확인을 기다리던 제출을 끝내 잠금을 풀고, 진행 중 표시도 지운다(그 제출의 finally 는 세션이 바뀌어 상태를 건드리지 않는다).
    cancelCheckRef.current?.();
    cancelCheckRef.current = null;
    setCheckingOnSubmit(false);
    setOpen(next);
    setCreated(null);
    setSuspendedUserId(null);
    setLookup(null);
    reset(next ? { ...EMPTY_FORM, temporaryPassword: generateTemporaryPassword() } : EMPTY_FORM);
  };

  /**
   * 제출 뒤 서버 답(409)에 맞춰 판정을 바꾼다(WD-771). 응답을 기다리는 사이 이메일을 고쳤으면 그 이메일의 판정이 아니므로 두지 않는다.
   * userId 는 SUSPENDED 일 때만 싣는다(409 MEMBER_SUSPENDED 본문의 errors.userId — 상세 링크용). 나머지 409 본문에는 없다.
   *
   * @returns 판정을 바꿨으면 true, 이메일이 바뀌어 두지 않았으면 false — 호출부가 무반응으로 두지 않고 따로 알리게.
   */
  const replaceVerdict = (
    submittedEmail: string,
    status: 'INACTIVE' | 'MEMBER' | 'SUSPENDED',
    userId: number | null = null,
  ): boolean => {
    const requested = normalizeEmail(submittedEmail);
    if (normalizeEmail(form.getValues('email')) !== requested) return false;
    setLookup((prev) => ({
      email: requested,
      result: { status, userId, name: prev?.email === requested ? prev.result.name : null },
    }));
    return true;
  };

  const onSubmit = async (values: AddMemberFormData) => {
    const email = values.email.trim();
    const roleIds = canAssignRoles ? values.roleIds : [];
    // 기존 계정이면 이름·임시 비밀번호를 키째 뺀다(WD-751) — 숨긴 칸에 남은 값(새 계정이라 생각하고 미리 쓴 것)이 섞여 나가지 않게.
    const request: AddMemberRequest = hidesAccountFields
      ? { email, roleIds }
      : { email, name: values.name.trim(), temporaryPassword: values.temporaryPassword, roleIds };
    try {
      const { data } = await usersApi.addMember(request);
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
      // 코드별 처리 표(CONFLICTS) — 모르는 코드면 undefined.
      const conflict = data?.code && Object.hasOwn(CONFLICTS, data.code) ? CONFLICTS[data.code] : undefined;
      // 정지된 멤버: 다시 추가가 아니라 상세에서 재활성화해야 한다 — 이메일 아래에 상세 링크.
      // 판정도 SUSPENDED 로 바꿔 제출을 잠근다(WD-771) — EXISTING 으로 남으면 「기존 계정 추가」를 또 누르게 된다. 안내는 칸 오류·링크가 맡고,
      // 같은 내용의 정지 멤버 배너는 칸 오류가 있는 동안 그리지 않는다(아래 showSuspendedBanner).
      if (data?.code === 'MEMBER_SUSPENDED') {
        const parsedUserId = Number(data.errors?.userId);
        const userId = data.errors?.userId && Number.isFinite(parsedUserId) ? parsedUserId : null;
        if (replaceVerdict(email, 'SUSPENDED', userId)) {
          setSuspendedUserId(userId);
          setError('email', { message: '이미 이 워크스페이스의 멤버입니다(정지됨)' }, { shouldFocus: true });
        } else {
          // 응답을 기다리는 사이 이메일을 고쳤다(WD-779) — 칸 오류·상세 링크는 지금 이메일의 것이 아니므로 달지 않고, 제출한 이메일에 대해 버튼 위에 알린다.
          setError('root', { message: CONFLICTS.MEMBER_SUSPENDED.submittedMessage(email) });
        }
        return;
      }
      // 새 계정으로 보냈는데 그사이 같은 이메일의 계정이 만들어졌다(409 ACCOUNT_ALREADY_EXISTS, WD-779). 판정(NEW)이 낡았으므로
      // 그 이메일로 한 번 다시 확인해 판정을 바꾼다(기존 계정이면 이름·임시 비밀번호 칸이 숨겨진다). 기존 확인 함수를 그대로 써 세대 확인·진행 중 공유를 따른다.
      if (data?.code === 'ACCOUNT_ALREADY_EXISTS') {
        const session = sessionRef.current;
        // 응답 사이 이메일을 고쳤으면 다시 확인하지 않고 낡음으로 본다 — 다시 확인 중 이메일을 고친 경우와 함께 아래 한곳에서 알린다.
        const outcome: LookupOutcome =
          normalizeEmail(form.getValues('email')) === normalizeEmail(email) ? await runLookup(email) : { kind: 'stale' };
        // 다시 확인하는 사이 닫았으면 아무것도 남기지 않는다.
        if (sessionRef.current !== session) return;
        if (outcome.kind === 'stale') {
          // 제출한 이메일이 지금 이메일이 아니다 — 판정은 두지 않고 제출한 이메일로 알린다(다른 409 와 같은 규칙).
          setError('root', { message: CONFLICTS.ACCOUNT_ALREADY_EXISTS.submittedMessage(email) });
          return;
        }
        // 다시 확인이 실패하면 낡은 NEW 판정을 버리고 판정 없이 오류만 남긴다(결과면 runLookup 이 판정을 이미 바꿨다).
        if (outcome.kind === 'failed') setLookup(null);
        setError('root', {
          message: '같은 이메일의 계정이 방금 만들어져 추가하지 못했습니다 — 확인 결과를 보고 다시 추가하세요',
        });
        return;
      }
      // 판정과 제출 사이에 상태가 바뀌었다(409 ACCOUNT_INACTIVE·MEMBER_ALREADY_EXISTS, WD-771) — 서버가 이미 답했으므로 다시 묻지 않고
      // 판정을 바꿔 그 안내(배너)를 보이고 제출을 잠근다. 같은 문구를 칸 오류로 또 보이지 않는다. 이름은 확인 때 받은 것을 쓴다.
      if (conflict?.status) {
        if (replaceVerdict(email, conflict.status)) {
          // 버튼이 잠기며 포커스가 사라지지 않게 이메일 칸으로 옮긴다(이메일을 고치는 것이 다음 행동).
          setFocus('email');
        } else {
          // 응답을 기다리는 사이 이메일을 고쳤다 — 지금 이메일의 판정은 아니지만, 무반응이면 왜 추가되지 않았는지 알 수 없다.
          setError('root', { message: conflict.submittedMessage(email) });
        }
        return;
      }
      // 기존 계정으로 판정해 이름·임시 비밀번호 없이 보냈는데 그사이 계정이 사라졌다(400 NEW_ACCOUNT_FIELDS_REQUIRED, WD-751).
      // 오류가 숨긴 칸에 붙으면 아무것도 보이지 않으므로 판정을 「새 계정」으로 바꿔 칸을 다시 보이고, 칸 오류와 함께 까닭을 알린다.
      if (data?.code === 'NEW_ACCOUNT_FIELDS_REQUIRED' && hidesAccountFields) {
        // 서버가 칸 오류를 싣지 않았으면(구버전 등) 두 칸 모두 필수로 본다 — 기존 계정 요청은 둘 다 보내지 않았다.
        pendingFieldErrorsRef.current = data.errors
          ? { name: data.errors.name, temporaryPassword: data.errors.temporaryPassword }
          : { name: '이름은 필수입니다', temporaryPassword: '임시 비밀번호는 필수입니다' };
        setLookup({ email: normalizeEmail(email), result: { status: 'NEW', userId: null, name: null } });
        setError('root', { message: '계정을 다시 확인했더니 새 계정입니다 — 이름과 임시 비밀번호를 입력해 주세요' });
        return;
      }
      // 코드를 모르는 409 는 판정을 바꾸지 않고 서버 문구를 이메일 아래에 보인다(WD-771 이전 동작). 다른 계정의 프로필 이메일과 같은 것은 막지 않는다(WD-271).
      if (response?.status === 409) {
        setError('email', { message: data?.message ?? '이미 이 워크스페이스의 멤버입니다' }, { shouldFocus: true });
        return;
      }
      // 검증 400 은 해당 필드 아래로, 모르는 필드 문구는 폼 상단으로. roleIds 는 필드 오류를 그리지 않아 목록에서 뺀다.
      if (applyFieldErrors(error, setError, ['email', 'name', 'temporaryPassword'])) return;
      // INVALID_ROLE·권한 부족·서버 오류 등은 폼 상단 오류로.
      setError('root', { message: extractApiError(error, '멤버를 추가하지 못했습니다') });
    }
  };

  /**
   * 제출(WD-751). 이미 멤버로 판정됐으면 보내지 않는다(Enter 로 하는 제출 포함). 판정 없이 제출하면 먼저 이메일을 확인하고,
   * 기존 계정·이미 멤버로 바뀌면 바로 추가하지 않는다 — 칸이 사라지고 버튼 문구가 바뀌므로 사용자가 바뀐 화면을 보고 다시 누른다.
   * 새 계정이거나 확인이 실패하면 지금 화면 그대로 이어서 제출한다(화면이 바뀌지 않으니 다시 누르게 할 까닭이 없다).
   * 확인을 기다리는 사이 이메일이 바뀌었거나 대화상자를 닫았으면(stale) 제출하지 않는다 — 확인하지 않은 이메일을 보내지 않게.
   * blur 로 시작된 같은 이메일의 확인이 진행 중이면 그것을 기다린다(요청 한 번). 잠금(useSubmitGuard)으로 감싸 확인 중 연타가 겹치지 않게 한다.
   */
  const submitForm = useSubmitGuard()(async (event) => {
    event?.preventDefault();
    if (blocksSubmit) return;
    const email = form.getValues('email');
    if (!lookup && isLookupableEmail(email)) {
      const session = sessionRef.current;
      setCheckingOnSubmit(true);
      // 닫으면(handleOpenChange) 확인 응답을 기다리지 않고 「낡음」으로 끝난다 — 제출 잠금이 붙잡힌 응답에 묶이지 않게(WD-771).
      const closed = new Promise<LookupOutcome>((resolve) => {
        cancelCheckRef.current = () => resolve({ kind: 'stale' });
      });
      const outcome = await Promise.race([runLookup(email), closed]).finally(() => {
        // 닫았다 다시 연 뒤라면 새 세션의 진행 중 표시·닫기 함수를 건드리지 않는다.
        if (sessionRef.current !== session) return;
        cancelCheckRef.current = null;
        setCheckingOnSubmit(false);
      });
      if (outcome.kind === 'stale') return;
      // 공유한 확인이 끝난 뒤에도 한 번 더 본다 — 기다리는 사이 이메일이 바뀌었거나 닫혔으면 멈춘다.
      if (sessionRef.current !== session || normalizeEmail(form.getValues('email')) !== normalizeEmail(email)) return;
      if (outcome.kind === 'result' && outcome.result.status !== 'NEW') return;
    }
    // 실제 제출이 시작되는 순간 잠금 ref 를 세운다(WD-779) — 다시 그려지기 전의 닫기도 handleOpenChange 가 막게. 끝나면(성공·실패·검증 실패) 내린다.
    submittingRef.current = true;
    try {
      await form.handleSubmit(onSubmit)();
    } finally {
      submittingRef.current = false;
    }
  });
  const pending = isSubmitting || checkingOnSubmit;
  // 정지 멤버 배너는 409 MEMBER_SUSPENDED 의 칸 오류(같은 문구·같은 링크)가 떠 있는 동안 그리지 않는다 — 안내와 링크가 두 번 나오지 않게.
  const showSuspendedBanner = verdict === 'SUSPENDED' && !errors.email;

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
          <form onSubmit={submitForm} noValidate className="flex min-h-0 flex-1 flex-col gap-4">
            <DialogHeader>
              <DialogTitle>멤버 추가</DialogTitle>
              <DialogDescription className="break-keep">
                이 워크스페이스에 사용자를 추가합니다. 이미 가입된 이메일이면 기존 계정이 추가됩니다.
              </DialogDescription>
            </DialogHeader>
            <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
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
              {/* 이메일 확인 결과 안내(WD-751) — 무엇이 일어날지 제출 전에 보여 준다. */}
              {isExistingAccount && (
                <InlineBanner variant="info" icon={<Info />} title="이미 가입된 계정입니다 — 이 워크스페이스에 추가합니다">
                  <p className="break-keep">
                    <span className="font-medium">{lookup?.result.name}</span>
                    <span className="text-muted-foreground"> · 이름과 비밀번호는 바뀌지 않고, 기존 비밀번호로 로그인합니다</span>
                  </p>
                </InlineBanner>
              )}
              {/* 비활성 계정(WD-771) — 이름이 없으면(확인이 실패한 채 제출해 409 로 알게 됨) 이름 자리를 뺀다. */}
              {isInactiveAccount && (
                <InlineBanner variant="warning" icon={<AlertTriangle />}>
                  <p className="break-keep">
                    비활성화된 계정입니다
                    {lookup?.result.name && (
                      <>
                        {' — '}
                        <span className="font-medium">{lookup.result.name}</span>
                      </>
                    )}
                    . 계정이 다시 활성화되기 전에는 멤버로 추가할 수 없습니다. 플랫폼 운영자에게 활성화를 요청하세요.
                  </p>
                </InlineBanner>
              )}
              {verdict === 'MEMBER' && (
                <InlineBanner variant="warning" icon={<AlertTriangle />} title="이미 이 워크스페이스의 멤버입니다" />
              )}
              {showSuspendedBanner && lookup && (
                <InlineBanner variant="warning" icon={<AlertTriangle />} title="이미 이 워크스페이스의 멤버입니다(정지됨)">
                  {/* 기존 409(MEMBER_SUSPENDED) 처리의 링크와 같은 경로·문구. userId 를 모르면 링크를 그리지 않는다. */}
                  {lookup.result.userId !== null && (
                    <Link
                      to={`/admin/users/${lookup.result.userId}`}
                      className="rounded-sm whitespace-nowrap text-primary hover:underline focus-visible:ring-[3px] focus-visible:ring-ring focus-visible:outline-none"
                      onClick={() => handleOpenChange(false)}
                    >
                      상세에서 재활성화 →
                    </Link>
                  )}
                </InlineBanner>
              )}
              {/* 기존 계정이면 이름·임시 비밀번호 칸을 숨긴다(비활성이 아니라 숨김 — 쓰지 않는 칸이 보이면 무엇을 넣어야 할지 헷갈린다). */}
              {!hidesAccountFields && (
                <>
                  <FormField label="이름" htmlFor="member-name" error={errors.name?.message} required>
                    <Input
                      id="member-name"
                      maxLength={50}
                      aria-invalid={!!errors.name}
                      aria-describedby={errors.name ? 'member-name-error' : undefined}
                      {...register('name', { onBlur: validateFilledOnBlur('name') })}
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
                        // maxLength 는 글자 수라 바이트 상한을 다 표현하지 못한다 — 72자로 1차 차단하고 한글 등 다바이트는 스키마가 막는다 (WD-605)
                        maxLength={PASSWORD_MAX_BYTES}
                        aria-invalid={!!errors.temporaryPassword}
                        aria-describedby={
                          errors.temporaryPassword ? 'member-password-help member-password-error' : 'member-password-help'
                        }
                        {...register('temporaryPassword', { onBlur: validateFilledOnBlur('temporaryPassword') })}
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
                      {PASSWORD_HELP_TEXT}. 기존 계정이면 사용되지 않습니다.
                    </p>
                  </FormField>
                </>
              )}
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
                        // 시스템 ADMIN 역할 — ADMIN 이 아니면 고를 수 없다(서버 RoleGrantGuard 가 403). 열어 두면 추가 시점에야 실패한다.
                        // 부여 불가 목록이 오기 전에도 바로 잠그도록 따로 둔다.
                        const adminOnlyLocked = canAssignRoles && !isCallerAdmin && role.isSystem && role.name === 'ADMIN';
                        // 그 밖에 호출자 범위(열람 등급·권한·허용 목록)를 넘는 역할도 같은 식으로 잠그고 서버 문구로 까닭을 보인다(WD-291).
                        const lockReason = isUser
                          ? undefined
                          : adminOnlyLocked
                            ? ADMIN_ROLE_LOCK_REASON
                            : blockedReasonById.get(role.id);
                        return (
                          <div key={role.id} className="flex flex-wrap items-center gap-x-2 gap-y-1">
                            <Checkbox
                              id={id}
                              checked={isUser || field.value.includes(role.id)}
                              disabled={isUser || !canAssignRoles || !!lockReason}
                              aria-describedby={lockReason ? `${id}-lock` : undefined}
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
                            {lockReason && (
                              // 체크박스 폭+간격(pl-6)만큼 들여 역할 이름과 왼쪽을 맞춘다.
                              <span id={`${id}-lock`} className="basis-full pl-6 text-xs text-muted-foreground break-keep">
                                {lockReason}
                              </span>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                />
                <p className="text-[13px] text-muted-foreground break-keep">
                  USER 는 워크스페이스 기본 역할로 항상 부여됩니다. 선택한 역할은 여기에 추가됩니다.
                </p>
                {grantBlockedQuery.isError && (
                  // 잠금을 못 받았다고 알린다 — 말없이 다 열어 두면 범위 밖 역할을 고르고 추가할 때에야 거부를 본다.
                  <p className="text-[13px] text-muted-foreground break-keep">
                    부여할 수 있는 역할 범위를 확인하지 못했습니다. 범위 밖 역할은 추가할 때 거부될 수 있습니다
                  </p>
                )}
                {!canAssignRoles && (
                  <p className="text-[13px] text-muted-foreground">역할 지정 권한이 없어 USER 역할로만 추가됩니다</p>
                )}
              </fieldset>
            </div>
            {/* 제출 실패 문구는 스크롤 영역 밖, 「추가」 버튼 바로 위에 둔다(WD-291) — 역할 목록을 내린 채 누르면 영역 맨 위의 문구는 화면
                밖에 있어, 눌러도 아무 일이 없는 것처럼 보였다. */}
            {errors.root && (
              <p role="alert" className="text-sm text-destructive break-keep">
                {errors.root.message}
              </p>
            )}
            <DialogFooter>
              {/* 실제 제출 중에만 잠근다. 제출 전 존재 확인만 도는 동안에는 닫을 수 있다 — 닫힌 뒤 확인 응답은 버려지고 제출로 이어지지 않는다(WD-771). */}
              <Button type="button" variant="outline" disabled={isSubmitting} onClick={() => handleOpenChange(false)}>
                취소
              </Button>
              {/* 이미 멤버·비활성 계정이면 진행 중 잠금(pending)이 아니라 제출 불가라 disabled 로 잠근다(PendingButton 규칙). */}
              <PendingSubmitButton pending={pending} disabled={blocksSubmit}>
                {pending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
                {isExistingAccount ? '기존 계정 추가' : '추가'}
              </PendingSubmitButton>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
