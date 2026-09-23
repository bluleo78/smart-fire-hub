import { Bot, Boxes, Mail, RotateCcw, Save, Settings, Tags } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
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
import { useAiClassifyForm } from '../../hooks/useAiClassifyForm';
import { useAiCredentialForm } from '../../hooks/useAiCredentialForm';
import { useSettingsOverrideForm } from '../../hooks/useSettingsOverrideForm';
import { useSmtpSettingsForm } from '../../hooks/useSmtpSettingsForm';
import {
  useDirtyAggregator,
  useUnsavedChangesGuard,
} from '../../hooks/useUnsavedChangesGuard';
import { CLAUDE_MODEL_OPTIONS, typeChangeConfirmDescription } from '../../lib/ai-credential-screen';
import AiClassifySettingsTab from './AiClassifySettingsTab';
import { AiCredentialFieldset, OpencodeModelField } from './AiCredentialFieldset';
import EmbeddingSettingsTab from './EmbeddingSettingsTab';
import { SettingFieldLabel } from './settings-lock';
import SmtpSettingsTab from './SmtpSettingsTab';

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

export default function SettingsPage() {
  const [isSaving, setIsSaving] = useState(false);
  const [authStatus, setAuthStatus] = useState<{ valid: boolean; email?: string; subscriptionType?: string } | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);
  /** 동작 설정(6키) 저장 후 재조회가 실패했을 때의 지속 안내 — 옛 `useAiSettingsForm.staleNotice`
   * 를 대체한다. 자격증명 쪽의 같은 안내는 `cred.staleNotice`(별도 자원, 별도 슬롯)가 갖는다. */
  const [behaviorStaleNotice, setBehaviorStaleNotice] = useState<string | null>(null);
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
    commitSaved,
    refreshMeta,
  } = behavior;

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof AIBehaviorForm, string>> = {};

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
   * <b>부분 실패는 이 설계가 받아들인 대가다</b>(Ruling #10 이 설정 저장 전반의 트랜잭션 부재를
   * 이미 파킹했다) — 두 자원이 별도 엔드포인트인 이상 원자성은 없다. 동작 설정 저장이 실패해도
   * 자격증명 저장은 계속 시도한다(독립 자원이므로 한쪽 실패가 다른 쪽을 막을 이유가 없다). 이
   * 순서는 그 실패가 "덜 위험한 쪽"에서 먼저 나도록 고른 것이다 — 무인증 free-form 키(`ai.model`)
   * 쓰기가 먼저 끝나고, 검증이 있는(그래서 더 자주 실패할 수 있는) 자격증명 쓰기가 나중이다.
   *
   * <b>토스트 두 번은 의도적이다</b> — 두 자원은 독립적이고 사용자는 둘의 결과를 각각 알아야
   * 한다. `cred.save()` 는 훅 내부에서 스스로 성공/실패 토스트를 띄우고, 이제(fix round 1,
   * Ruling #42) 쓰기 성공 여부를 boolean 으로도 돌려준다 — 이 함수는 그 값으로 `verifyAuth()`
   * 호출을 성공했을 때만 하도록 조건을 건다(아래 참고).
   */
  const performSave = useCallback(async () => {
    setIsSaving(true);
    try {
      if (behaviorHasChanges) {
        const { payload, droppedChangedKeys } = buildChangedPayload();
        if (droppedChangedKeys.length > 0) {
          const names = droppedChangedKeys.map((key) => AI_FIELD_LABELS[key]).join(', ');
          toast.error(`${names}을(를) 비워 둔 채로는 저장할 수 없습니다.`);
        } else {
          try {
            await settingsApi.update({ settings: payload });
            commitSaved();
            toast.success('설정이 저장되었습니다.');
            refreshMeta().catch(() => {
              const message =
                '저장은 완료됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';
              setBehaviorStaleNotice(message);
              toast.error(message);
            });
          } catch {
            // 동작 설정 저장 실패 — 자격증명은 별도 자원이므로 아래에서 계속 시도한다(주석 참고).
            toast.error('설정 저장에 실패했습니다.');
          }
        }
      }

      if (cred.hasUnsavedInput) {
        // `cred.save()` 가 쓰기(PUT) 성공 여부를 boolean 으로 돌려준다(Ruling #42, fix
        // round 1) — 실패한 쓰기 뒤에 `verifyAuth()` 를 돌리면 여전히 낡은(그러나 여전히 유효할
        // 수 있는) 자격증명을 검사해 사용자를 혼란스럽게 한다. 성공했을 때만 부른다.
        const saved = await cred.save();
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
    } finally {
      setIsSaving(false);
    }
  }, [behaviorHasChanges, buildChangedPayload, commitSaved, refreshMeta, cred, verifyAuth]);

  /**
   * "저장" 클릭 — 파괴적 결과(유형 전환 = 이전 유형 비밀 폐기)가 예정돼 있으면 확인 다이얼로그를
   * 먼저 연다(설계서 §193 "유형 전환").
   */
  const handleSaveClick = () => {
    if (!validate()) {
      toast.error('입력값을 확인하세요.');
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

  // 탭별 dirty 상태를 합산해 페이지 전체 dirty 여부를 결정한다(이슈 #86). AI 탭은 이제 두 독립
  // 자원(동작 설정 6키 + 자격증명)을 갖고 있어 `cred.hasUnsavedInput` 도 함께 보고해야 한다 —
  // 빠뜨리면 자격증명만 입력하고 떠나는 이탈이 조용히 통과한다.
  const { isAnyDirty, makeReporter } = useDirtyAggregator();
  const aiReporter = makeReporter('ai');
  const smtpReporter = makeReporter('smtp');
  const smtpHasChanges = smtp.base.hasChanges;
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
  const { dialog: unsavedDialog } = useUnsavedChangesGuard(isAnyDirty);

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
              {/* "지금 화면을 믿지 말고 다시 읽어라" 안내 — 두 자원 각자의 슬롯을 따로 그린다.
                  뭉뚱그리면 동작 설정 저장 실패가 자격증명 문제로 잘못 읽힌다(그 반대도 마찬가지). */}
              {behaviorStaleNotice && <InlineBanner variant="warning">{behaviorStaleNotice}</InlineBanner>}
              {cred.staleNotice && <InlineBanner variant="warning">{cred.staleNotice}</InlineBanner>}

              <AiCredentialFieldset
                cred={cred}
                authStatus={authStatus}
                isVerifying={isVerifying}
                onVerifyAuth={verifyAuth}
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
                        {CLAUDE_MODEL_OPTIONS.map((opt) => (
                          <SelectItem key={opt.value} value={opt.value}>
                            {opt.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <p className="text-sm text-muted-foreground">AI 에이전트가 사용할 Claude 모델</p>
                  </>
                )}
              </div>

              <Separator />

              {/* Max Turns */}
              <div className="space-y-2">
                <SettingFieldLabel
                  htmlFor="ai-max-turns"
                  badge={<DefaultHint show={isDefault('ai.max_turns')} />}
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
                  badge={<DefaultHint show={isDefault('ai.temperature')} />}
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
                  badge={<DefaultHint show={isDefault('ai.max_tokens')} />}
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
                  badge={<DefaultHint show={isDefault('ai.session_max_tokens')} />}
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
                  onChange={(e) => updateField('ai.session_max_tokens', e.target.value)}
                />
                {errors['ai.session_max_tokens'] && (
                  <p className="text-sm text-destructive">{errors['ai.session_max_tokens']}</p>
                )}
                <p className="text-sm text-muted-foreground">
                  세션의 입력 토큰이 이 값을 초과하면 대화를 자동 요약하고 새 세션으로 전환합니다 (10,000~200,000)
                </p>
              </div>
            </CardContent>
          </Card>

          <Card className="card-hover">
            <CardHeader>
              {/* 이 카드에는 별도 Label 이 없으므로(제목이 곧 필드 이름) 기본값 힌트를 제목 줄에 붙인다. */}
              <CardTitle className="flex flex-wrap items-center gap-2">
                <span>시스템 프롬프트</span>
                <DefaultHint show={isDefault('ai.system_prompt')} />
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Textarea
                  id="ai-system-prompt"
                  rows={15}
                  className="font-mono text-sm"
                  value={form['ai.system_prompt']}
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
          />
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
