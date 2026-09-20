import { ShieldCheck } from 'lucide-react';
import type { ReactNode } from 'react';
import { useState } from 'react';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { PasswordInput } from '@/components/ui/password-input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { AiCredentialSectionState } from '@/hooks/useAiCredentialSection';
import { useAuth } from '@/hooks/useAuth';
import type { AgentType } from '@/lib/ai-credential';
import {
  AGENT_TYPE_LABELS,
  AGENT_TYPES,
  CLAUDE_MODEL_CANDIDATES,
  PROVIDER_ID_CANDIDATES,
  REASONING_EFFORT_CANDIDATES,
  REASONING_EFFORT_DEFAULT_SENTINEL,
  withPreservedValue,
} from '@/lib/ai-credential';

/**
 * 플랫폼 AI 자격증명(`ai.credential`) + 모델(`ai.model`) 전용 섹션 — 설계서 "화면 · 플랫폼 화면"
 * 절의 구현.
 *
 * <b>테넌트 화면(`apps/firehub-web/src/pages/admin/AiCredentialFieldset.tsx` + Task 11 의 다른
 * 조각들)의 의도적인 복제·단순화본이다.</b> 레이아웃·필드·유형 분기는 같지만, 라디오("플랫폼
 * 설정을 사용" / "우리 조직이 직접 설정")가 **없다** — 플랫폼에는 상위 평면이 없어 "가져다
 * 쓸까"라는 선택지 자체가 성립하지 않는다(설계서 §223 "플랫폼 화면 — 플랫폼 값만"). 그 결과:
 *
 * - `plane`/`tenantOwned`/DELETE 가 없다. 플랫폼 응답(`PlatformAiCredentialResponse`)에도
 *   `tenantOwned` 필드 자체가 없다(Task 7 `PlatformAiCredentialController` — "항상 false" 는
 *   플랫폼이 그 값을 소유하므로 거짓이다).
 * - 배지(`테넌트 재정의 가능`/`전역 고정`)와 안내문("직접 설정한 조직에는 적용되지 않습니다")을
 *   렌더하지 않는다(브리프 Step 3, 설계서 §225).
 *
 * <b>이 컴포넌트는 프레젠테이션 전용이다(Task 13 fix round 1, Ruling #50).</b> 폼 상태
 * (`useAiCredentialSection`)는 이 컴포넌트가 아니라 `SettingsPage.tsx` 가 `Tabs` 바깥에서
 * 한 번만 불러 `cred` prop 으로 내려준다 — 그 이유는 훅 파일(`hooks/useAiCredentialSection.ts`)
 * 헤더 주석에 있다: Radix 가 비활성 `TabsContent` 를 언마운트해서 생기는, "탭을 옮겼다 오면
 * 타이핑 중이던 비밀·모델이 사라진다"는 결함을 막기 위해서다.
 *
 * <b>"인증 확인" 버튼이 있다(Ruling #47, fix round 1).</b> `GET /api/platform/ai/auth-status`
 * (`PlatformAiController`, `AiCredentialService.resolve()` 를 재사용하는 얇은 거울)를 불러
 * sdk/cli/cli-api 세 유형에서 "✓ 인증됨"/"✗ 유효하지 않음" 배지를 보여준다. opencode 는
 * Anthropic 인증 개념이 없어 이 버튼·배지 자체가 없다(테넌트 화면과 같은 규칙).
 *
 * <b>모델(`ai.model`)이 있다(Ruling #48, fix round 1).</b> 예전엔 `SettingsPage.tsx` 의 범용
 * 렌더러가 3개 Claude 모델로 고정된 Select 하나로 그렸는데, opencode 를 고른 관리자가
 * `providerId/modelId` 형식을 지정할 방법이 없어 "저장은 되는데 쓸 수 없는" 자격증명이
 * 만들어졌다 — 지금은 유형을 아는 이 컴포넌트가 유형에 따라 Select(Claude 세 모델)/opencode
 * 4상태(아래 `OpencodeModelField`)를 가른다. 저장 순서(모델 먼저, 자격증명 다음)와 그 이유는
 * 훅의 `doSave` 주석 참고.
 *
 * <b>opencode 모델 칸은 테넌트 화면의 4상태를 그대로 옮겼다(Ruling #51, fix round 2).</b> 예전
 * 라운드에선 자유 입력 하나뿐이었다 — 리뷰가 정리한 근거: 스펙의 공통 절이 두 앱을 함께
 * 규율하고, 플랫폼 문단이 지우는 건 라디오뿐이다. 테넌트 화면이 플랫폼 평면에서 자유 입력만
 * 쓰는 건 "테넌트가 플랫폼의 키를 프로브할 수 없기" 때문인데, 이 화면은 그 자격증명을 직접
 * 소유하고 프로브 엔드포인트도 있어 그 비대칭이 성립하지 않는다 — 그래서 미로드/실패/목록
 * 없음(자유 입력 폴백)/목록(Select) 네 상태를 그대로 포팅한다. "모델 불러오기" 버튼도 예전
 * `OpencodeFields`(연결 확인 전용)에서 이 칸으로 옮겨, 실제로 모델을 고르는 액션 옆에 둔다.
 *
 * <b>접두어 조립은 컴포넌트가 하지 않는다(Ruling #52, fix round 2).</b> 예전엔 모델 입력칸의
 * `onChange` 가 **타이핑하는 그 순간의** `providerId` 로 `providerId/modelId` 접두어를 즉시
 * 다시 붙였다 — opencode 를 고르고 모델을 먼저 타이핑한 뒤 공급자를 나중에 고르면 접두어가
 * 다시 붙지 않아, `PUT /settings` 가 `{"ai.model":"gpt-4o"}` 를 보냈다(`openai/gpt-4o` 가
 * 아니라). 서버는 슬래시 없는 값을 "아직 대조 불가"로 통과시켜(Ruling #28) 저장은 성공하고
 * 실제 채팅 시점에야 깨졌다. 고침: `useAiCredentialSection` 의 `model` state 는 이제 항상
 * 맨 모델 id 이고, 접두어는 `doSave` 안에서 저장 직전에만 조립한다 — 이 컴포넌트의
 * `onChange`/`onValueChange` 는 `cred.setModel(raw)` 를 그대로 부를 뿐 접두어 계산을 전혀
 * 하지 않는다. 입력 순서가 결과에 영향을 줄 여지 자체가 없어진다.
 *
 * <b>두 저장 버튼이 무엇을 저장하는지 라벨로 말한다(Ruling #49, fix round 1).</b> 이 섹션의
 * 버튼은 "AI 자격증명·모델 반영"/"AI 자격증명·모델 초기화" — 이 fieldset 이 다루는 두 자원
 * (`ai.credential` + `ai.model`)을 함께 저장한다는 뜻이다. 페이지 하단의 다른 버튼
 * (`SettingsPage.tsx`)은 "나머지 설정 저장"/"나머지 설정 되돌리기" — 이 AI 탭의 나머지 5키뿐
 * 아니라 이메일·임베딩 탭 전부를 함께 저장한다(탭을 넘나드는 범위라 "AI 탭 나머지"라고만
 * 쓰면 거짓이 된다, 리포트에 이 명명 그대로 남긴다). 이것은 통합 리팩터가 아니다 — 두 버튼은
 * 여전히 별도 엔드포인트, 별도 저장 흐름이다. 라벨만 무엇을 저장하는지 말하게 고쳤다.
 */

type CredentialState = AiCredentialSectionState;

/** 비밀 필드 입력 밑의 상태 힌트 — 사실절과 행동절을 분리한다(설계서 공통 절, 테넌트 화면
 * `secretHint` 와 같은 문구). 값 대신 "현재 값이 설정되어 있습니다"만 보여주고 입력칸은 비운다
 * — 마스크 문자열은 어떤 경로로도 입력칸에 들어가지 않는다. */
function secretHint(fieldName: string, cred: CredentialState, editable: boolean) {
  if (!cred.secretFieldNames.includes(fieldName)) {
    return <p className="text-sm text-muted-foreground">설정된 값이 없습니다.</p>;
  }
  return (
    <p className="text-sm text-muted-foreground">
      현재 값이 설정되어 있습니다.
      {editable && ' 바꾸려면 새 값을 입력하세요 — 비워 두면 현재 값이 그대로 유지됩니다.'}
    </p>
  );
}

/** OAuth 토큰/API 키 입력 옆의 "✓ 인증됨" 배지(Ruling #47) — 테넌트 화면 `AuthBadge` 와 같은
 * 모양이다. sdk/cli/cli-api 세 유형에서만 그린다(opencode 는 이 버튼·배지 자체가 없다). */
function AuthBadge({ authStatus }: { authStatus: CredentialState['authStatus'] }) {
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

/** sdk/cli/cli-api 세 유형의 입력 — OAuth 토큰(cli·sdk)·API 키(cli-api·sdk), "인증 확인" 버튼
 * (Ruling #47). 타이핑 중인 값이 아직 저장되지 않았으면 낡은(저장된) 값을 검증하게 되므로
 * 잠근다(테넌트 화면과 같은 가드). */
function ClaudeFields({ cred, editable }: { cred: CredentialState; editable: boolean }) {
  const showOauth = cred.agentType === 'cli' || cred.agentType === 'sdk';
  const showApiKey = cred.agentType === 'cli-api' || cred.agentType === 'sdk';
  // fix round 2 — `hasUnsavedInput` 은 모델 편집도 포함한다(Ruling #52 의 `effectiveModel`
  // 비교). Claude 모델만 바꾸고 자격증명은 그대로인 상태에서 "인증 확인"이 잠기는 건, 이
  // 가드의 원래 목적("타이핑 중인 낡은 자격증명을 검증하지 않는다")과 무관한 이유로 잠기는
  // 것이었다 — 자격증명만 보는 `hasUnsavedCredentialInput` 을 쓴다.
  const verifyDisabled = cred.isVerifying || cred.hasUnsavedCredentialInput;

  return (
    <div className="space-y-4">
      {showOauth && (
        <div className="space-y-2">
          <Label htmlFor="ai-cred-oauth-token">OAuth 토큰</Label>
          <div className="flex gap-2 max-w-md">
            <PasswordInput
              id="ai-cred-oauth-token"
              className="flex-1"
              autoComplete="new-password"
              value={cred.secretInputs.oauthToken ?? ''}
              disabled={!editable}
              onChange={(e) => cred.setSecretInput('oauthToken', e.target.value)}
              placeholder="sk-ant-oat01-..."
              aria-describedby="ai-cred-oauth-token-hint"
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => void cred.verifyAuth()}
              disabled={verifyDisabled}
              className="shrink-0"
            >
              <ShieldCheck className="h-3.5 w-3.5" />
              {cred.isVerifying ? '검증 중...' : '인증 확인'}
            </Button>
          </div>
          <div id="ai-cred-oauth-token-hint">
            {secretHint('oauthToken', cred, editable)}
            <AuthBadge authStatus={cred.authStatus} />
          </div>
        </div>
      )}
      {/* fix round 2 — sdk 는 showOauth·showApiKey 가 둘 다 true 라, 예전엔 이 아래 블록에도
          같은 `AuthBadge` 를 또 그려 배지가 두 번(OAuth 칸·API 키 칸) 나타났다. 배지 하나는
          "지금 적용 중인 자격증명" 하나를 말하지, 필드 두 개를 각각 말하는 게 아니다 — sdk 에서
          OAuth 칸이 이미 그렸다면 API 키 칸에서는 생략한다. */}
      {showApiKey && (
        <div className="space-y-2">
          <Label htmlFor="ai-cred-api-key">API 키</Label>
          <div className="flex gap-2 max-w-md">
            <PasswordInput
              id="ai-cred-api-key"
              className="flex-1"
              autoComplete="new-password"
              value={cred.secretInputs.apiKey ?? ''}
              disabled={!editable}
              onChange={(e) => cred.setSecretInput('apiKey', e.target.value)}
              placeholder="sk-ant-..."
              aria-describedby="ai-cred-api-key-hint"
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => void cred.verifyAuth()}
              disabled={verifyDisabled}
              className="shrink-0"
            >
              <ShieldCheck className="h-3.5 w-3.5" />
              {cred.isVerifying ? '검증 중...' : '인증 확인'}
            </Button>
          </div>
          <div id="ai-cred-api-key-hint">
            {secretHint('apiKey', cred, editable)}
            {!showOauth && <AuthBadge authStatus={cred.authStatus} />}
          </div>
        </div>
      )}
    </div>
  );
}

/** opencode 전용 입력 — 공급자·기본 URL·API 키·추론 강도. 모델 지정·"모델 불러오기"는 아래
 * `OpencodeModelField` 가 따로 다룬다(Ruling #48, Ruling #51 — 예전엔 이 컴포넌트가 "모델
 * 불러오기"를 연결 확인 전용으로 들고 있었는데, 실제로 모델을 고르는 액션 옆으로 옮겼다). */
function OpencodeFields({ cred, editable }: { cred: CredentialState; editable: boolean }) {
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
          disabled={!editable}
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
          disabled={!editable}
          onChange={(e) => cred.setPayloadField('baseURL', e.target.value)}
          placeholder="https://api.openai.com/v1"
        />
      </div>

      <div className="space-y-2">
        <Label htmlFor="ai-cred-opencode-api-key">API 키</Label>
        <PasswordInput
          id="ai-cred-opencode-api-key"
          className="max-w-md"
          autoComplete="new-password"
          value={cred.secretInputs.apiKey ?? ''}
          disabled={!editable}
          onChange={(e) => cred.setSecretInput('apiKey', e.target.value)}
          aria-describedby="ai-cred-opencode-api-key-hint"
        />
        <div id="ai-cred-opencode-api-key-hint">{secretHint('apiKey', cred, editable)}</div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="ai-cred-reasoning-effort">추론 강도</Label>
        <Select
          value={reasoningEffort === '' ? REASONING_EFFORT_DEFAULT_SENTINEL : reasoningEffort}
          onValueChange={(v) =>
            cred.setPayloadField('reasoningEffort', v === REASONING_EFFORT_DEFAULT_SENTINEL ? '' : v)
          }
          disabled={!editable}
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

/**
 * opencode 전용 모델 칸 4상태(Ruling #51, fix round 2) — 테넌트 화면
 * `AiCredentialFieldset.tsx` 의 `OpencodeModelField` 를 이 화면(플랫폼)에 맞게 포팅했다.
 * 미로드 / 실패(+ "직접 입력으로 전환") / 목록 없음(자유 입력 폴백) / 목록(Select) 네 상태를
 * 그대로 두되, 테넌트 버전에 있던 "플랫폼 평면" 갈래는 여기엔 없다 — 이 화면 자체가 이미
 * 플랫폼 평면이라 그 구분이 성립하지 않는다(파일 헤더 주석 참고).
 *
 * <b>`cred.model` 은 이미 맨 모델 id 다.</b> 훅(`useAiCredentialSection`)이 "model state 는
 * 항상 접두어 없는 형태"라는 불변식을 지키므로(Ruling #52), 여기서는 `stripProviderPrefix`/
 * `withProviderPrefix` 를 전혀 부르지 않는다 — `onChange`/`onValueChange` 는 `cred.setModel(raw)`
 * 를 그대로 넘긴다.
 */
function OpencodeModelField({ cred, editable }: { cred: CredentialState; editable: boolean }) {
  const [manualOverride, setManualOverride] = useState(false);

  // 기본 URL·유형이 바뀌면 훅이 이미 모델 목록을 무효화한다(미로드로 복귀) — 이 로컬 탈출
  // 상태도 함께 되돌리지 않으면 "URL 을 고쳤는데 화면은 여전히 자유 입력"이라는 낡은 모양이
  // 남는다. `useEffect` 가 아니라 렌더 중 `setState` 로 되돌린다(React 공식 문서의 "prop
  // 변경에 맞춰 state 조정" 패턴, 테넌트 화면과 같은 이유·같은 코드).
  const scopeKey = `${cred.payload.baseURL ?? ''}\u0000${cred.agentType}`;
  const [prevScopeKey, setPrevScopeKey] = useState(scopeKey);
  if (scopeKey !== prevScopeKey) {
    setPrevScopeKey(scopeKey);
    if (manualOverride) setManualOverride(false);
  }

  const handleLoadModels = () => {
    setManualOverride(false);
    void cred.loadModels();
  };

  let field: ReactNode;
  let hint: ReactNode = null;

  if (manualOverride) {
    field = (
      <Input
        id="ai-cred-model"
        value={cred.model}
        disabled={!editable}
        onChange={(e) => cred.setModel(e.target.value)}
        placeholder="예: gpt-4o"
      />
    );
    hint = <p className="text-sm text-muted-foreground">직접 입력한 모델을 사용합니다.</p>;
  } else if (cred.modelsError) {
    // 실패 — 미로드와 같은 모양이 되지 않도록 오류 문구 + 전환 버튼을 반드시 함께 보여준다.
    // value 는 빈 문자열이 아니라 저장된 값(cred.model)이다 — 로딩에 실패했다고 해서 이미
    // 저장돼 있던 모델까지 화면에서 사라진 것처럼 보이면 안 된다(테넌트 화면 Minor #8 과 같은
    // 이유).
    field = (
      <Input id="ai-cred-model" value={cred.model} disabled placeholder="먼저 모델을 불러오세요" />
    );
    hint = (
      <div className="space-y-1.5">
        <p className="text-sm text-destructive">{cred.modelsError}</p>
        <Button type="button" variant="outline" size="sm" onClick={() => setManualOverride(true)}>
          직접 입력으로 전환
        </Button>
      </div>
    );
  } else if (cred.models === null) {
    // 미로드도 실패와 같은 이유로 cred.model 을 보여준다 — "아직 안 눌렀을 뿐"이지 값이
    // 없어진 게 아니다.
    field = (
      <Input id="ai-cred-model" value={cred.model} disabled placeholder="먼저 모델을 불러오세요" />
    );
  } else if (cred.models.length === 0) {
    field = (
      <Input
        id="ai-cred-model"
        value={cred.model}
        disabled={!editable}
        onChange={(e) => cred.setModel(e.target.value)}
        placeholder="예: gpt-4o"
      />
    );
    hint = (
      <p className="text-sm text-muted-foreground">공급자가 모델 목록을 주지 않아 직접 입력합니다.</p>
    );
  } else {
    const options = withPreservedValue(
      cred.models.map((m) => ({ value: m, label: m })),
      cred.model,
    );
    field = (
      <Select
        value={cred.model === '' ? undefined : cred.model}
        onValueChange={(v) => cred.setModel(v)}
        disabled={!editable}
      >
        <SelectTrigger id="ai-cred-model" className="w-full">
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

  return (
    <div className="space-y-2">
      <Label htmlFor="ai-cred-model">모델</Label>
      <div className="flex gap-2 max-w-md items-start">
        <div className="flex-1">{field}</div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={handleLoadModels}
          disabled={!cred.canLoadModels || !editable}
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
      {cred.modelDescription && (
        <p className="text-sm text-muted-foreground">{cred.modelDescription}</p>
      )}
    </div>
  );
}

/** `ai.model` 필드(Ruling #48) — Claude 세 유형은 고정 Select, opencode 는 위
 * `OpencodeModelField` 의 4상태(Ruling #51). 서버 `description` 이 있으면 그대로 보여준다
 * (예전 `SettingField` 가 하던 것과 같다, e2e "서버 description 을 그대로 쓴다" 고정 문구
 * 하나를 이 화면에서도 지킨다). */
function ModelField({ cred, editable }: { cred: CredentialState; editable: boolean }) {
  if (cred.agentType === 'opencode') {
    return <OpencodeModelField cred={cred} editable={editable} />;
  }
  return (
    <div className="space-y-2">
      <Label htmlFor="ai-cred-model">모델</Label>
      <Select
        value={cred.model === '' ? undefined : cred.model}
        onValueChange={(v) => cred.setModel(v)}
        disabled={!editable}
      >
        <SelectTrigger id="ai-cred-model" className="w-full max-w-md">
          <SelectValue placeholder="모델을 선택하세요" />
        </SelectTrigger>
        <SelectContent>
          {withPreservedValue(CLAUDE_MODEL_CANDIDATES, cred.model).map((opt) => (
            <SelectItem key={opt.value} value={opt.value}>
              {opt.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {cred.modelDescription && (
        <p className="text-sm text-muted-foreground">{cred.modelDescription}</p>
      )}
    </div>
  );
}

export function AiCredentialSection({ cred }: { cred: CredentialState }) {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('platform:settings:write');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  const handleSaveClick = () => {
    if (cred.typeChanged) {
      setConfirmOpen(true);
      return;
    }
    void runSave();
  };

  const runSave = async () => {
    setConfirmOpen(false);
    setIsSaving(true);
    try {
      await cred.doSave();
    } catch {
      // 토스트는 doSave 안에서 이미 띄웠다 — 여기서는 저장 중 상태만 정리한다.
    } finally {
      setIsSaving(false);
    }
  };

  if (cred.isLoading) {
    return (
      <fieldset className="space-y-4 rounded-md border p-4">
        <legend className="px-1 text-sm font-medium">자격증명</legend>
        <p className="text-sm text-muted-foreground">불러오는 중...</p>
      </fieldset>
    );
  }

  if (cred.loadFailed) {
    return (
      <fieldset className="space-y-4 rounded-md border p-4">
        <legend className="px-1 text-sm font-medium">자격증명</legend>
        <p className="text-sm text-destructive" role="alert">
          자격증명 정보를 불러오지 못했습니다. 아래 내용이 실제 상태와 다를 수 있으니, 새로고침
          하거나 잠시 후 다시 시도하세요.
        </p>
      </fieldset>
    );
  }

  return (
    <fieldset className="space-y-6 rounded-md border p-4">
      <legend className="px-1 text-sm font-medium">자격증명</legend>

      {/* 라디오 없음 — 플랫폼 화면은 "가져다 쓸까"라는 선택지가 없다(설계서 §223). 유형이
          맨 앞에 온다: 이 화면이 있게 된 이유(원래 화면에서 유형이 9개 중 8번째였던 문제)를
          바로 이 순서로 고친다. */}
      <div className="space-y-2">
        <Label htmlFor="ai-cred-agent-type">에이전트 유형</Label>
        <Select
          value={cred.agentType}
          onValueChange={(v) => cred.setAgentType(v as AgentType)}
          disabled={!canWrite}
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
        {cred.typeChanged && (
          <p className="text-sm text-destructive">
            유형을 바꾸면 이전 유형({AGENT_TYPE_LABELS[cred.savedAgentType]})의 저장된 비밀이
            삭제됩니다. 복구할 수 없습니다.
          </p>
        )}
      </div>

      {cred.agentType === 'opencode' ? (
        <OpencodeFields cred={cred} editable={canWrite} />
      ) : (
        <ClaudeFields cred={cred} editable={canWrite} />
      )}

      {/* 유형 → 유형별 필드 → 모델(Ruling #48) → (페이지 하단) 동작 설정 순서. */}
      <ModelField cred={cred} editable={canWrite} />

      {cred.staleNotice && <p className="text-sm text-destructive">{cred.staleNotice}</p>}

      {canWrite && (
        <div className="flex justify-end gap-2">
          {/* Ruling #49(fix round 1) — 이 버튼이 저장하는 두 자원(자격증명 + 모델)을 라벨에
              그대로 담는다. 페이지 전역 버튼(SettingsPage.tsx)과 접근성 이름이 겹치면 안
              된다 — 겹치면 Playwright strict-mode 셀렉터가 두 버튼을 구별하지 못하고(실제
              사용자도 어느 쪽을 눌렀는지 헷갈린다), 이 화면이 두 개의 독립된 저장 흐름을
              갖는다는 사실도 라벨에서 드러나지 않는다. `getByRole(..., { name })` 는 기본이
              부분(substring) 일치라, 페이지 전역 버튼 라벨("나머지 설정 저장"/"나머지 설정
              되돌리기")과 겹치는 부분 문자열이 없는 동사를 그대로 쓴다(반영/초기화). */}
          <Button
            type="button"
            variant="outline"
            onClick={cred.reset}
            disabled={!cred.hasUnsavedInput || isSaving}
          >
            AI 자격증명·모델 초기화
          </Button>
          <Button type="button" onClick={handleSaveClick} disabled={!cred.hasUnsavedInput || isSaving}>
            AI 자격증명·모델 반영
          </Button>
        </div>
      )}

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>자격증명 유형을 바꿀까요?</AlertDialogTitle>
            <AlertDialogDescription>
              {`유형을 ${AGENT_TYPE_LABELS[cred.savedAgentType]}에서 ${AGENT_TYPE_LABELS[cred.agentType]}(으)로 바꾸면 이전 유형의 저장된 비밀이 삭제됩니다. 이 값은 플랫폼 기본값이라 재정의하지 않은 모든 워크스페이스에 영향을 줍니다. 복구할 수 없습니다.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={() => void runSave()}>저장</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </fieldset>
  );
}
