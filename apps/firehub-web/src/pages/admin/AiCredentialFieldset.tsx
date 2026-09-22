import { AlertTriangle, ShieldCheck } from 'lucide-react';
import type { ReactNode } from 'react';
import { useState } from 'react';

import { Button } from '../../components/ui/button';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import type { UseAiCredentialFormResult } from '../../hooks/useAiCredentialForm';
import type { AgentType } from '../../lib/ai-credential';
import { AGENT_TYPES } from '../../lib/ai-credential';
import {
  AGENT_TYPE_LABELS,
  PROVIDER_ID_CANDIDATES,
  REASONING_EFFORT_CANDIDATES,
  REASONING_EFFORT_DEFAULT_SENTINEL,
  stripProviderPrefix,
  withPreservedValue,
  withProviderPrefix,
} from '../../lib/ai-credential-screen';

/**
 * AI 자격증명(`ai.credential`) 전용 화면 — 설계서 "화면 · 테넌트 화면" 절의 구현.
 *
 * <b>자격증명은 테넌트 전용이다(#706).</b> 이 화면은 항상 우리 조직의 자격증명 입력 폼을 보여주고,
 * 아직 저장된 자격증명이 없으면(`configured:false`) "설정해야 AI 기능을 쓸 수 있다"고 안내한다.
 *
 * <b>모델(`ai.model`)은 여기 없다.</b> 스펙 §48 "ai.model 은 평면 키로 남는다" — 자격증명 문서에
 * 속하지 않는 별도 키라 `GET /settings/ai-credential` 응답에도 없다. 모델 칸(+opencode 4상태)은
 * `SettingsPage.tsx` 가 `behavior` 폼과 함께 이 fieldset **바깥**에 그린다(레이아웃은 "유형별
 * 필드 → 모델 → 동작 설정"으로 이어지되, 모델은 별도 자원이다).
 *
 * <b>순수 로직은 `lib/ai-credential-screen.ts` 에 산다.</b> `react-refresh/only-export-components`
 * 가 컴포넌트 파일에는 컴포넌트만 export 하기를 요구하므로, `typeChangeConfirmDescription` 같은
 * 렌더 없이 테스트 가능한 결정 로직은 그쪽에 있다.
 */

/**
 * opencode 전용 <b>모델 칸 4상태</b>(설계서 §195 "모델 칸 4상태") — 미로드 / 목록 / 목록 없음(자유
 * 입력) / 실패(오류 + "직접 입력으로 전환"). `[모델 불러오기]` 는 이 칸 옆에 둔다(§197).
 *
 * `ai.model` 은 자격증명 문서가 아니라 `behavior` 폼의 값이므로, 이 컴포넌트는 `cred`(모델 목록
 * 로딩 상태)와 `modelValue`/`onModelChange`(behavior 폼)를 함께 받는다 — 두 자원을 한 칸에서
 * 조합하는 지점이 여기 하나뿐이도록 캡슐화한다.
 */
export function OpencodeModelField({
  cred,
  modelValue,
  onModelChange,
}: {
  cred: UseAiCredentialFormResult;
  modelValue: string;
  onModelChange: (value: string) => void;
}) {
  const providerId = cred.payload.providerId ?? '';
  const bareModel = stripProviderPrefix(modelValue, providerId);
  const [manualOverride, setManualOverride] = useState(false);

  /**
   * 기본 URL·유형이 바뀌면 훅이 이미 목록을 무효화한다(미로드로 복귀, §195) — 이 로컬 탈출
   * 상태도 함께 되돌리지 않으면 "URL 을 고쳤는데 화면은 여전히 자유 입력"이라는 낡은 모양이
   * 남는다.
   *
   * <b>`useEffect` 가 아니라 렌더 중 `setState` 로 되돌린다</b>(React 공식 문서의 "prop 변경에
   * 맞춰 state 조정" 패턴) — effect 안에서 `setManualOverride(false)` 를 부르면
   * `react-hooks/set-state-in-effect` 가 경고하는 cascading render 가 생긴다. "이전 scope 값"을
   * 함께 상태로 들고 있다가 달라졌을 때만, <b>같은 렌더 사이클 안에서</b> 리셋한다.
   */
  const scopeKey = `${cred.payload.baseURL ?? ''}\u0000${cred.agentType}`;
  const [prevScopeKey, setPrevScopeKey] = useState(scopeKey);
  if (scopeKey !== prevScopeKey) {
    setPrevScopeKey(scopeKey);
    if (manualOverride) setManualOverride(false);
  }

  const handleFreeInputChange = (raw: string) => onModelChange(withProviderPrefix(raw, providerId));

  const handleLoadModels = () => {
    setManualOverride(false);
    void cred.loadModels();
  };

  let field: ReactNode;
  let hint: ReactNode = null;

  if (manualOverride) {
    field = (
      <Input
        id="ai-model"
        value={bareModel}
        onChange={(e) => handleFreeInputChange(e.target.value)}
        placeholder="예: gpt-4o"
      />
    );
    hint = <p className="text-sm text-muted-foreground">직접 입력한 모델을 사용합니다.</p>;
  } else if (cred.modelsError) {
    // 실패 — 미로드와 같은 모양이 되지 않도록 오류 문구 + 전환 버튼을 반드시 함께 보여준다.
    // value 는 빈 문자열이 아니라 저장된 값(bareModel)이다(Minor #8, fix round 1) — 로딩에
    // 실패했다고 해서 이미 저장돼 있던 모델까지 화면에서 사라진 것처럼 보이면 안 된다.
    field = <Input id="ai-model" value={bareModel} disabled placeholder="먼저 모델을 불러오세요" />;
    hint = (
      <div className="space-y-1.5">
        <p className="text-sm text-destructive">{cred.modelsError}</p>
        <Button type="button" variant="outline" size="sm" onClick={() => setManualOverride(true)}>
          직접 입력으로 전환
        </Button>
      </div>
    );
  } else if (cred.models === null) {
    // 미로드도 실패와 같은 이유로 bareModel 을 보여준다(Minor #8) — "아직 안 눌렀을 뿐"이지
    // 값이 없어진 게 아니다.
    field = <Input id="ai-model" value={bareModel} disabled placeholder="먼저 모델을 불러오세요" />;
  } else if (cred.models.length === 0) {
    field = (
      <Input
        id="ai-model"
        value={bareModel}
        onChange={(e) => handleFreeInputChange(e.target.value)}
        placeholder="예: gpt-4o"
      />
    );
    hint = <p className="text-sm text-muted-foreground">공급자가 모델 목록을 주지 않아 직접 입력합니다.</p>;
  } else {
    const options = withPreservedValue(
      cred.models.map((m) => ({ value: m, label: m })),
      bareModel,
    );
    field = (
      <Select
        value={bareModel === '' ? undefined : bareModel}
        onValueChange={(v) => onModelChange(withProviderPrefix(v, providerId))}
      >
        <SelectTrigger id="ai-model" className="w-full">
          <SelectValue placeholder="모델을 선택하세요" />
        </SelectTrigger>
        <SelectContent>
          {options.map((opt) => (
            <SelectItem key={opt.value} value={opt.value}>
              {opt.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    );
    hint = <p className="text-sm text-muted-foreground">✓ 모델 {cred.models.length}개</p>;
  }

  // "모델" Label(과 기본값 힌트)은 호출부(`SettingsPage.tsx`)가 이미 그린다 — `ai.model` 은 이
  // 컴포넌트가 모르는 별도 자원(동작 설정)이라서다. 여기서 또 그리면 "모델" 이 두 번 나온다.
  return (
    <div className="space-y-2">
      <div className="flex gap-2 max-w-md items-start">
        <div className="flex-1">{field}</div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={handleLoadModels}
          disabled={!cred.canLoadModels}
          className="shrink-0"
        >
          모델 불러오기
        </Button>
      </div>
      {!cred.canLoadModels && (
        <p className="text-sm text-muted-foreground">
          기본 URL과 API 키를 입력하면 모델을 불러올 수 있습니다.
        </p>
      )}
      {hint}
    </div>
  );
}

export interface AiCredentialFieldsetProps {
  cred: UseAiCredentialFormResult;
  authStatus: { valid: boolean; email?: string; subscriptionType?: string } | null;
  isVerifying: boolean;
  onVerifyAuth: () => void;
}

/** OAuth 토큰/API 키 입력 옆의 "✓ 인증됨" 배지 — sdk/cli/cli-api 세 유형에서만 그린다(opencode 는
 * Anthropic 인증 개념이 없어 이 버튼·배지 자체가 없다, 설계서 §184-189 표). */
function AuthBadge({ authStatus }: { authStatus: AiCredentialFieldsetProps['authStatus'] }) {
  if (!authStatus) return null;
  return (
    <span
      className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-success' : 'text-destructive'}`}
    >
      {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
      {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
      {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
    </span>
  );
}

/** 비밀 필드 입력 밑의 상태 힌트 — "사실"(설정됨)과 "할 일"(바꾸려면 입력)을 분리한다(설계서
 * §191). `cred.secretFieldNames` 는 훅이 이미 유형 불일치를 걸러 낸 값이다 — 여기서 다시 거르지
 * 않는다. */
function secretHint(fieldName: string, cred: UseAiCredentialFormResult) {
  if (!cred.secretFieldNames.includes(fieldName)) {
    return <p className="text-sm text-muted-foreground">설정된 값이 없습니다.</p>;
  }
  return (
    <p className="text-sm text-muted-foreground">
      현재 값이 설정되어 있습니다. 바꾸려면 새 값을 입력하세요 — 비워 두면 현재 값이 그대로 유지됩니다.
    </p>
  );
}

/** "자격증명" 범례를 단 fieldset 틀 — 실패·잠김·정상 세 상태가 같은 틀을 쓴다(간격만 다르다). */
function CredentialShell({ className, children }: { className: string; children: ReactNode }) {
  return (
    <fieldset className={`${className} rounded-md border p-4`}>
      <legend className="px-1 text-sm font-medium">자격증명</legend>
      {children}
    </fieldset>
  );
}

/**
 * AI 자격증명 fieldset 본체 — (미설정 안내) + 유형별 입력 폼.
 * `SettingsPage.tsx` 의 `모델 설정` 카드 안, `모델`/`동작 설정` 위에 놓인다.
 */
export function AiCredentialFieldset({
  cred,
  authStatus,
  isVerifying,
  onVerifyAuth,
}: AiCredentialFieldsetProps) {
  // 최초 조회(GET)가 실패하면 훅은 안전한 초기값(`configured:false, secretFieldNames:[]`)으로
  // 주저앉는다. 이 초기값을 평소 렌더 경로에 흘리면 "AI 설정이 없습니다"를 <b>사실</b>처럼
  // 보여주게 된다 — 실제로는 "몰라서" 못 그리는 것뿐인데 "없다"고 단정하는 것이다. 토스트는
  // 지나가 버리므로, 별도의 지속적인 실패 상태를 그려 화면이 거짓을 말하지 않게 막는다.
  if (cred.loadFailed) {
    return (
      <CredentialShell className="space-y-4">
        <p className="text-sm text-destructive" role="alert">
          자격증명 정보를 불러오지 못했습니다. 아래 내용이 실제 상태와 다를 수 있으니, 새로고침
          하거나 잠시 후 다시 시도하세요.
        </p>
      </CredentialShell>
    );
  }

  // 조회가 403 이면(권한 없음) 폼 값이 전부 초기값(모름)이다 — 위 실패 가드와 같은 이유로
  // 입력 폼도 미설정 안내도 그리지 않고, 볼 수 없다는 사실만 말한다.
  if (cred.isLocked) {
    return (
      <CredentialShell className="space-y-4">
        <p className="text-sm text-muted-foreground">AI 자격증명을 조회·변경할 권한이 없습니다.</p>
      </CredentialShell>
    );
  }

  return (
    <CredentialShell className="space-y-6">
      {/* 자격증명은 테넌트 전용이라(#706) 미설정은 곧 "AI 기능 중단"이다 — 입력 폼
          바로 위에 지속 안내로 알린다(서버가 실제로 configured:false 라고 답한 경우에만 여기 온다). */}
      {!cred.configured && (
        <InlineBanner variant="warning" icon={<AlertTriangle />}>
          AI 설정이 없습니다. 설정해야 AI 기능을 쓸 수 있습니다.
        </InlineBanner>
      )}

      <CredentialForm
        cred={cred}
        authStatus={authStatus}
        isVerifying={isVerifying}
        onVerifyAuth={onVerifyAuth}
      />
    </CredentialShell>
  );
}

/** 우리 조직 자격증명 입력 폼 — 유형(맨 앞) → 유형별 필드(설계서 §180 공통 레이아웃). */
function CredentialForm({
  cred,
  authStatus,
  isVerifying,
  onVerifyAuth,
}: {
  cred: UseAiCredentialFormResult;
  authStatus: AiCredentialFieldsetProps['authStatus'];
  isVerifying: boolean;
  onVerifyAuth: () => void;
}) {
  // 잠김(403)은 상위 `AiCredentialFieldset` 이 이미 걸렀으므로 여기 도달하면 항상 편집 가능하다 —
  // 그래서 입력칸들에 disabled 분기가 없다.
  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="ai-cred-agent-type">에이전트 유형</Label>
        <Select
          value={cred.agentType}
          onValueChange={(v) => cred.setAgentType(v as AgentType)}
        >
          <SelectTrigger id="ai-cred-agent-type" className="w-full max-w-md">
            <SelectValue placeholder="에이전트 유형을 선택하세요" />
          </SelectTrigger>
          <SelectContent>
            {AGENT_TYPES.map((t) => (
              <SelectItem key={t} value={t}>
                {AGENT_TYPE_LABELS[t]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p className="text-sm text-muted-foreground">AI 채팅에 사용할 에이전트 유형</p>
        {/* 유형 전환 경고(설계서 §193) — 저장된 유형과 다를 때만, Select 바로 아래 정적 안내로. */}
        {cred.typeChanged && (
          <p className="text-sm text-destructive">
            유형을 바꾸면 이전 유형({AGENT_TYPE_LABELS[cred.savedAgentType]})의 저장된 비밀이
            삭제됩니다. 복구할 수 없습니다.
          </p>
        )}
      </div>

      {cred.agentType === 'opencode' ? (
        <OpencodeFields cred={cred} />
      ) : (
        <ClaudeFields
          cred={cred}
          authStatus={authStatus}
          isVerifying={isVerifying}
          onVerifyAuth={onVerifyAuth}
        />
      )}
    </div>
  );
}

/** sdk/cli/cli-api 세 유형의 입력 — OAuth 토큰(cli·sdk)·API 키(cli-api·sdk), 유지되는 "인증
 * 확인" 버튼(설계서 §184-189, 경쟁 조건 가드는 페이지가 그대로 들고 있다). */
function ClaudeFields({
  cred,
  authStatus,
  isVerifying,
  onVerifyAuth,
}: {
  cred: UseAiCredentialFormResult;
  authStatus: AiCredentialFieldsetProps['authStatus'];
  isVerifying: boolean;
  onVerifyAuth: () => void;
}) {
  const showOauth = cred.agentType === 'cli' || cred.agentType === 'sdk';
  const showApiKey = cred.agentType === 'cli-api' || cred.agentType === 'sdk';
  // 타이핑 중인 값이 아직 저장되지 않았으면 "인증 확인"은 낡은(저장된) 값을 검증한다 —
  // 클릭 가능해도 사용자가 방금 친 값을 확인하는 게 아니라는 뜻이라 잠근다.
  const verifyDisabled = isVerifying || cred.hasUnsavedInput;

  return (
    <div className="space-y-4">
      {showOauth && (
        <div className="space-y-2">
          <Label htmlFor="ai-cred-oauth-token">OAuth 토큰</Label>
          <div className="flex gap-2 max-w-md">
            <Input
              id="ai-cred-oauth-token"
              type="password"
              className="flex-1"
              value={cred.secretInputs.oauthToken ?? ''}
              onChange={(e) => cred.setSecretInput('oauthToken', e.target.value)}
              placeholder="sk-ant-oat01-..."
              aria-describedby="ai-cred-oauth-token-desc ai-cred-oauth-token-hint"
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onVerifyAuth}
              disabled={verifyDisabled}
              className="shrink-0"
            >
              <ShieldCheck className="h-3.5 w-3.5" />
              {isVerifying ? '검증 중...' : '인증 확인'}
            </Button>
          </div>
          <p id="ai-cred-oauth-token-desc" className="text-sm text-muted-foreground">
            로컬에서 claude setup-token으로 발급받은 OAuth 토큰
            <AuthBadge authStatus={authStatus} />
          </p>
          <div id="ai-cred-oauth-token-hint">{secretHint('oauthToken', cred)}</div>
        </div>
      )}
      {showApiKey && (
        <div className="space-y-2">
          <Label htmlFor="ai-cred-api-key">API 키</Label>
          <div className="flex gap-2 max-w-md">
            <Input
              id="ai-cred-api-key"
              type="password"
              className="flex-1"
              value={cred.secretInputs.apiKey ?? ''}
              onChange={(e) => cred.setSecretInput('apiKey', e.target.value)}
              placeholder="sk-ant-..."
              aria-describedby="ai-cred-api-key-desc ai-cred-api-key-hint"
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onVerifyAuth}
              disabled={verifyDisabled}
              className="shrink-0"
            >
              <ShieldCheck className="h-3.5 w-3.5" />
              {isVerifying ? '검증 중...' : '인증 확인'}
            </Button>
          </div>
          <p id="ai-cred-api-key-desc" className="text-sm text-muted-foreground">
            Anthropic API 키 (sk-ant-...)
            <AuthBadge authStatus={authStatus} />
          </p>
          <div id="ai-cred-api-key-hint">{secretHint('apiKey', cred)}</div>
        </div>
      )}
    </div>
  );
}

/** opencode 전용 입력 — 공급자·기본 URL·API 키·추론 강도. 검증 수단은 "모델 불러오기"(모델
 * 칸은 `SettingsPage.tsx` 가 이 fieldset 밖에서 그린다, 이 파일 헤더 주석 참고). */
function OpencodeFields({ cred }: { cred: UseAiCredentialFormResult }) {
  const providerId = cred.payload.providerId ?? '';
  const providerOptions = withPreservedValue(PROVIDER_ID_CANDIDATES, providerId);
  const reasoningEffort = cred.payload.reasoningEffort ?? '';
  const reasoningOptions = withPreservedValue(REASONING_EFFORT_CANDIDATES, reasoningEffort);

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="ai-cred-provider">공급자</Label>
        <Select
          value={providerId === '' ? undefined : providerId}
          onValueChange={(v) => cred.setPayloadField('providerId', v)}
        >
          <SelectTrigger id="ai-cred-provider" className="w-full max-w-md">
            <SelectValue placeholder="공급자를 선택하세요" />
          </SelectTrigger>
          <SelectContent>
            {providerOptions.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="space-y-2">
        <Label htmlFor="ai-cred-base-url">기본 URL</Label>
        <Input
          id="ai-cred-base-url"
          className="max-w-md"
          value={cred.payload.baseURL ?? ''}
          onChange={(e) => cred.setPayloadField('baseURL', e.target.value)}
          placeholder="https://api.openai.com/v1"
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="ai-cred-opencode-api-key">API 키</Label>
        <Input
          id="ai-cred-opencode-api-key"
          type="password"
          className="max-w-md"
          value={cred.secretInputs.apiKey ?? ''}
          onChange={(e) => cred.setSecretInput('apiKey', e.target.value)}
          aria-describedby="ai-cred-opencode-api-key-hint"
        />
        <div id="ai-cred-opencode-api-key-hint">{secretHint('apiKey', cred)}</div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="ai-cred-reasoning-effort">추론 강도</Label>
        <Select
          value={reasoningEffort === '' ? REASONING_EFFORT_DEFAULT_SENTINEL : reasoningEffort}
          onValueChange={(v) =>
            cred.setPayloadField('reasoningEffort', v === REASONING_EFFORT_DEFAULT_SENTINEL ? '' : v)
          }
        >
          <SelectTrigger id="ai-cred-reasoning-effort" className="w-full max-w-md">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={REASONING_EFFORT_DEFAULT_SENTINEL}>기본값</SelectItem>
            {reasoningOptions.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p className="text-sm text-muted-foreground">
          공급자에게 그대로 전달되는 값입니다 — 지원 여부는 저장 시 서버가 판정합니다.
        </p>
      </div>
    </div>
  );
}
