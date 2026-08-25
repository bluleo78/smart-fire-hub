import { Bot, Boxes, Mail, RotateCcw, Save, Settings, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../../api/settings';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
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
import { useSettingsOverrideForm } from '../../hooks/useSettingsOverrideForm';
import { useSmtpSettingsForm } from '../../hooks/useSmtpSettingsForm';
import {
  useDirtyAggregator,
  useUnsavedChangesGuard,
} from '../../hooks/useUnsavedChangesGuard';
import { BUILTIN_AI_DEFAULTS } from '../../lib/settings-fields';
import EmbeddingSettingsTab from './EmbeddingSettingsTab';
import {
  ClearOverrideButton,
  PlatformLockedNote,
  SettingFieldLabel,
  SettingStateBadge,
} from './settings-lock';
import SmtpSettingsTab from './SmtpSettingsTab';

const AGENT_TYPE_OPTIONS = [
  { value: 'sdk', label: 'Claude Agent SDK' },
  { value: 'cli', label: 'Claude Code CLI' },
  { value: 'cli-api', label: 'Claude API' },
  // OpenCode: 별도 키 없이 배포 환경 인증 사용
  { value: 'opencode', label: 'OpenCode' },
];

const MODEL_OPTIONS = [
  { value: 'claude-sonnet-5', label: 'Claude Sonnet 5' },
  { value: 'claude-opus-4-8', label: 'Claude Opus 4.8' },
  { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' },
];

// 인덱스 시그니처(`[key: string]: string`)를 <b>명시하지 않는다</b> — 명시하면 keyof 가
// string|number 로 넓어져 키를 설정 키 문자열로 다루는 곳마다 타입이 무너진다. 폼 키는 이 9개로
// 닫혀 있다.
//
// `interface` 가 아니라 `type` 인 이유: `useSettingsOverrideForm<F extends SettingsFormShape>` 의
// 제약(`Record<string, string>`)은 <b>암묵적</b> 인덱스 시그니처로 만족되는데, TS 는 그것을
// 타입 별칭에만 준다(interface 는 선언 병합으로 나중에 넓어질 수 있어 주지 않는다).
// keyof 는 그대로 9개 리터럴이므로 위 문단의 성질은 유지된다.
type AISettingsForm = {
  'ai.api_key': string;
  'ai.cli_oauth_token': string;
  'ai.agent_type': string;
  'ai.model': string;
  'ai.max_turns': string;
  'ai.system_prompt': string;
  'ai.temperature': string;
  'ai.max_tokens': string;
  'ai.session_max_tokens': string;
};

// 조회 전 초기값은 전부 빈 문자열이다. 조회 후에는 "서버 값 → 코드 기본값(BUILTIN_AI_DEFAULTS)
// → 빈 문자열" 순으로 채운다. 코드 기본값까지 보여주는 이유는 그 값이 실제로 적용되고 있기
// 때문이고, 그 사실은 "내장 기본값" 배지가 함께 알린다.
const EMPTY_VALUES: AISettingsForm = {
  'ai.api_key': '',
  'ai.cli_oauth_token': '',
  'ai.agent_type': '',
  'ai.model': '',
  'ai.max_turns': '',
  'ai.system_prompt': '',
  'ai.temperature': '',
  'ai.max_tokens': '',
  'ai.session_max_tokens': '',
};

/**
 * 훅에 넘기는 시드 폴백 한 벌. 예전 코드의 `byKey[key]?.value ?? BUILTIN_AI_DEFAULTS[key] ?? ''`
 * <b>두 단 폴백을 미리 합쳐</b> 훅의 한 단(`?? defaults[key]`)으로 만든다.
 *
 * 모듈 레벨 상수여야 한다 — 훅 계약이 그렇게 요구한다(인라인 객체는 렌더마다 새 참조).
 */
const AI_DEFAULTS: AISettingsForm = {
  ...EMPTY_VALUES,
  'ai.session_max_tokens': BUILTIN_AI_DEFAULTS['ai.session_max_tokens'] ?? '',
};

// 필드의 화면 표시 이름 — 저장이 거부된 필드를 이름으로 지목하는 데 쓴다.
// "어떤 필드가 문제인지" 말해주지 않으면 사용자가 무엇을 고쳐야 할지 알 수 없다.
//
// 편집 허용 6키뿐 아니라 폼의 9키 전부를 담는다. 저장 대상 판정이 web 상수가 아니라 서버
// 플래그(fieldState)로 바뀌었으므로, 서버가 지금 잠겨 있는 키를 열어 주면 그 키도 이 목록에
// 나타날 수 있다 — 6키만 담아 두면 그때 이름 대신 undefined 가 사용자에게 보인다.
// 숫자 필드 검증 규칙. 하한·상한은 백엔드 SettingsService.validateValues 와 반드시 같아야 한다 —
// 어긋나면 "운영자가 저장한 값 때문에 테넌트가 아무 필드도 저장 못 하는" 상태가 만들어진다
// (session_max_tokens 가 실제로 그랬다: backend 1000 vs web 10000).
const NUMBER_RULES: {
  key: keyof AISettingsForm;
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

const FIELD_LABELS: Record<keyof AISettingsForm, string> = {
  'ai.system_prompt': '시스템 프롬프트',
  'ai.model': '모델',
  'ai.temperature': 'Temperature',
  'ai.max_turns': '최대 턴 수',
  'ai.max_tokens': '최대 응답 토큰',
  'ai.session_max_tokens': '세션 최대 토큰',
  'ai.agent_type': '에이전트 유형',
  'ai.api_key': 'API 키',
  'ai.cli_oauth_token': 'OAuth 토큰',
};

export default function SettingsPage() {
  const [isSaving, setIsSaving] = useState(false);
  const [authStatus, setAuthStatus] = useState<{ valid: boolean; email?: string; subscriptionType?: string } | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);

  /**
   * AI 탭의 폼 상태 기계 — 이메일 탭과 <b>같은 훅</b>을 쓴다. 조회 실패 시 이 탭은 종단 화면을
   * 만들지 않고 toast 만으로 알린 뒤 폴백 값으로 렌더한다(비대칭은 의도된 것이다: 이 탭에는
   * 서버가 마스킹해 내려주는 자격증명 필드가 없어 "빈 폼"이 덮어쓰기를 부르지 않는다).
   */
  const {
    isLoading,
    settings,
    form,
    errors,
    setErrors,
    isClearing,
    fieldState,
    isEditable,
    hasChanges,
    updateField,
    handleReset,
    handleClearOverride,
    buildChangedPayload,
    commitSaved,
    refreshMeta,
  } = useSettingsOverrideForm<AISettingsForm>({ prefix: 'ai', defaults: AI_DEFAULTS });

  const verifyAuth = useCallback(async () => {
    setIsVerifying(true);
    try {
      const { data } = await settingsApi.verifyAuthStatus();
      setAuthStatus(data);
    } catch {
      setAuthStatus(null);
    } finally {
      setIsVerifying(false);
    }
  }, []);

  // isBlankAllowed 는 제거했다. DB 행도 코드 기본값도 없는 'no-default' 상태에서만 참이 되는데,
  // AI 8키는 V15/V69 에서 전부 non-null 로 시드돼 있고 유일하게 시드가 없는
  // ai.session_max_tokens 는 BUILTIN_AI_DEFAULTS 가 덮어 'builtin-default' 가 된다.
  // 즉 편집 가능 키 중 어느 것도 그 상태에 도달하지 못해, 다섯 개의 검증 분기가 전부
  // "항상 참인 가드" 안에 들어 있었다 — 읽는 사람이 그 가드가 살아 있는 경우를 지키는지
  // 판단할 수 없다. (순수 함수 쪽 5상태는 단위 테스트가 지키므로 그대로 둔다.)

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof AISettingsForm, string>> = {};

    // 잠긴 3키(ai.agent_type / ai.api_key / ai.cli_oauth_token)는 검증하지 않는다.
    // 저장 대상이 아니고 테넌트가 고칠 수도 없으므로, 여기서 검증하면 "고칠 수 없는 오류" 때문에
    // temperature 같은 편집 가능 필드의 저장까지 영구히 막힌다(플랫폼이 sdk + api_key 미설정인
    // 상태가 실제로 존재한다).
    // 숫자 4필드는 검증 모양이 같아 표로 한 번만 돈다. 네 벌로 복사돼 있던 시절에는 하한 하나가
    // 백엔드와 어긋난 것(session_max_tokens 1000 vs 10000)을 아무도 못 봤다 — 같은 규칙이 네 곳에
    // 흩어져 있으면 한 곳만 틀려도 눈에 띄지 않는다.
    NUMBER_RULES.forEach(({ key, min, max, integer, message }) => {
      const raw = form[key];
      const n = Number(raw);
      if (
        raw.trim() === '' ||
        isNaN(n) ||
        n < min ||
        n > max ||
        (integer && !Number.isInteger(n))
      ) {
        newErrors[key] = message;
      }
    });

    if (!form['ai.system_prompt'].trim()) {
      newErrors['ai.system_prompt'] = '시스템 프롬프트를 입력하세요';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSave = async () => {
    if (!validate()) {
      // 검증 실패 시 사용자에게 toast로 알림 (#69)
      toast.error('입력값을 확인하세요.');
      return;
    }

    // 페이로드 조립(잠긴 키 제외·미편집 키 제외·빈 값 거부 목록)은 훅이 한다 — 이 규칙이
    // 이메일 탭과 갈라지면 한쪽만 고치는 사고가 난다.
    //
    // 왜 validate() 와 별도인가: validate() 는 "입력값이 규칙에 맞는가"를 보고, 아래 검사는
    // "만든 페이로드가 사용자가 방금 한 편집을 실제로 담고 있는가"를 본다. 검증 규칙이 나중에
    // 완화되어도 이 대조는 계속 성립해야 하므로 페이로드를 만든 뒤 한 번 더 확인한다.
    const { payload, droppedChangedKeys } = buildChangedPayload();
    if (droppedChangedKeys.length > 0) {
      const names = droppedChangedKeys.map((key) => FIELD_LABELS[key]).join(', ');
      toast.error(
        `${names}을(를) 비워 둔 채로는 저장할 수 없습니다. 플랫폼 기본값으로 되돌리려면 "재정의 해제"를 사용하세요.`,
      );
      return;
    }

    setIsSaving(true);
    try {
      await settingsApi.update({ settings: payload });
      commitSaved();
      toast.success('설정이 저장되었습니다.');
      // 저장한 키는 이제 테넌트 재정의 상태이므로 배지를 다시 읽어 맞춘다.
      refreshMeta().catch(() => undefined);
      verifyAuth();
    } catch {
      toast.error('설정 저장에 실패했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  // 재정의 중인 필드에만 해제 버튼을 붙인다 — 상속 중인 필드에는 지울 오버라이드가 없다.
  const clearAction = (key: keyof AISettingsForm) =>
    fieldState(key) === 'overridden' ? (
      <ClearOverrideButton onConfirm={() => handleClearOverride(key)} disabled={isClearing} />
    ) : undefined;

  /**
   * 이메일 탭의 폼 상태도 <b>페이지가 소유한다</b>. Radix `TabsContent` 가 비활성 탭을
   * 언마운트하므로 탭이 상태를 갖고 있으면 탭 전환이 미저장 편집을 죽인다 — AI 탭은 여기서
   * 살아남는데 이메일 탭만 죽는 비대칭이었고, 고쳐야 할 것은 계약을 어긴 쪽이다(#390-2b).
   */
  const smtp = useSmtpSettingsForm();

  // 탭별 dirty 상태를 합산해 페이지 전체 dirty 여부를 결정한다 (이슈 #86).
  // 두 탭 모두 상태를 이 페이지가 소유하므로 보고도 페이지가 직접 한다 — 언마운트 클린업으로
  // dirty 를 false 로 되돌리던 보정은 사라졌다. 살아 있는 편집을 dirty 아님으로 보고하면
  // 이탈 가드가 침묵해 결함이 유실에서 경고 누락으로 모습만 바뀐다.
  // 임베딩 탭은 여전히 전면 잠금이라 dirty 가 될 수 없어 보고자가 없다.
  const { isAnyDirty, makeReporter } = useDirtyAggregator();
  const aiReporter = makeReporter('ai');
  const smtpReporter = makeReporter('smtp');
  const smtpHasChanges = smtp.base.hasChanges;
  useEffect(() => {
    aiReporter(hasChanges);
  }, [aiReporter, hasChanges]);
  useEffect(() => {
    smtpReporter(smtpHasChanges);
  }, [smtpReporter, smtpHasChanges]);
  const { dialog: unsavedDialog } = useUnsavedChangesGuard(isAnyDirty);

  if (isLoading) {
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

      <Tabs defaultValue="ai">
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
          <TabsTrigger value="email">
            <Mail className="h-4 w-4" />
            이메일
          </TabsTrigger>
          <TabsTrigger value="embedding">
            <Boxes className="h-4 w-4" />
            임베딩
          </TabsTrigger>
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
        <TabsContent value="ai" className="mt-6 space-y-6">
          <Card className="card-hover">
            <CardHeader>
              <CardTitle>모델 설정</CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              {/* 에이전트 유형 — 실행 형태·과금 주체라 플랫폼 소유(잠금) */}
              <div className="space-y-2">
                <SettingFieldLabel htmlFor="ai-agent-type" state={fieldState('ai.agent_type')}>
                  에이전트 유형
                </SettingFieldLabel>
                <Select value={form['ai.agent_type']} disabled={!isEditable('ai.agent_type')}>
                  <SelectTrigger id="ai-agent-type" className="w-full max-w-md">
                    <SelectValue placeholder="에이전트 유형을 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {AGENT_TYPE_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-sm text-muted-foreground">AI 채팅에 사용할 에이전트 유형</p>
                <PlatformLockedNote />
              </div>

              <Separator />

              {/* OpenCode / CLI OAuth 토큰 / API 키 — 에이전트 유형에 따라 분기
                  sdk는 OAuth 토큰과 API 키를 모두 지원(백엔드에서 OAuth 우선 적용)하므로
                  두 필드를 동시에 노출한다. */}
              {form['ai.agent_type'] === 'opencode' ? (
                // OpenCode: 배포 환경 인증(opencode auth) 사용 — 별도 키 입력 불필요
                <div className="rounded-md border border-border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">
                  배포 환경에 구성된 OpenCode 인증(opencode auth)을 사용합니다. 별도 키 입력이 필요 없습니다.
                </div>
              ) : (
                <div className="space-y-4">
                  {/* cli 또는 sdk: OAuth 토큰 필드 (sdk는 OAuth 우선) */}
                  {(form['ai.agent_type'] === 'cli' || form['ai.agent_type'] === 'sdk') && (
                    <div className="space-y-2">
                      <SettingFieldLabel
                        htmlFor="ai-cli-oauth-token"
                        state={fieldState('ai.cli_oauth_token')}
                      >
                        OAuth 토큰
                      </SettingFieldLabel>
                      <div className="flex gap-2 max-w-md">
                        {/* 값은 서버에서 **** 로 마스킹되어 내려오고 편집도 불가하므로 표시/숨기기
                            토글을 두지 않는다 — 눌러도 보여줄 평문이 없다. */}
                        <Input
                          id="ai-cli-oauth-token"
                          type="password"
                          className="flex-1"
                          value={form['ai.cli_oauth_token']}
                          disabled={!isEditable('ai.cli_oauth_token')}
                          placeholder="sk-ant-oat01-..."
                        />
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={verifyAuth}
                          disabled={isVerifying || hasChanges}
                          className="shrink-0"
                        >
                          <ShieldCheck className="h-3.5 w-3.5" />
                          {isVerifying ? '검증 중...' : '인증 확인'}
                        </Button>
                      </div>
                      <p className="text-sm text-muted-foreground">
                        로컬에서 claude setup-token으로 발급받은 OAuth 토큰
                        {authStatus && (
                          <span className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-success' : 'text-destructive'}`}>
                            {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
                            {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
                            {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
                          </span>
                        )}
                      </p>
                      <PlatformLockedNote />
                    </div>
                  )}
                  {/* cli-api 또는 sdk: API 키 필드 */}
                  {(form['ai.agent_type'] === 'cli-api' || form['ai.agent_type'] === 'sdk') && (
                    <div className="space-y-2">
                      <SettingFieldLabel htmlFor="ai-api-key" state={fieldState('ai.api_key')}>
                        API 키
                      </SettingFieldLabel>
                      <div className="flex gap-2 max-w-md">
                        {/* OAuth 토큰과 같은 이유로 표시/숨기기 토글 없음 (마스킹 + 편집 불가) */}
                        <Input
                          id="ai-api-key"
                          type="password"
                          className="flex-1"
                          value={form['ai.api_key']}
                          disabled={!isEditable('ai.api_key')}
                          placeholder="sk-ant-..."
                        />
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={verifyAuth}
                          disabled={isVerifying || hasChanges}
                          className="shrink-0"
                        >
                          <ShieldCheck className="h-3.5 w-3.5" />
                          {isVerifying ? '검증 중...' : '인증 확인'}
                        </Button>
                      </div>
                      <p className="text-sm text-muted-foreground">
                        Anthropic API 키 (sk-ant-...)
                        {authStatus && (
                          <span className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-success' : 'text-destructive'}`}>
                            {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
                            {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
                            {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
                          </span>
                        )}
                      </p>
                      <PlatformLockedNote />
                    </div>
                  )}
                </div>
              )}

              <Separator />

              {/* 모델 선택 */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-model"
                  state={fieldState('ai.model')}
                  action={clearAction('ai.model')}
                >
                  모델
                </SettingFieldLabel>
                <Select
                  value={form['ai.model']}
                  onValueChange={(value) => updateField('ai.model', value)}
                  disabled={!isEditable('ai.model')}
                >
                  <SelectTrigger id="ai-model" className="w-full max-w-md">
                    <SelectValue placeholder="모델을 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {MODEL_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-sm text-muted-foreground">AI 에이전트가 사용할 Claude 모델</p>
              </div>

              <Separator />

              {/* Max Turns */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-max-turns"
                  state={fieldState('ai.max_turns')}
                  action={clearAction('ai.max_turns')}
                >
                  최대 턴 수
                </SettingFieldLabel>
                <Input
                  id="ai-max-turns"
                  type="number"
                  min={1}
                  max={50}
                  className="w-full max-w-md"
                  value={form['ai.max_turns']}
                  disabled={!isEditable('ai.max_turns')}
                  onChange={(e) => updateField('ai.max_turns', e.target.value)}
                />
                {errors['ai.max_turns'] && (
                  <p className="text-sm text-destructive">{errors['ai.max_turns']}</p>
                )}
                <p className="text-sm text-muted-foreground">에이전트가 도구를 사용할 수 있는 최대 반복 횟수 (1~50)</p>
              </div>

              <Separator />

              {/* Temperature */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-temperature"
                  state={fieldState('ai.temperature')}
                  action={clearAction('ai.temperature')}
                >
                  Temperature
                </SettingFieldLabel>
                <Input
                  id="ai-temperature"
                  type="number"
                  min={0}
                  max={1}
                  step={0.1}
                  className="w-full max-w-md"
                  value={form['ai.temperature']}
                  disabled={!isEditable('ai.temperature')}
                  onChange={(e) => updateField('ai.temperature', e.target.value)}
                />
                {errors['ai.temperature'] && (
                  <p className="text-sm text-destructive">{errors['ai.temperature']}</p>
                )}
                <p className="text-sm text-muted-foreground">응답의 창의성 수준 (0.0: 결정적, 1.0: 창의적)</p>
              </div>

              <Separator />

              {/* Max Tokens */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-max-tokens"
                  state={fieldState('ai.max_tokens')}
                  action={clearAction('ai.max_tokens')}
                >
                  최대 응답 토큰
                </SettingFieldLabel>
                <Input
                  id="ai-max-tokens"
                  type="number"
                  min={1}
                  max={65536}
                  className="w-full max-w-md"
                  value={form['ai.max_tokens']}
                  disabled={!isEditable('ai.max_tokens')}
                  onChange={(e) => updateField('ai.max_tokens', e.target.value)}
                />
                {errors['ai.max_tokens'] && (
                  <p className="text-sm text-destructive">{errors['ai.max_tokens']}</p>
                )}
                <p className="text-sm text-muted-foreground">AI 응답의 최대 토큰 수 (1~65536)</p>
              </div>

              <Separator />

              {/* Session Max Tokens */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-session-max-tokens"
                  state={fieldState('ai.session_max_tokens')}
                  action={clearAction('ai.session_max_tokens')}
                >
                  세션 최대 토큰
                </SettingFieldLabel>
                <Input
                  id="ai-session-max-tokens"
                  type="number"
                  min={10000}
                  max={200000}
                  step={10000}
                  className="w-full max-w-md"
                  value={form['ai.session_max_tokens']}
                  disabled={!isEditable('ai.session_max_tokens')}
                  onChange={(e) => updateField('ai.session_max_tokens', e.target.value)}
                />
                {errors['ai.session_max_tokens'] && (
                  <p className="text-sm text-destructive">{errors['ai.session_max_tokens']}</p>
                )}
                {/* 이 키는 플랫폼 시드 행이 없어 서버 description 이 null 일 수 있다 — 그때는
                    허용 범위를 담은 기존 문구로 폴백한다(스펙 §5). */}
                <p className="text-sm text-muted-foreground">
                  {settings['ai.session_max_tokens']?.description ??
                    '세션의 입력 토큰이 이 값을 초과하면 대화를 자동 요약하고 새 세션으로 전환합니다 (10,000~200,000)'}
                </p>
              </div>
            </CardContent>
          </Card>

          <Card className="card-hover">
            <CardHeader>
              {/* 이 카드에는 별도 Label 이 없으므로(제목이 곧 필드 이름) 배지와 재정의 해제 버튼을
                  제목 줄에 붙인다. */}
              <CardTitle className="flex flex-wrap items-center gap-2">
                <span>시스템 프롬프트</span>
                <SettingStateBadge state={fieldState('ai.system_prompt')} />
                {clearAction('ai.system_prompt')}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Textarea
                  id="ai-system-prompt"
                  rows={15}
                  className="font-mono text-sm"
                  value={form['ai.system_prompt']}
                  disabled={!isEditable('ai.system_prompt')}
                  onChange={(e) => updateField('ai.system_prompt', e.target.value)}
                  placeholder="시스템 프롬프트를 입력하세요..."
                />
                {errors['ai.system_prompt'] && (
                  <p className="text-sm text-destructive">{errors['ai.system_prompt']}</p>
                )}
                <p className="text-sm text-muted-foreground">AI 에이전트의 역할과 동작을 정의하는 시스템 프롬프트</p>
              </div>
            </CardContent>
          </Card>

          <div className="flex items-center gap-3">
            <Button onClick={handleSave} disabled={isSaving || !hasChanges}>
              <Save className="h-4 w-4" />
              {isSaving ? '저장 중...' : '저장'}
            </Button>
            <Button variant="outline" onClick={handleReset} disabled={!hasChanges}>
              <RotateCcw className="h-4 w-4" />
              되돌리기
            </Button>
          </div>
        </TabsContent>
        {/* 이메일 탭 — 폼 상태는 페이지가 소유한다(탭 전환에도 편집이 살아남는다) */}
        <TabsContent value="email" className="mt-6">
          <SmtpSettingsTab state={smtp} />
        </TabsContent>
        {/* 임베딩 탭 — 전면 잠금이라 dirty 보고자가 없다 */}
        <TabsContent value="embedding" className="mt-6">
          <EmbeddingSettingsTab />
        </TabsContent>
      </Tabs>

      {/* 미저장 변경사항 이탈 가드 다이얼로그 (이슈 #86) */}
      {unsavedDialog}
    </div>
  );
}
