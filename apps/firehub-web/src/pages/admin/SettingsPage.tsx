import { Bot, Boxes, Mail, RotateCcw, Save, Settings, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
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
  AlertDialogTrigger,
} from '../../components/ui/alert-dialog';
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
import {
  useDirtyAggregator,
  useUnsavedChangesGuard,
} from '../../hooks/useUnsavedChangesGuard';
import {
  indexSettingsByKey,
  isTenantEditableAiKey,
  resolveSettingFieldState,
  TENANT_EDITABLE_AI_KEYS,
} from '../../lib/settings-fields';
import type { ResolvedSettingResponse } from '../../types/settings';
import EmbeddingSettingsTab from './EmbeddingSettingsTab';
import { PlatformLockedNote, SettingFieldLabel, SettingStateBadge } from './settings-lock';
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

// 인덱스 시그니처(`[key: string]: string`)를 두지 않는다 — keyof 가 string|number 로 넓어져
// 키를 설정 키 문자열로 다루는 곳마다 타입이 무너진다. 폼 키는 이 9개로 닫혀 있다.
interface AISettingsForm {
  'ai.api_key': string;
  'ai.cli_oauth_token': string;
  'ai.agent_type': string;
  'ai.model': string;
  'ai.max_turns': string;
  'ai.system_prompt': string;
  'ai.temperature': string;
  'ai.max_tokens': string;
  'ai.session_max_tokens': string;
}

// 폼 초기값은 전부 빈 문자열이다. 예전에는 여기에 하드코딩 기본값(예: session_max_tokens '50000')을
// 넣었는데, 그러면 서버에 그 키의 행이 아예 없을 때도 입력창에 50000이 보여서 "기본값 없음" 배지와
// 화면이 서로 모순된다(스펙 §5). 값은 서버 응답만이 채운다.
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

// 값이 없는 편집 가능 필드에 힌트로만 노출하는 예시값 — 폼 상태에는 절대 들어가지 않는다.
// (예전 하드코딩 기본값을 placeholder 로 격하시킨 것)
const PLACEHOLDERS: Record<string, string> = {
  'ai.max_turns': '10',
  'ai.temperature': '1.0',
  'ai.max_tokens': '16384',
  'ai.session_max_tokens': '50000',
};

/**
 * 재정의 해제 버튼 + 확인 다이얼로그.
 *
 * - 배치 저장(PUT)에 얹지 않고 즉시 DELETE 를 호출한다 — 오버라이드 삭제는 "빈 문자열 저장"과
 *   다른 연산이라 PUT payload 로 표현할 수 없다.
 * - `DeleteConfirmDialog` 래퍼를 쓰지 않는 이유: 고정 문구가 "되돌릴 수 없습니다"인데 재정의
 *   해제는 언제든 다시 재정의할 수 있어 사실과 어긋난다. 그래서 원본 프리미티브로 문구를 짠다.
 * - 확인을 받는 이유: system_prompt 처럼 공들여 입력한 긴 텍스트가 즉시 사라질 수 있다.
 */
function ClearOverrideButton({
  settingKey,
  onConfirm,
  disabled,
}: {
  settingKey: string;
  onConfirm: (key: string) => void;
  disabled?: boolean;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button type="button" variant="ghost" size="sm" disabled={disabled}>
          <RotateCcw className="h-3.5 w-3.5" />
          재정의 해제
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>재정의 해제</AlertDialogTitle>
          <AlertDialogDescription>
            이 항목의 테넌트 설정이 삭제되고 플랫폼 기본값으로 즉시 전환됩니다. 지금 입력된 값은
            사라지며, 필요하면 언제든 다시 재정의할 수 있습니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          {/* destructive 색을 쓰지 않는다 — 되돌릴 수 있는 동작이다 */}
          <AlertDialogAction onClick={() => onConfirm(settingKey)}>되돌리기</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export default function SettingsPage() {
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [form, setForm] = useState<AISettingsForm>(EMPTY_VALUES);
  const [original, setOriginal] = useState<AISettingsForm>(EMPTY_VALUES);
  // 서버 응답을 키로 인덱싱해 보관한다 — 배지 상태(overridden/tenantEditable)와 description 폴백의 근거.
  const [settings, setSettings] = useState<Record<string, ResolvedSettingResponse>>({});
  const [errors, setErrors] = useState<Partial<Record<keyof AISettingsForm, string>>>({});
  const [isClearing, setIsClearing] = useState(false);
  const [authStatus, setAuthStatus] = useState<{ valid: boolean; email?: string; subscriptionType?: string } | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);

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

  const fetchSettings = useCallback(async () => {
    setIsLoading(true);
    try {
      const { data } = await settingsApi.getByPrefix('ai');
      const byKey = indexSettingsByKey(data);
      // 응답에 없는 키·값이 null 인 키는 빈 문자열로 둔다. "null"/"undefined" 문자열이 입력창에
      // 렌더되는 일을 원천 차단하고, 값이 없다는 사실을 배지("기본값 없음")가 대신 말한다.
      const values = { ...EMPTY_VALUES };
      (Object.keys(values) as (keyof AISettingsForm)[]).forEach((key) => {
        values[key] = byKey[key]?.value ?? '';
      });
      setSettings(byKey);
      setForm(values);
      setOriginal(values);
    } catch {
      toast.error('설정을 불러오는데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  // 배지 상태(overridden)만 다시 읽는다 — 폼 값은 건드리지 않으므로 입력 중인 내용이 사라지지 않는다.
  const refreshMeta = useCallback(() => {
    settingsApi
      .getByPrefix('ai')
      .then(({ data }) => setSettings(indexSettingsByKey(data)))
      // 배지 갱신 실패는 저장 결과를 뒤집지 않는다 — 다음 진입 때 다시 읽힌다.
      .catch(() => undefined);
  }, []);

  // 필드 상태 판정 — 배지·disabled·검증·저장 대상이 모두 이 한 곳을 거쳐 서로 어긋나지 않게 한다.
  const fieldState = (key: keyof AISettingsForm) => resolveSettingFieldState(key, settings[key]);

  // 입력 가능 여부 = 서버가 편집 가능이라고 말했고(플래그가 권위) + 저장 페이로드에 실제로 담기는
  // 키다. 두 조건을 모두 요구하는 이유는 양방향 어긋남을 둘 다 막기 위해서다:
  //  - 서버가 잠금이라 했는데 입력이 열려 있으면 "배지는 잠금인데 타이핑은 된다"가 된다.
  //  - 서버가 열어줬어도 페이로드에 담지 않는 키면 "입력은 되는데 저장이 무시된다"가 된다.
  const isEditable = (key: keyof AISettingsForm) =>
    fieldState(key) !== 'locked' && isTenantEditableAiKey(key);

  // 값이 비어 있어도 오류로 보지 않는 필드: 플랫폼 기본값 행 자체가 없는 키(예: ai.session_max_tokens).
  // 비어 있는 상태가 곧 "재정의 없음"이라 정상이고, 저장 페이로드에서도 제외된다.
  const isBlankAllowed = (key: keyof AISettingsForm) =>
    form[key].trim() === '' && fieldState(key) === 'no-default';

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof AISettingsForm, string>> = {};

    // 잠긴 3키(ai.agent_type / ai.api_key / ai.cli_oauth_token)는 검증하지 않는다.
    // 저장 대상이 아니고 테넌트가 고칠 수도 없으므로, 여기서 검증하면 "고칠 수 없는 오류" 때문에
    // temperature 같은 편집 가능 필드의 저장까지 영구히 막힌다(플랫폼이 sdk + api_key 미설정인
    // 상태가 실제로 존재한다).
    if (!isBlankAllowed('ai.max_turns')) {
      const maxTurns = Number(form['ai.max_turns']);
      if (form['ai.max_turns'].trim() === '' || isNaN(maxTurns) || maxTurns < 1 || maxTurns > 50 || !Number.isInteger(maxTurns)) {
        newErrors['ai.max_turns'] = '1~50 사이의 정수를 입력하세요';
      }
    }

    if (!isBlankAllowed('ai.temperature')) {
      const temperature = Number(form['ai.temperature']);
      if (form['ai.temperature'].trim() === '' || isNaN(temperature) || temperature < 0 || temperature > 1) {
        newErrors['ai.temperature'] = '0.0~1.0 사이의 값을 입력하세요';
      }
    }

    if (!isBlankAllowed('ai.max_tokens')) {
      const maxTokens = Number(form['ai.max_tokens']);
      if (form['ai.max_tokens'].trim() === '' || isNaN(maxTokens) || maxTokens < 1 || maxTokens > 65536 || !Number.isInteger(maxTokens)) {
        newErrors['ai.max_tokens'] = '1~65536 사이의 정수를 입력하세요';
      }
    }

    if (!isBlankAllowed('ai.session_max_tokens')) {
      const sessionMaxTokens = Number(form['ai.session_max_tokens']);
      if (form['ai.session_max_tokens'].trim() === '' || isNaN(sessionMaxTokens) || sessionMaxTokens < 10000 || sessionMaxTokens > 200000 || !Number.isInteger(sessionMaxTokens)) {
        newErrors['ai.session_max_tokens'] = '10,000~200,000 사이의 정수를 입력하세요';
      }
    }

    if (!isBlankAllowed('ai.system_prompt') && !form['ai.system_prompt'].trim()) {
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

    setIsSaving(true);
    try {
      // 페이로드는 테넌트 편집 허용 6키만 담는다. 잠긴 키를 보내면 서버가 키 이름을 명시해 400 을
      // 던지므로 "저장은 되는데 서버가 거부"가 아니라 "거부될 필드는 시도조차 하지 않는다"로 만든다.
      // 값이 빈 키도 제외한다 — 빈 문자열 오버라이드 행은 "재정의 없음"과 다른 상태이고,
      // 상속으로 되돌리는 조작은 재정의 해제(DELETE)가 담당한다.
      const settingsToSave: Record<string, string> = {};
      TENANT_EDITABLE_AI_KEYS.forEach((key) => {
        if (form[key].trim() !== '') settingsToSave[key] = form[key];
      });
      await settingsApi.update({ settings: settingsToSave });
      setOriginal({ ...form });
      toast.success('설정이 저장되었습니다.');
      // 저장한 키는 이제 테넌트 재정의 상태이므로 배지를 다시 읽어 맞춘다.
      refreshMeta();
      verifyAuth();
    } catch {
      toast.error('설정 저장에 실패했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  /**
   * 재정의 해제 — DELETE 후 해당 키 하나만 서버 값으로 되돌린다.
   * 전체 폼을 다시 시드하지 않는 이유: 다른 필드에 입력 중이던 미저장 변경을 조용히 날려버린다.
   */
  const handleClearOverride = async (key: string) => {
    const formKey = key as keyof AISettingsForm;
    setIsClearing(true);
    try {
      await settingsApi.clearOverride(key);
      const { data } = await settingsApi.getByPrefix('ai');
      const byKey = indexSettingsByKey(data);
      setSettings(byKey);
      const restored = byKey[key]?.value ?? '';
      setForm((prev) => ({ ...prev, [formKey]: restored }));
      setOriginal((prev) => ({ ...prev, [formKey]: restored }));
      setErrors((prev) => {
        const next = { ...prev };
        delete next[formKey];
        return next;
      });
      toast.success('플랫폼 기본값으로 되돌렸습니다.');
    } catch {
      toast.error('재정의 해제에 실패했습니다.');
    } finally {
      setIsClearing(false);
    }
  };

  // 재정의 중인 필드에만 해제 버튼을 붙인다 — 상속 중인 필드에는 지울 오버라이드가 없다.
  const clearAction = (key: keyof AISettingsForm) =>
    fieldState(key) === 'overridden' ? (
      <ClearOverrideButton settingKey={key} onConfirm={handleClearOverride} disabled={isClearing} />
    ) : undefined;

  const handleReset = () => {
    setForm({ ...original });
    setErrors({});
  };

  // dirty 판정도 편집 가능 6키만 본다 — 잠긴 필드는 바뀔 수 없지만, 판정 근거를 한 집합으로 통일한다.
  const hasChanges = TENANT_EDITABLE_AI_KEYS.some((key) => form[key] !== original[key]);

  // 탭별 dirty 상태를 합산해 페이지 전체 dirty 여부를 결정한다 (이슈 #86).
  // P7-b 이후 이메일·임베딩 탭은 편집 자체가 불가능해 dirty 가 될 수 없으므로 보고자가 AI 탭뿐이다.
  // 합산기 구조는 유지한다 — 장래에 편집 가능한 탭이 추가되면 그대로 다시 붙는다.
  const { isAnyDirty, makeReporter } = useDirtyAggregator();
  const aiReporter = makeReporter('ai');
  useEffect(() => {
    aiReporter(hasChanges);
  }, [aiReporter, hasChanges]);
  const { dialog: unsavedDialog } = useUnsavedChangesGuard(isAnyDirty);

  const updateField = (key: keyof AISettingsForm, value: string) => {
    setForm(prev => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors(prev => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  };

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
                  placeholder={PLACEHOLDERS['ai.max_turns']}
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
                  placeholder={PLACEHOLDERS['ai.temperature']}
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
                  placeholder={PLACEHOLDERS['ai.max_tokens']}
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
                  placeholder={PLACEHOLDERS['ai.session_max_tokens']}
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
        {/* 이메일 탭 — 플랫폼 전용(전 필드 읽기 전용)이라 dirty 보고자가 없다 */}
        <TabsContent value="email" className="mt-6">
          <SmtpSettingsTab />
        </TabsContent>
        {/* 임베딩 탭 — 위와 동일 */}
        <TabsContent value="embedding" className="mt-6">
          <EmbeddingSettingsTab />
        </TabsContent>
      </Tabs>

      {/* 미저장 변경사항 이탈 가드 다이얼로그 (이슈 #86) */}
      {unsavedDialog}
    </div>
  );
}
