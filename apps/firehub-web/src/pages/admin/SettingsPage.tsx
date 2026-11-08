import { Bot, Boxes, Mail, RotateCcw, Save, Settings, ShieldCheck, Tags } from 'lucide-react';
import { lazy, type ReactNode, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../../api/settings';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '../../components/ui/alert-dialog';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { FormField } from '../../components/ui/form-field';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Input } from '../../components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { Textarea } from '../../components/ui/textarea';
import { useMyPermissions } from '../../hooks/queries/useMyPermissions';
import { useAiClassifyForm } from '../../hooks/useAiClassifyForm';
import { useAiCredentialForm } from '../../hooks/useAiCredentialForm';
import { useAuth } from '../../hooks/useAuth';
import { useEmbeddingSettingsForm } from '../../hooks/useEmbeddingSettingsForm';
import { useSettingsOverrideForm } from '../../hooks/useSettingsOverrideForm';
import { useSmtpSettingsForm } from '../../hooks/useSmtpSettingsForm';
import {
  useDirtyAggregator,
  useUnsavedChangesGuard,
} from '../../hooks/useUnsavedChangesGuard';
import { AI_BEHAVIOR_SETTING_SUPPORT, type AiBehaviorRunSettingKey } from '../../lib/ai-credential';
import { CLAUDE_MODEL_OPTIONS, typeChangeConfirmDescription, withPreservedValue } from '../../lib/ai-credential-screen';
import { extractApiError } from '../../lib/api-error';
import { HOSTING_DECLARE_PERMISSION } from '../../lib/hosting-location';
import { isDecimalSyntax, isIntegerSyntax } from '../../lib/settings-number';
import { cn } from '../../lib/utils';
import AiClassifySettingsTab from './AiClassifySettingsTab';
import { AiCredentialFieldset, OpencodeModelField } from './AiCredentialFieldset';
import EmbeddingSettingsTab from './EmbeddingSettingsTab';
import SecurityOnlySettingsPage from './security/SecurityOnlySettingsPage';
import { SettingFieldLabel } from './settings-lock';
import SmtpSettingsTab from './SmtpSettingsTab';

// 데이터 보안 탭은 security:settings 보유자만 열므로 지연 로딩한다.
const SecuritySettingsTab = lazy(() => import('./security/SecuritySettingsTab'));

/**
 * AI 탭의 <b>동작 설정</b> 6키 — 모델·시스템 프롬프트·Temperature·최대 턴 수·최대 응답 토큰·세션
 * 최대 토큰. 자격증명 3키(`ai.agent_type`/`ai.api_key`/`ai.cli_oauth_token`)는 `ai.credential`
 * 전용 문서(`useAiCredentialForm`, `AiCredentialFieldset.tsx`)로 옮겨가 이 폼에서 빠졌다 —
 * 설계서 §72 "키 변화" 표의 "평면 유지" 목록이 정확히 이 6키다.
 *
 * <b>테넌트 전용 설정이다.</b> 서버(`GET /settings?prefix=ai`)는 6키를 항상 내려주고, 테넌트가
 * 저장한 적 없는 키는 코드 기본값을 `value` 로, `overridden:false` 로 준다. 그래서 화면은 상태 배지나
 * 잠금 없이 평범한 입력 폼으로 그리고, 저장하지 않은 필드에만 "기본값" 힌트를 붙인다.
 * 폼 상태(조회·바꾼 키만 PUT·되돌리기)는 `useSettingsOverrideForm` 을 그대로 쓴다.
 */
type AIBehaviorForm = {
  'ai.model': string;
  'ai.max_turns': string;
  'ai.system_prompt': string;
  'ai.temperature': string;
  'ai.max_tokens': string;
  'ai.session_max_tokens': string;
};

// 조회 전 초기값은 빈 문자열이다 — 기본값은 서버가 값으로 내려주므로 화면이 따로 들고 있지 않는다
// (백엔드 `AiBehaviorDefaults` 가 단일 원본이다).
const AI_BEHAVIOR_DEFAULTS: AIBehaviorForm = {
  'ai.model': '',
  'ai.max_turns': '',
  'ai.system_prompt': '',
  'ai.temperature': '',
  'ai.max_tokens': '',
  'ai.session_max_tokens': '',
};

// 필드 표시 이름 — 저장이 거부된 필드(빈 값으로는 저장할 수 없는 키를 비운 채 저장 시도)를
// 이름으로 지목하는 데 쓴다.
const AI_FIELD_LABELS: Record<keyof AIBehaviorForm, string> = {
  'ai.model': '모델',
  'ai.max_turns': '최대 턴 수',
  'ai.system_prompt': '시스템 프롬프트',
  'ai.temperature': 'Temperature',
  'ai.max_tokens': '최대 응답 토큰',
  'ai.session_max_tokens': '세션 최대 토큰',
};

// 숫자 필드 검증 규칙. 하한·상한은 백엔드 SettingsService.validateValues 와 반드시 같아야 한다 —
// 어긋나면 "운영자가 저장한 값 때문에 테넌트가 아무 필드도 저장 못 하는" 상태가 만들어진다.
const NUMBER_RULES: {
  key: keyof AIBehaviorForm;
  min: number;
  max: number;
  integer: boolean;
  message: string;
}[] = [
  { key: 'ai.max_turns', min: 1, max: 50, integer: true, message: '1~50 사이의 정수를 입력하세요' },
  { key: 'ai.temperature', min: 0, max: 1, integer: false, message: '0.0~1.0 사이의 값을 입력하세요' },
  { key: 'ai.max_tokens', min: 1, max: 65536, integer: true, message: '1~65536 사이의 정수를 입력하세요' },
  {
    key: 'ai.session_max_tokens',
    min: 10000,
    max: 200000,
    integer: true,
    message: '10,000~200,000 사이의 정수를 입력하세요',
  },
];

/**
 * 시스템 프롬프트 최대 길이(글자 수, `string.length` = UTF-16 코드 단위 기준)(WD-536).
 *
 * 백엔드 `SettingsService.SYSTEM_PROMPT_MAX_LENGTH` 와 반드시 같아야 한다 — 서버도 Java `String.length()`
 * (UTF-16)로 세므로 두 쪽의 글자 수가 일치한다. 어긋나면 화면은 통과시키고 서버가 400 으로 거부하거나,
 * 서버가 받아 주는 값을 화면이 막는다. 입력창에 `maxLength` 를 걸지 않는 이유: 이미 상한을 넘겨 저장된 값이
 * 말없이 잘려 나가면 안 되므로, 자르지 않고 글자 수 표시와 저장 전 검증으로만 안내한다.
 */
const SYSTEM_PROMPT_MAX_LENGTH = 20_000;

/**
 * WD-59: 현재 고른 공급자(자격증명 유형)에서 반영되지 않는 실행 설정 칸 아래에 붙는 안내.
 * 칸은 비활성화하지만 값은 지우지 않는다 — 공급자를 다시 바꾸면 그 값이 그대로 쓰인다.
 * WD-643: 반영되지 않을 때만 FormField 의 `hint` 자리로 넘겨, 칸의 aria-describedby 에도 이어진다.
 */
const UNSUPPORTED_HINT = '현재 공급자에서는 적용되지 않습니다';

/**
 * 필드 키 하나에서 입력·문구 id 를 만든다(WD-643) — id 의 단일 출처.
 *
 * WD-590 에서는 JSX 에 하드코딩한 문구 id 와 describedby 를 만드는 정규식 파생 id 가 따로 놀아, 한쪽만 바뀌면
 * describedby 가 없는 요소를 가리킬 수 있었다. 숫자 칸은 `input` 을 FormField 의 `htmlFor` 로 넘기고 문구 id 는
 * FormField 가 같은 규칙(`-error`·`-desc`·`-hint`)으로 만든다. 라벨이 없어 FormField 를 못 쓰는 시스템 프롬프트는
 * 여기서 나온 `error`·`desc`·`count` 를 문구 id 와 describedby 에 함께 쓴다. 예: 'ai.max_turns' → 'ai-max-turns'.
 */
function fieldIds(key: keyof AIBehaviorForm) {
  const input = key.replace(/[._]/g, '-');
  return { input, error: `${input}-error`, desc: `${input}-desc`, count: `${input}-count` };
}

/**
 * aria-describedby 값을 실제로 그려진 문구 id 만으로 만든다(WD-643) — 그려지지 않은 문구(false)는 뺀다.
 * 순서: 오류 → 설명 → 글자 수(FormField 의 오류 → 설명 → 안내와 같은 규칙).
 */
function describedBy(...ids: (string | false)[]): string {
  return ids.filter(Boolean).join(' ');
}

/**
 * AI 동작 설정의 숫자 칸 하나 — 공용 FormField 의 얇은 감싸개(WD-643).
 *
 * 네 칸(최대 턴 수·Temperature·최대 응답 토큰·세션 최대 토큰)이 같은 모양이라 입력 속성만 여기서 정한다.
 * 라벨·기본값 배지(`labelAddon`)·오류·설명·지원 안 함 안내(`hint`)와 aria-describedby·aria-invalid 연결은 FormField 가
 * 맡는다. `announceError={false}` — 저장 시 여러 칸이 한꺼번에 틀리면 role=alert 가 겹쳐 읽히므로 끄고, 대신 저장 시
 * 첫 오류 칸으로 포커스를 옮긴다(`handleSaveClick`).
 */
function AiNumberSettingField({
  fieldKey,
  label,
  description,
  isDefault,
  value,
  onChange,
  error,
  min,
  max,
  step,
  disabled,
  unsupported = false,
}: {
  fieldKey: keyof AIBehaviorForm;
  label: string;
  description: ReactNode;
  isDefault: boolean;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  min: number;
  max: number;
  step?: number;
  disabled?: boolean;
  /** 지금 고른 공급자에서 반영되지 않는 칸인가(WD-59) — 그때만 안내를 그리고 칸 설명에 잇는다. */
  unsupported?: boolean;
}) {
  const { input } = fieldIds(fieldKey);
  return (
    <FormField
      label={label}
      htmlFor={input}
      labelAddon={<DefaultHint show={isDefault} />}
      error={error}
      announceError={false}
      description={description}
      hint={unsupported ? UNSUPPORTED_HINT : undefined}
    >
      <Input
        id={input}
        type="number"
        min={min}
        max={max}
        step={step}
        className="w-full max-w-md"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
      />
    </FormField>
  );
}

/**
 * 저장한 적 없는(=코드 기본값이 적용 중인) 필드 라벨 옆에 붙는 작은 힌트.
 * 저장된 필드에는 아무것도 붙이지 않는다 — 입력창의 값이 곧 적용 중인 값이라 따로 알릴 것이 없다.
 */
function DefaultHint({ show }: { show: boolean }) {
  if (!show) return null;
  return (
    <Badge variant="outline" className="font-normal text-muted-foreground">
      기본값
    </Badge>
  );
}

function AdminSettingsPage() {
  // 「데이터 보안」 탭은 security:settings 보유자에게만(스펙 §5-1 탭별 권한 노출). 최종 판정은 서버.
  // 같은 권한이 AI·임베딩 공급자의 자체 호스팅 선언도 가른다(S3 §5-5 — 서버 HostingDeclarationPolicy 와 같은 판정).
  const { permissions } = useMyPermissions();
  const canSecurity = permissions.has(HOSTING_DECLARE_PERMISSION);
  const [isSaving, setIsSaving] = useState(false);
  const [authStatus, setAuthStatus] = useState<{ valid: boolean; email?: string; subscriptionType?: string } | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);
  /** 동작 설정(6키) 저장 후 재조회가 실패했을 때의 지속 안내 — 옛 `useAiSettingsForm.staleNotice`
   * 를 대체한다. 자격증명 쪽의 같은 안내는 `cred.staleNotice`(별도 자원, 별도 슬롯)가 갖는다. */
  const [behaviorStaleNotice, setBehaviorStaleNotice] = useState<string | null>(null);
  /**
   * 자격증명 저장이 거부됐는데 함께 저장한 모델을 되돌리지도 못했을 때의 지속 안내(#720). 서버에
   * 남은 모델이 자격증명과 어긋나 AI 채팅이 실패할 수 있다는 사실을 토스트가 사라진 뒤에도 남긴다.
   * `behaviorStaleNotice` 와 슬롯을 나눈 이유: 그쪽은 메타 재조회가 성공하면 지워지는데, 이 안내는
   * 다음 저장이 성공해 두 자원이 다시 맞을 때까지 남아야 한다.
   */
  const [modelRollbackNotice, setModelRollbackNotice] = useState<string | null>(null);
  /** 유형 전환 저장 확인 다이얼로그 열림 여부 — `handleSaveClick` 이 열고, 확인/취소가 닫는다. */
  const [saveConfirmOpen, setSaveConfirmOpen] = useState(false);

  /**
   * 마지막으로 시작한 인증 확인의 일련번호. <b>늦게 도착한 낡은 응답을 버리기 위한</b> 것이다
   * (latest-request-wins). `SettingsPage.tsx` 초기 버전부터 있던 가드로, 자격증명이 번들에서
   * 전용 문서로 옮겨간 뒤에도 호출부 수·경쟁 조건의 성질은 그대로다(저장 성공 직후, 자격증명
   * 전환 직후 — 이제는 `cred.save()` 이후 — 두 곳에서 부른다).
   */
  const verifySeqRef = useRef(0);

  /**
   * OAuth 토큰/API 키가 실제로 서버에서 통하는지 확인해 배지(`authStatus`)를 갱신한다.
   * opencode 는 애초에 `AiCredentialFieldset` 이 이 버튼 자체를 그리지 않으므로(Anthropic 인증
   * 개념이 없다, 설계서 §184-189 표) 이 함수도 그 유형에서는 호출되지 않는다.
   */
  const verifyAuth = useCallback(async () => {
    const seq = ++verifySeqRef.current;
    setIsVerifying(true);
    // 직전 결과는 새 검증이 끝날 때까지 지운다(#722) — 저장 직후 자동 검증에서는 직전 결과가 **이전**
    // 자격증명의 것이라, 남겨 두면 응답을 기다리는 동안 방금 저장한 새 칸 옆에 옛 배지가 붙는다.
    setAuthStatus(null);
    try {
      const { data } = await settingsApi.verifyAuthStatus();
      if (seq !== verifySeqRef.current) return;
      setAuthStatus(data);
    } catch {
      if (seq !== verifySeqRef.current) return;
      setAuthStatus(null);
    } finally {
      if (seq === verifySeqRef.current) setIsVerifying(false);
    }
  }, []);

  /**
   * AI <b>자격증명</b>(`ai.credential`) 전용 폼(Task 10) — 유형·payload·비밀을 갖는다(테넌트
   * 전용, #706). 동작 설정 6키와는 완전히 독립된 서버 자원(전용 GET/PUT/probe 엔드포인트)이라
   * 저장도 따로 간다(`performSave` 참고).
   */
  const cred = useAiCredentialForm();

  /**
   * AI 분류 전용 공급자(#707) — 페이지가 소유한다(탭 언마운트에도 편집 보존). 조회는 탭이 처음
   * 열릴 때만 한다(`activate`) — 다른 탭 화면에서 요청을 만들지 않고, 로딩 게이트도 막지 않는다.
   */
  const classify = useAiClassifyForm();

  /** 동작 설정 6키 — 테넌트 전용 평면 설정(파일 헤더 주석 참고). */
  const behavior = useSettingsOverrideForm<AIBehaviorForm>({
    prefix: 'ai',
    defaults: AI_BEHAVIOR_DEFAULTS,
    onMetaRefreshed: () => setBehaviorStaleNotice(null),
  });
  const {
    isLoading: behaviorLoading,
    settings,
    form,
    errors,
    setErrors,
    hasChanges: behaviorHasChanges,
    updateField,
    handleReset,
    buildChangedPayload,
    original,
    commitSaved,
    restoreOriginal,
    refreshMeta,
  } = behavior;

  /**
   * 동작 설정 폼을 검증해 오류 문구를 세우고, 그 오류 객체를 돌려준다(WD-643 — 호출부가 첫 오류 칸을 고르려면
   * 방금 계산한 결과가 필요하다. `errors` 상태는 다음 렌더에야 바뀐다).
   */
  const validate = (): Partial<Record<keyof AIBehaviorForm, string>> => {
    const newErrors: Partial<Record<keyof AIBehaviorForm, string>> = {};

    NUMBER_RULES.forEach(({ key, min, max, integer, message }) => {
      const raw = form[key];
      const n = Number(raw);
      // 표기부터 본다 — 원문 문자열이 그대로 서버로 가므로 서버(Java)가 읽는 표기만 통과시킨다.
      // `Number()` 만으로 판정하면 `1e1`·`5.0`·`0x10` 이 통과하고 서버가 400 으로 거부한다(#727).
      // 빈 값·공백이 섞인 값도 여기서 걸린다.
      const syntaxOk = integer ? isIntegerSyntax(raw) : isDecimalSyntax(raw);
      if (!syntaxOk || n < min || n > max) {
        newErrors[key] = message;
      }
    });

    if (!form['ai.system_prompt'].trim()) {
      newErrors['ai.system_prompt'] = '시스템 프롬프트를 입력하세요';
    } else if (form['ai.system_prompt'].length > SYSTEM_PROMPT_MAX_LENGTH) {
      // 서버 상한(WD-536)과 같은 기준으로 저장 전에 막는다 — 그대로 보내면 서버가 400 으로 거부한다.
      newErrors['ai.system_prompt'] =
        `시스템 프롬프트는 ${SYSTEM_PROMPT_MAX_LENGTH.toLocaleString('ko-KR')}자 이하로 입력하세요`;
    }

    setErrors(newErrors);
    return newErrors;
  };

  /**
   * 검증 실패 뒤 첫 오류 칸으로 포커스를 옮기는 장치(WD-643).
   *
   * 바로 포커스하지 않고 오류가 그려진 렌더 뒤(아래 effect)에 옮긴다 — 지원 안 함으로 잠긴(disabled) 칸은 오류가 붙어야
   * 잠금이 풀리는데(`runFieldLocked`), setErrors 직후에는 아직 disabled 라 포커스를 받지 못한다.
   * `pendingErrorFocusRef` 는 "이번 저장 실패에 대한 포커스가 남았다"는 표시이고, `errorFocusRequest` 는 오류 객체가
   * 같아 보여도(예: 같은 오류로 다시 저장) effect 가 한 번 돌게 하는 신호다.
   */
  const aiTabRef = useRef<HTMLDivElement>(null);
  const pendingErrorFocusRef = useRef(false);
  const [errorFocusRequest, setErrorFocusRequest] = useState(0);
  useEffect(() => {
    if (!pendingErrorFocusRef.current) return;
    // 찾든 못 찾든 표시는 지운다 — 남겨 두면 나중의 다른 렌더가 사용자 포커스를 빼앗는다.
    pendingErrorFocusRef.current = false;
    // 손으로 적은 순서 목록 대신 화면(DOM) 순서상 첫 오류 칸을 고른다 — 사용자가 보는 순서와 늘 같다.
    // 이번 검증 오류의 칸만 고른다(같은 탭의 자격증명 칸이 자기 오류로 aria-invalid 일 수 있다).
    const invalidIds = new Set(Object.keys(errors).map((key) => fieldIds(key as keyof AIBehaviorForm).input));
    const target = Array.from(aiTabRef.current?.querySelectorAll<HTMLElement>('[aria-invalid="true"]') ?? []).find(
      (el) => invalidIds.has(el.id),
    );
    if (target) {
      // 포커스가 칸을 화면에 보이게 하고 연결된 오류 문구를 읽힌다 — 토스트는 띄우지 않는다(라이브 영역이 칸 낭독과 겹친다).
      target.focus();
    } else {
      // 포커스할 칸을 못 찾았을 때만 토스트로 알린다(무슨 일이 있었는지 알 방법이 없어지지 않게).
      toast.error('입력값을 확인하세요.');
    }
  }, [errors, errorFocusRequest]);

  /**
   * 실제 저장 실행 — 확인 다이얼로그(필요한 경우)를 지난 뒤에만 호출된다.
   *
   * <b>순서: 동작 설정(6키) 먼저, 자격증명(`ai.credential`) 다음.</b> 반대로 하면 opencode
   * 공급자·모델을 <b>같은 저장에서 함께 바꾸는</b> 흔한 경로가 막힌다 — 자격증명 PUT 의 opencode
   * 검증(`OpencodeCredentialValidation.checkProviderConsistency`, `AiCredentialController
   * .validateOpencode` 137-148행)은 <b>지금 DB 에 저장된 `ai.model`</b>을 읽어 요청의 새
   * `providerId` 와 비교한다. 자격증명을 먼저 저장하면 이 비교가 "새 providerId vs 아직 안 바뀐
   * 옛 `ai.model`" 이 되어 정당한 공급자 전환 + 모델 재선택 조합이 400 으로 막힌다. 동작 설정을
   * 먼저 저장하면 그 시점에 `ai.model` 이 이미 새 값으로 확정되므로 뒤이은 자격증명 검증이 항상
   * 최신 상태와 비교한다. (반대 방향 — opencode→sdk 전환 — 은 순서와 무관하게 안전하다: 그
   * 검사는 `agentType==='opencode'` 요청에만 걸린다, 같은 컨트롤러 70행.)
   *
   * <b>원자성은 없지만, 모델만은 보상한다(#720).</b> 두 자원이 별도 엔드포인트인 이상 트랜잭션은
   * 없다(Ruling #10 이 설정 저장 전반의 트랜잭션 부재를 파킹했다). 동작 설정 저장이 실패해도
   * 자격증명 저장은 계속 시도한다(독립 자원). 다만 <b>`ai.model` 은 독립이 아니다</b> — 모델 형식이
   * 자격증명 유형·공급자에 묶여 있어(opencode 는 `공급자/모델`, Claude 유형은 Claude 모델 id),
   * 모델만 저장되고 자격증명이 거부되면 "새 유형 형식의 모델 + 옛 유형 자격증명"이 남아 AI 채팅이
   * 즉시 깨진다. 자격증명 PUT 은 서버가 프로브를 다시 돌려 흔히 거부되므로(잘못된 키·타임아웃·
   * 미해석 호스트) 이 경로는 드물지 않다. 그래서 이번 저장에서 `ai.model` 을 썼고 자격증명 저장이
   * 거부되면 <b>`ai.model` 만</b> 저장 전 값으로 되돌리는 보상 PUT 을 보낸다(`rollbackModel`).
   * 나머지 5키는 자격증명과 무관한 독립 편집이라 그대로 둔다. 폼에는 사용자가 고른 모델이 남고
   * `original` 만 이전 값으로 돌아가므로, 자격증명을 고친 뒤 그대로 다시 저장할 수 있다.
   *
   * 보상의 한계: 키 단위 삭제 통로가 없어, 저장한 적 없던(`overridden:false`) 모델도 보상 뒤에는
   * "기본값과 같은 값이 저장된" 상태가 된다(동작은 같다). 보상 PUT 자체가 실패하면 되돌릴 방법이
   * 없으므로 지속 배너(`modelRollbackNotice`)로 어긋난 상태를 알린다.
   *
   * <b>토스트 두 번은 의도적이다</b> — 두 자원은 독립적이고 사용자는 둘의 결과를 각각 알아야
   * 한다. `cred.save()` 는 훅 내부에서 스스로 성공/실패 토스트를 띄우고, 쓰기 성공 여부를
   * boolean 으로도 돌려준다(Ruling #42) — 이 함수는 그 값으로 `verifyAuth()` 호출과 모델 보상을
   * 가른다. 단 모델을 자격증명과 함께 저장하는 경우에는 "설정이 저장되었습니다" 를 자격증명 결과가
   * 나올 때까지 미룬다 — 곧 되돌릴 수도 있는 저장을 성공이라고 먼저 알리면 거부 토스트와 모순된다.
   */
  /** 동작 설정 저장 뒤 메타(기본값 힌트)를 다시 읽는다 — 실패하면 낡음 안내를 세운다. */
  const refreshBehaviorMeta = useCallback(() => {
    refreshMeta().catch(() => {
      const message =
        '저장은 완료됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';
      setBehaviorStaleNotice(message);
      toast.error(message);
    });
  }, [refreshMeta]);

  /**
   * 자격증명 저장이 거부됐을 때 방금 저장한 `ai.model` 을 저장 전 값으로 되돌린다(#720, 보상 PUT).
   * 성공하면 `original` 의 모델만 이전 값으로 돌려 폼의 새 모델이 다시 "미저장 변경"이 되게 한다.
   * 실패하면(또는 되돌릴 이전 값이 비어 있으면) 서버에는 어긋난 모델이 남으므로 지속 배너를 세운다.
   */
  const rollbackModel = useCallback(
    async (previousModel: string, attemptedModel: string, otherKeysSaved: boolean) => {
      try {
        // 빈 값은 서버가 저장을 거부한다(조회 실패로 이전 값을 모르는 경우) — 보내지 않고 실패로 다룬다.
        if (previousModel.trim() === '') throw new Error('이전 모델 값을 알 수 없다');
        await settingsApi.update({ settings: { 'ai.model': previousModel } });
        restoreOriginal('ai.model', previousModel);
        toast.error(
          '자격증명 저장이 거부되어 모델 변경도 저장하지 않았습니다. 자격증명을 고친 뒤 다시 저장하세요.',
        );
        if (otherKeysSaved) toast.success('모델을 제외한 설정이 저장되었습니다.');
      } catch {
        const message = `자격증명 저장이 거부됐는데 모델 변경을 되돌리지 못했습니다. 저장된 모델(${attemptedModel})이 현재 자격증명과 맞지 않아 AI 채팅이 실패할 수 있습니다 — 자격증명을 고쳐 다시 저장하거나, 모델을 다시 선택해 저장하세요.`;
        setModelRollbackNotice(message);
        toast.error(message);
      }
    },
    [restoreOriginal],
  );

  const performSave = useCallback(async () => {
    setIsSaving(true);
    try {
      // 이번 저장에서 `ai.model` 을 자격증명과 함께 썼는가 — 그렇다면 자격증명 결과가 나온 뒤에
      // 성공 안내(또는 모델 보상)를 한다. null 이면 보상 대상이 아니다.
      let pendingModel: { previous: string; attempted: string; otherKeysSaved: boolean } | null = null;
      let behaviorSaved = false;

      if (behaviorHasChanges) {
        const { payload, droppedChangedKeys } = buildChangedPayload();
        if (droppedChangedKeys.length > 0) {
          const names = droppedChangedKeys.map((key) => AI_FIELD_LABELS[key]).join(', ');
          toast.error(`${names}을(를) 비워 둔 채로는 저장할 수 없습니다.`);
        } else {
          try {
            await settingsApi.update({ settings: payload });
            commitSaved();
            behaviorSaved = true;
            if ('ai.model' in payload && cred.hasUnsavedInput) {
              pendingModel = {
                previous: original['ai.model'],
                attempted: payload['ai.model'],
                otherKeysSaved: Object.keys(payload).length > 1,
              };
            } else {
              toast.success('설정이 저장되었습니다.');
              refreshBehaviorMeta();
            }
          } catch (err) {
            // 동작 설정 저장 실패 — 자격증명은 별도 자원이므로 아래에서 계속 시도한다(주석 참고).
            // 서버가 알려준 거부 사유를 그대로 보여준다(#727).
            toast.error(extractApiError(err, '설정 저장에 실패했습니다.'));
          }
        }
      }

      let credSaved: boolean | null = null;
      if (cred.hasUnsavedInput) {
        // `cred.save()` 가 쓰기(PUT) 성공 여부를 boolean 으로 돌려준다(Ruling #42, fix
        // round 1) — 실패한 쓰기 뒤에 `verifyAuth()` 를 돌리면 여전히 낡은(그러나 여전히 유효할
        // 수 있는) 자격증명을 검사해 사용자를 혼란스럽게 한다. 성공했을 때만 부른다.
        const saved = await cred.save();
        credSaved = saved;
        if (pendingModel) {
          if (saved) {
            toast.success('설정이 저장되었습니다.');
          } else {
            // 자격증명이 거부됐다 — 모델만 새 형식으로 남지 않게 저장 전 값으로 되돌린다(#720).
            await rollbackModel(pendingModel.previous, pendingModel.attempted, pendingModel.otherKeysSaved);
          }
          // 보상까지 끝난 최종 상태의 메타를 읽는다(먼저 읽으면 되돌리기 전 값을 본다).
          refreshBehaviorMeta();
        }
        if (!saved) {
          // 쓰기 자체가 실패했다 — 훅이 이미 자체 토스트로 알렸다. 인증 확인도, opencode 배지
          // 초기화도 여기서는 의미가 없다(둘 다 "쓰기가 성공했다"를 전제한다).
        } else if (cred.agentType !== 'opencode') {
          verifyAuth();
        } else {
          // opencode 로 저장하면 서버가 이전 유형의 비밀을 폐기한다(유형이 바뀐 경우) — 이전
          // 유형의 "✓ 인증됨" 배지를 그대로 두면, 사용자가 Select 를 다시 sdk/cli/cli-api 로
          // 로컬에서만 돌렸을 때 이미 사라진 자격증명 옆에 낡은 성공 배지가 붙는다.
          setAuthStatus(null);
        }
      }

      // 어긋남 안내는 두 자원 중 하나라도 새로 저장돼 상태가 바뀌었고, 이번에 실패한 쓰기가 없을
      // 때 지운다(방금 `rollbackModel` 이 세운 안내는 credSaved=false 라 여기서 지워지지 않는다).
      if ((behaviorSaved || credSaved === true) && credSaved !== false) {
        setModelRollbackNotice(null);
      }
    } finally {
      setIsSaving(false);
    }
  }, [
    behaviorHasChanges,
    buildChangedPayload,
    commitSaved,
    original,
    refreshBehaviorMeta,
    rollbackModel,
    cred,
    verifyAuth,
  ]);

  /**
   * "저장" 클릭 — 파괴적 결과(유형 전환 = 이전 유형 비밀 폐기)가 예정돼 있으면 확인 다이얼로그를
   * 먼저 연다(설계서 §193 "유형 전환").
   */
  const handleSaveClick = () => {
    const validationErrors = validate();
    // 오류가 하나라도 있으면 저장하지 않는다(WD-643) — 어느 칸으로 포커스할지와 저장 차단은 별개다.
    if (Object.keys(validationErrors).length > 0) {
      // 오류 문구들을 role=alert 로 한꺼번에 읽히는 대신, 렌더 뒤 첫 오류 칸으로 포커스를 옮겨 라벨과 연결된 오류
      // 문구(describedby)를 읽게 한다. 검증 실패일 때만이라 확인 다이얼로그·저장 경로에는 끼어들지 않는다.
      pendingErrorFocusRef.current = true;
      setErrorFocusRequest((n) => n + 1);
      return;
    }
    if (cred.typeChanged) {
      setSaveConfirmOpen(true);
    } else {
      void performSave();
    }
  };

  // 테넌트가 아직 저장하지 않은 키인가 — 서버가 `overridden:false` 로 알려 준다(값은 코드 기본값).
  // 응답이 없으면(조회 실패) 판단할 근거가 없으므로 힌트를 붙이지 않는다.
  const isDefault = (key: keyof AIBehaviorForm) => settings[key]?.overridden === false;

  /**
   * 이메일 탭의 폼 상태도 <b>페이지가 소유한다</b>. Radix `TabsContent` 가 비활성 탭을
   * 언마운트하므로 탭이 상태를 갖고 있으면 탭 전환이 미저장 편집을 죽인다.
   */
  const smtp = useSmtpSettingsForm();

  /**
   * 임베딩 탭도 같은 이유로 페이지가 소유한다(#713 리뷰 fix round 1) — 편집 가능한 폼이 된
   * 뒤에도 이 훅이 빠져 있어 탭 전환 시 입력한 모델·키가 경고 없이 사라지고 있었다.
   */
  const embedding = useEmbeddingSettingsForm();

  // 탭별 dirty 상태를 합산해 페이지 전체 dirty 여부를 결정한다(이슈 #86). AI 탭은 이제 두 독립
  // 자원(동작 설정 6키 + 자격증명)을 갖고 있어 `cred.hasUnsavedInput` 도 함께 보고해야 한다 —
  // 빠뜨리면 자격증명만 입력하고 떠나는 이탈이 조용히 통과한다.
  const { isAnyDirty, makeReporter } = useDirtyAggregator();
  const aiReporter = makeReporter('ai');
  const smtpReporter = makeReporter('smtp');
  const smtpHasChanges = smtp.hasChanges;
  const credDirty = cred.hasUnsavedInput;
  useEffect(() => {
    aiReporter(behaviorHasChanges || credDirty);
  }, [aiReporter, behaviorHasChanges, credDirty]);
  useEffect(() => {
    smtpReporter(smtpHasChanges);
  }, [smtpReporter, smtpHasChanges]);
  // AI 분류 탭도 자기 dirty 를 따로 보고한다 — 탭이 닫혀 있어도 편집이 페이지에 살아 있으므로
  // 이탈 가드가 그것을 알아야 한다.
  const classifyReporter = makeReporter('ai-classify');
  const classifyDirty = classify.hasUnsavedInput;
  useEffect(() => {
    classifyReporter(classifyDirty);
  }, [classifyReporter, classifyDirty]);
  // 임베딩 탭도 같은 방식으로 보고한다.
  const embeddingReporter = makeReporter('embedding');
  const embeddingDirty = embedding.hasChanges;
  useEffect(() => {
    embeddingReporter(embeddingDirty);
  }, [embeddingReporter, embeddingDirty]);
  const { dialog: unsavedDialog } = useUnsavedChangesGuard(isAnyDirty);

  // WD-59: 공급자별 실행 설정 반영 여부. 저장된 유형이 아니라 지금 고른 유형 기준이라, 유형 선택만 바꿔도 표시가 따라 바뀐다.
  const runSupport = AI_BEHAVIOR_SETTING_SUPPORT[cred.agentType];
  // 자격증명 조회가 실패·403 이면 cred.agentType 은 초기값(sdk)일 뿐 실제 유형을 모른다 — 그때는 잠그지 않는다
  // (opencode 테넌트에 "적용되지 않습니다"를 잘못 띄우지 않게).
  const agentTypeKnown = !cred.loadFailed && !cred.isLocked;
  // 반영 안 되는 칸은 잠그되, 검증 오류가 붙은 칸은 열어 둔다 — 다른 유형에서 잘못 입력한 값이 남은 채 유형을 바꾸면
  // 잠긴 칸의 오류가 저장을 막는데 고칠 방법이 없어진다. 고쳐서 검증을 통과하면 다시 잠긴다.
  const runUnsupported = (key: AiBehaviorRunSettingKey) => agentTypeKnown && !runSupport[key];
  const runFieldLocked = (key: AiBehaviorRunSettingKey) => runUnsupported(key) && !errors[key];
  // 시스템 프롬프트가 상한을 넘었는지(WD-536) — 글자 수 표시 색을 바꾸고, 넘었을 때만 글자 수를 칸 설명에 잇는다
  // (WD-643 — 평소에도 이으면 칸에 들어갈 때마다 숫자를 읽어 시끄럽다). 저장 차단은 validate() 가 한다.
  const systemPromptOverLimit = form['ai.system_prompt'].length > SYSTEM_PROMPT_MAX_LENGTH;
  // 시스템 프롬프트 칸의 id 묶음(WD-643 — 문구 id·describedby 의 단일 출처).
  const promptIds = fieldIds('ai.system_prompt');

  if (behaviorLoading || cred.isLoading) {
    return (
      <div className="max-w-2xl mx-auto space-y-6 p-6">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto space-y-6 p-6">
      <div className="flex items-center gap-3">
        <Settings className="h-6 w-6" />
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">설정</h1>
      </div>

      <Tabs
        defaultValue="ai"
        onValueChange={(v) => {
          // 분류 자격증명은 탭이 처음 열릴 때만 조회한다(다른 탭에서 요청을 만들지 않게).
          if (v === 'ai-classify') classify.activate();
        }}
      >
        {/* overflow-x-auto만 주면 CSS 사양상 overflow-y가 visible→auto로 승격되어
            탭 콘텐츠가 고정 높이를 미세 초과할 때 유령 세로 스크롤바가 생긴다.
            가로 스크롤(좁은 화면 대응)은 유지하되 세로는 명시적으로 hidden 고정. */}
        <TabsList className="overflow-x-auto overflow-y-hidden flex-nowrap">
          {/* 일반 탭 — 다른 탭과 아이콘 일관성 유지 */}
          <TabsTrigger value="general">
            <Settings className="h-4 w-4" />
            일반
          </TabsTrigger>
          <TabsTrigger value="ai">
            <Bot className="h-4 w-4" />
            AI 에이전트
          </TabsTrigger>
          <TabsTrigger value="ai-classify">
            <Tags className="h-4 w-4" />
            AI 분류
          </TabsTrigger>
          <TabsTrigger value="email">
            <Mail className="h-4 w-4" />
            이메일
          </TabsTrigger>
          <TabsTrigger value="embedding">
            <Boxes className="h-4 w-4" />
            임베딩
          </TabsTrigger>
          {canSecurity && (
            <TabsTrigger value="security">
              <ShieldCheck className="h-4 w-4" />
              데이터 보안
            </TabsTrigger>
          )}
        </TabsList>

        {/* 일반 탭 */}
        <TabsContent value="general" className="mt-6">
          <Card className="card-hover">
            <CardContent className="flex flex-col items-center justify-center py-16">
              <Settings className="h-12 w-12 text-muted-foreground mb-4" />
              <p className="text-base leading-6 font-semibold">일반 설정</p>
              <p className="text-sm text-muted-foreground mt-1">
                준비 중입니다
              </p>
            </CardContent>
          </Card>
        </TabsContent>

        {/* AI 에이전트 탭 */}
        {/* aiTabRef: 검증 실패 시 첫 오류 칸을 찾는 범위(WD-643) */}
        <TabsContent ref={aiTabRef} value="ai" className="mt-6 space-y-6">
          <Card className="card-hover">
            <CardHeader>
              <CardTitle>모델 설정</CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              {/* "지금 화면을 믿지 말고 다시 읽어라" 안내 — 두 자원 각자의 슬롯을 따로 그린다.
                  뭉뚱그리면 동작 설정 저장 실패가 자격증명 문제로 잘못 읽힌다(그 반대도 마찬가지). */}
              {behaviorStaleNotice && <InlineBanner variant="warning">{behaviorStaleNotice}</InlineBanner>}
              {cred.staleNotice && <InlineBanner variant="warning">{cred.staleNotice}</InlineBanner>}
              {modelRollbackNotice && <InlineBanner variant="warning">{modelRollbackNotice}</InlineBanner>}

              <AiCredentialFieldset
                cred={cred}
                authStatus={authStatus}
                isVerifying={isVerifying}
                onVerifyAuth={verifyAuth}
                canDeclareSelfHosted={canSecurity}
              />

              <Separator />

              {/* 모델 — ai.credential 이 아니라 동작 설정 6키의 하나다(§48 "ai.model 은 평면 키로
                  남는다"). opencode 는 자격증명의 providerId/모델 불러오기 결과에 딸린 4상태
                  칸(`OpencodeModelField`)을, 나머지 세 유형은 옛 정적 Claude 모델 목록을 쓴다. */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-model"
                  badge={<DefaultHint show={isDefault('ai.model')} />}
                >
                  모델
                </SettingFieldLabel>
                {cred.agentType === 'opencode' ? (
                  <OpencodeModelField
                    cred={cred}
                    modelValue={form['ai.model']}
                    onModelChange={(v) => updateField('ai.model', v)}
                  />
                ) : (
                  <>
                    <Select
                      value={form['ai.model']}
                      onValueChange={(value) => updateField('ai.model', value)}
                    >
                      <SelectTrigger id="ai-model" className="w-full max-w-md">
                        <SelectValue placeholder="모델을 선택하세요" />
                      </SelectTrigger>
                      <SelectContent>
                        {/* 목록 밖 저장값(예: opencode 에서 넘어온 `공급자/모델`)도 보존해 보여 준다(#720)
                            — 빈 칸으로 그리면 저장된 모델이 무엇인지, 왜 채팅이 실패하는지 알 수 없다. */}
                        {withPreservedValue(CLAUDE_MODEL_OPTIONS, form['ai.model']).map((opt) => (
                          <SelectItem key={opt.value} value={opt.value}>
                            {opt.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <p className="text-sm text-muted-foreground">AI 에이전트가 사용할 Claude 모델</p>
                    {form['ai.model'] !== '' &&
                      !CLAUDE_MODEL_OPTIONS.some((opt) => opt.value === form['ai.model']) && (
                        <p className="text-sm text-destructive">
                          Claude 모델 목록에 없는 값입니다. 이 에이전트 유형에서는 동작하지 않을 수 있으니 모델을 다시
                          선택하세요.
                        </p>
                      )}
                  </>
                )}
              </div>

              <Separator />

              {/* 숫자 칸 4개 — 라벨·입력·오류·설명·안내 마크업과 id 연결은 AiNumberSettingField 가 맡는다(WD-643). */}
              <AiNumberSettingField
                fieldKey="ai.max_turns"
                label="최대 턴 수"
                description="에이전트가 도구를 사용할 수 있는 최대 반복 횟수 (1~50)"
                isDefault={isDefault('ai.max_turns')}
                value={form['ai.max_turns']}
                onChange={(v) => updateField('ai.max_turns', v)}
                error={errors['ai.max_turns']}
                min={1}
                max={50}
                disabled={runFieldLocked('ai.max_turns')}
                unsupported={runUnsupported('ai.max_turns')}
              />

              <Separator />

              <AiNumberSettingField
                fieldKey="ai.temperature"
                label="Temperature"
                description="응답의 창의성 수준 (0.0: 결정적, 1.0: 창의적)"
                isDefault={isDefault('ai.temperature')}
                value={form['ai.temperature']}
                onChange={(v) => updateField('ai.temperature', v)}
                error={errors['ai.temperature']}
                min={0}
                max={1}
                step={0.1}
                disabled={runFieldLocked('ai.temperature')}
                unsupported={runUnsupported('ai.temperature')}
              />

              <Separator />

              <AiNumberSettingField
                fieldKey="ai.max_tokens"
                label="최대 응답 토큰"
                description="AI 응답의 최대 토큰 수 (1~65536)"
                isDefault={isDefault('ai.max_tokens')}
                value={form['ai.max_tokens']}
                onChange={(v) => updateField('ai.max_tokens', v)}
                error={errors['ai.max_tokens']}
                min={1}
                max={65536}
                disabled={runFieldLocked('ai.max_tokens')}
                unsupported={runUnsupported('ai.max_tokens')}
              />

              <Separator />

              {/* 세션 최대 토큰은 공급자별 실행 설정(WD-59)이 아니라 잠금·안내가 없다. */}
              <AiNumberSettingField
                fieldKey="ai.session_max_tokens"
                label="세션 최대 토큰"
                description="세션의 입력 토큰이 이 값을 초과하면 대화를 자동 요약하고 새 세션으로 전환합니다 (10,000~200,000)"
                isDefault={isDefault('ai.session_max_tokens')}
                value={form['ai.session_max_tokens']}
                onChange={(v) => updateField('ai.session_max_tokens', v)}
                error={errors['ai.session_max_tokens']}
                min={10000}
                max={200000}
                step={10000}
              />
            </CardContent>
          </Card>

          <Card className="card-hover">
            <CardHeader>
              {/* 이 카드에는 별도 Label 이 없으므로(제목이 곧 필드 이름) 기본값 힌트를 제목 줄에 붙인다. */}
              <CardTitle className="flex flex-wrap items-center gap-2">
                {/* 아래 Textarea 의 aria-labelledby 대상 — placeholder 는 입력하면 사라져 이름이 못 된다(WD-590). */}
                <span id="ai-system-prompt-label">시스템 프롬프트</span>
                <DefaultHint show={isDefault('ai.system_prompt')} />
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Textarea
                  id={promptIds.input}
                  aria-labelledby="ai-system-prompt-label"
                  aria-invalid={errors['ai.system_prompt'] ? true : undefined}
                  // WD-643: 실제로 그려진 문구 id 만 잇는다 — 오류(있을 때)·설명(늘)·글자 수(상한을 넘었을 때).
                  aria-describedby={describedBy(
                    errors['ai.system_prompt'] ? promptIds.error : false,
                    promptIds.desc,
                    systemPromptOverLimit ? promptIds.count : false,
                  )}
                  rows={15}
                  className="font-mono text-sm"
                  value={form['ai.system_prompt']}
                  onChange={(e) => updateField('ai.system_prompt', e.target.value)}
                  placeholder="시스템 프롬프트를 입력하세요..."
                />
                {/* 글자 수(WD-536): 상한에 얼마나 남았는지 늘 보여 주고, 넘으면 destructive 색으로 바꿔
                    저장 전에도 넘었다는 것을 알 수 있게 한다. */}
                <p
                  id={promptIds.count}
                  // 테스트 훅도 같은 id 값을 쓴다(WD-643 — 글자 수 id 의 출처는 fieldIds 하나).
                  data-testid={promptIds.count}
                  className={cn(
                    'text-right text-xs tabular-nums',
                    systemPromptOverLimit ? 'text-destructive' : 'text-muted-foreground',
                  )}
                >
                  {form['ai.system_prompt'].length.toLocaleString('ko-KR')} /{' '}
                  {SYSTEM_PROMPT_MAX_LENGTH.toLocaleString('ko-KR')}자
                </p>
                {/* 오류 문구에 role=alert 를 두지 않는다(WD-643) — 저장 시 첫 오류 칸으로 포커스가 와 describedby 로 읽힌다. */}
                {errors['ai.system_prompt'] && (
                  <p id={promptIds.error} className="text-sm text-destructive">
                    {errors['ai.system_prompt']}
                  </p>
                )}
                <p id={promptIds.desc} className="text-sm text-muted-foreground">
                  AI 에이전트의 역할과 동작을 정의하는 시스템 프롬프트
                </p>
              </div>
            </CardContent>
          </Card>

          <div className="flex items-center gap-3">
            <Button onClick={handleSaveClick} disabled={isSaving || !(behaviorHasChanges || credDirty)}>
              <Save className="h-4 w-4" />
              {isSaving ? '저장 중...' : '저장'}
            </Button>
            {/* 되돌리기는 두 자원을 함께 되돌린다(Ruling #41, fix round 1) — 동작 설정 6키는
                `handleReset()`, 자격증명(유형·payload·비밀 입력)은 `cred.reset()`. 화면
                에는 버튼이 하나뿐이라, 절반만(동작 설정만) 되돌리면 사용자에게 그 구분이
                설명되지 않는다. */}
            <Button
              variant="outline"
              onClick={() => {
                handleReset();
                cred.reset();
              }}
              disabled={!(behaviorHasChanges || credDirty)}
            >
              <RotateCcw className="h-4 w-4" />
              되돌리기
            </Button>
          </div>
        </TabsContent>
        {/* AI 분류 탭(#707) — 상태는 페이지 소유. 미설정 요약은 채팅 탭의 현재 값을 보여준다. */}
        <TabsContent value="ai-classify" className="mt-6">
          <AiClassifySettingsTab
            state={classify}
            chatAgentType={cred.savedAgentType}
            chatConfigured={cred.configured}
            chatModel={settings['ai.model']?.value ?? form['ai.model']}
            chatHosting={cred.savedHosting}
            canDeclareSelfHosted={canSecurity}
          />
        </TabsContent>
        {/* 이메일 탭 — 폼 상태는 페이지가 소유한다(탭 전환에도 편집이 살아남는다) */}
        <TabsContent value="email" className="mt-6">
          <SmtpSettingsTab state={smtp} />
        </TabsContent>
        {/* 임베딩 탭 — 폼 상태는 페이지가 소유한다(탭 전환에도 편집이 살아남고 이탈 가드에 dirty 를
            보고한다, #713 리뷰 fix round 1) */}
        <TabsContent value="embedding" className="mt-6">
          <EmbeddingSettingsTab state={embedding} canDeclareSelfHosted={canSecurity} />
        </TabsContent>
        {/* 데이터 보안 탭 — 자체 쿼리를 가지며 페이지 폼 상태와 무관 */}
        {canSecurity && (
          <TabsContent value="security" className="mt-6">
            <Suspense fallback={<Skeleton className="h-64 w-full" />}>
              <SecuritySettingsTab />
            </Suspense>
          </TabsContent>
        )}
      </Tabs>

      {/* 저장 확인 다이얼로그 — 유형 전환(이전 비밀 폐기)처럼 되돌릴 수 없는 결과가 예정돼
          있을 때만 뜬다(`handleSaveClick`). 그 외에는 저장이 즉시 진행된다. */}
      <AlertDialog
        open={saveConfirmOpen}
        onOpenChange={setSaveConfirmOpen}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>자격증명 유형을 바꿀까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {typeChangeConfirmDescription(cred.savedAgentType, cred.agentType)}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setSaveConfirmOpen(false);
                void performSave();
              }}
            >
              저장
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 미저장 변경사항 이탈 가드 다이얼로그 (이슈 #86) */}
      {unsavedDialog}
    </div>
  );
}

/**
 * 설정 라우트 진입점 — ADMIN 은 전체 설정(탭별 권한 노출), 그 외(security:settings 보유자)는 보안 전용 화면.
 * 훅 규칙상 조건부 조기 반환을 한 컴포넌트 안에서 할 수 없어 래퍼로 분리했다.
 */
export default function SettingsPage() {
  const { isAdmin } = useAuth();
  return isAdmin ? <AdminSettingsPage /> : <SecurityOnlySettingsPage />;
}
