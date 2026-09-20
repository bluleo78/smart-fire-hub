import { ShieldCheck } from 'lucide-react';
import type { ReactNode } from 'react';
import { useState } from 'react';

import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { RadioGroup, RadioGroupItem } from '../../components/ui/radio-group';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import type { CredentialPlane, UseAiCredentialFormResult } from '../../hooks/useAiCredentialForm';
import type { AgentType } from '../../lib/ai-credential';
import { AGENT_TYPES, CREDENTIAL_FIELDS } from '../../lib/ai-credential';
import {
  AGENT_TYPE_LABELS,
  hasTypeChangedFromSaved,
  PROVIDER_ID_CANDIDATES,
  REASONING_EFFORT_CANDIDATES,
  REASONING_EFFORT_DEFAULT_SENTINEL,
  stripProviderPrefix,
  withPreservedValue,
  withProviderPrefix,
} from '../../lib/ai-credential-screen';
import { PlatformLockedNote } from './settings-lock';

/**
 * AI 자격증명(`ai.credential`) 전용 화면 — 설계서 "화면 · 테넌트 화면" 절의 구현.
 *
 * <b>이 파일이 대체하는 것</b>: 옛 `useAiSettingsForm` 의 자격증명 3키 번들 레이어(그룹 배지 ·
 * [재정의 해제] · [저장된 OAuth 토큰 삭제])는 전부 사라진다. `ai.credential` 이 이미 원자적인
 * 문서 하나이므로 "부분 해제"·"부분 실패" 같은 번들 특유의 문제 자체가 없어졌다 — 배지 대신
 * **라디오 2개**("플랫폼 설정을 사용" / "우리 조직이 직접 설정")로, 상태를 읽는 대신 고르게
 * 한다(설계서 §205 "가져다 쓸까, 직접 정할까").
 *
 * <b>모델(`ai.model`)은 여기 없다.</b> 스펙 §48 "ai.model 은 평면 키로 남는다" — 자격증명 문서에
 * 속하지 않는 별도 키라 `GET /settings/ai-credential` 응답에도 없다. 모델 칸(+opencode 4상태)은
 * `SettingsPage.tsx` 가 `behavior` 폼과 함께 이 fieldset **바깥**에 그린다(레이아웃은 "유형별
 * 필드 → 모델 → 동작 설정"으로 이어지되, 모델은 별도 자원이다).
 *
 * <b>순수 로직은 `lib/ai-credential-screen.ts` 에 산다.</b> `react-refresh/only-export-components`
 * 가 컴포넌트 파일에는 컴포넌트만 export 하기를 요구하므로, `willDeleteOnSave`/`credentialIsDirty`
 * /`buildSaveConfirm` 같은 렌더 없이 테스트 가능한 결정 로직은 그쪽에 있다 — `SettingsPage.tsx`
 * 가 저장 오케스트레이션에 그 함수들을 직접 쓴다.
 */

interface RadioOptionProps {
  id: string;
  value: CredentialPlane;
  title: string;
  description: string;
  disabled?: boolean;
}

function RadioOption({ id, value, title, description, disabled }: RadioOptionProps) {
  return (
    <div className="flex items-start gap-3 rounded-md border p-3">
      <RadioGroupItem value={value} id={id} disabled={disabled} className="mt-0.5" />
      <div className="space-y-0.5">
        <label htmlFor={id} className="text-sm font-medium cursor-pointer">
          {title}
        </label>
        <p className="text-xs text-muted-foreground">{description}</p>
      </div>
    </div>
  );
}

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
  disabled,
}: {
  cred: UseAiCredentialFormResult;
  modelValue: string;
  onModelChange: (value: string) => void;
  disabled: boolean;
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

  // Ruling #44(fix round 1) — 플랫폼 평면(§209)은 기본 URL·API 키 입력 자체가 없어
  // `canLoadModels` 가 이 평면에서 절대 true 가 될 수 없다(둘 다 타이핑할 곳이 없으니). §195의
  // 4상태 모델 로딩은 애초에 "우리가 자격증명을 갖고 있을 때"를 전제한 설계라 이 평면을 다루지
  // 않는다 — 그렇다고 모델을 못 정하게 두면 §48이 약속한 "플랫폼 자격증명 + 우리 모델" 조합이
  // 막힌다. 그래서 플랫폼 평면에서는 4상태 대신 늘 열린 자유 입력 하나로 대체한다.
  const isPlatformPlane = cred.plane === 'platform';

  let field: ReactNode;
  let hint: ReactNode = null;

  if (isPlatformPlane) {
    field = (
      <Input
        id="ai-model"
        value={bareModel}
        disabled={disabled}
        onChange={(e) => handleFreeInputChange(e.target.value)}
        placeholder="예: gpt-4o"
      />
    );
    hint = (
      <p className="text-sm text-muted-foreground">
        플랫폼 자격증명을 그대로 사용하되, 모델만 직접 입력할 수 있습니다.
      </p>
    );
  } else if (manualOverride) {
    field = (
      <Input
        id="ai-model"
        value={bareModel}
        disabled={disabled}
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
        disabled={disabled}
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
        disabled={disabled}
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

  // "모델" Label + 상태 배지는 호출부(`SettingsPage.tsx`)가 이미 `SettingFieldLabel` 로 그린다
  // (`ai.model` 은 이 컴포넌트가 모르는 별도 자원이라 상속/재정의 배지도 거기서만 정확히 안다) —
  // 여기서 또 그리면 "모델" 이 두 번 나온다.
  return (
    <div className="space-y-2">
      <div className="flex gap-2 max-w-md items-start">
        <div className="flex-1">{field}</div>
        {/* 플랫폼 평면엔 "불러올" 기본 URL·API 키가 애초에 없으니 버튼 자체를 숨긴다 — 늘
            비활성인 버튼을 보여줘 "왜 안 눌리지"를 궁금하게 만들지 않는다. */}
        {!isPlatformPlane && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleLoadModels}
            disabled={!cred.canLoadModels || disabled}
            className="shrink-0"
          >
            모델 불러오기
          </Button>
        )}
      </div>
      {/* 이 안내는 "기본 URL과 API 키를 입력하면"을 말하는데, 플랫폼 평면엔 그 입력 자체가
          없다 — 그대로 두면 존재하지 않는 입력칸을 가리키는 거짓 안내가 된다. */}
      {!isPlatformPlane && !cred.canLoadModels && (
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
  /** 서버와 마지막으로 동기화된 유형 — `useSavedAgentType(cred)` 결과를 그대로 넘긴다(페이지가
   * 저장 확인 다이얼로그에도 같은 값을 써야 하므로 훅 호출을 페이지가 한 번만 하고 내려준다). */
  savedAgentType: AgentType;
  authStatus: { valid: boolean; email?: string; subscriptionType?: string } | null;
  isVerifying: boolean;
  onVerifyAuth: () => void;
  /**
   * 지금 적용 중인(resolved) `ai.model` 값(Ruling #43, fix round 1) — "플랫폼 설정을 사용"
   * 정의 목록에 "모델" 행으로 넣는다. 이 fieldset 은 `ai.credential` 만 알고 `ai.model` 은
   * 전혀 모르는 별도 키라(§48), 페이지(`SettingsPage.tsx`)가 이미 갖고 있는 `behavior` 폼의
   * <b>해석된(resolved)</b> 값을 그대로 내려받는다 — 플랫폼의 원본 값이 아니라 해석된 값을
   * 쓰는 이유는 `ai.model` 자체가 테넌트 오버라이드 대상이라, "지금 적용 중"이 플랫폼 원본과
   * 다를 수 있기 때문이다.
   */
  resolvedModel: string;
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
 * §191). `cred.secretFieldNames` 는 훅이 이미 유형·평면 불일치를 걸러 낸 값이다(Ruling #38) —
 * 여기서 다시 거르지 않는다. */
function secretHint(fieldName: string, cred: UseAiCredentialFormResult, editable: boolean) {
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

/**
 * AI 자격증명 fieldset 본체 — 라디오 2개 + (플랫폼 값 미리보기 | 유형별 입력 폼).
 * `SettingsPage.tsx` 의 `모델 설정` 카드 안, `모델`/`동작 설정` 위에 놓인다.
 */
export function AiCredentialFieldset({
  cred,
  savedAgentType,
  authStatus,
  isVerifying,
  onVerifyAuth,
  resolvedModel,
}: AiCredentialFieldsetProps) {
  const editable = !cred.isLocked;
  const typeChanged = hasTypeChangedFromSaved(cred.agentType, savedAgentType, cred.tenantOwned);

  // Important #1(fix round 1) — 최초 조회(GET)가 실패하면 훅은 안전한 기본값
  // (`plane:'platform', tenantOwned:false, secretFieldNames:[]`)으로 주저앉는다(hook 주석
  // 참고). 이 기본값을 그대로 평소 렌더 경로에 흘리면 "플랫폼에 설정된 값이 없습니다"를
  // <b>사실</b>처럼 보여주게 된다 — 실제로는 "몰라서" 못 그리는 것뿐인데 "없다"고 단정하는
  // 것이다. 토스트는 지나가 버리므로, 여기서 별도의 지속적인 실패 상태를 그려 화면 자체가
  // 거짓을 말하지 않게 막는다.
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

      {/* "재정의"는 시스템 내부 용어다. 쓰는 사람의 질문은 "플랫폼 걸 쓸까, 우리가 정할까"이고,
          상태를 읽는 배지가 아니라 고르는 선택으로 두면 그 질문에 그대로 답하게 된다. 전환은
          폼 상태다 — 즉시 삭제하면 실수로 한 번 누른 대가가 모든 비밀 재입력이다(§213). */}
      <RadioGroup
        aria-label="자격증명 출처"
        value={cred.plane}
        onValueChange={(v) => cred.setPlane(v as CredentialPlane)}
        disabled={!editable}
        className="space-y-2"
      >
        <RadioOption
          id="ai-cred-plane-platform"
          value="platform"
          title="플랫폼 설정을 사용"
          description="플랫폼 운영자가 정한 값이 그대로 적용됩니다. 운영자가 값을 바꾸면 우리 조직에도 함께 반영됩니다."
          disabled={!editable}
        />
        <RadioOption
          id="ai-cred-plane-tenant"
          value="tenant"
          title="우리 조직이 직접 설정"
          // 과금 문구 주의(브리프·설계서 둘 다 명시): "사용량도 우리 계정으로 청구됩니다"는
          // cli(구독 OAuth)·사내 엔드포인트에서 거짓이다.
          description="AI 호출이 우리 조직 자격증명으로 나갑니다."
          disabled={!editable}
        />
      </RadioGroup>
      {!editable && <PlatformLockedNote />}

      {/*
       * Ruling #46(fix round 1 리뷰) — 잠긴 상태(GET 403)에서는 아래 평면별 내용을 아예 그리지
       * 않는다. `isLocked` 는 `fetchAndApply` 가 403 으로 실패해 `applyResponse` 를 전혀 못 부른
       * 경우라, 이 시점의 `plane`/`tenantOwned`/`secretFieldNames` 는 <b>모두 훅의 초기값</b>
       * (`EMPTY_ORIGINAL`: `plane:'platform', tenantOwned:false, secretFieldNames:[]`)이다 —
       * 서버가 실제로 그렇다고 답한 값이 아니라 "몰라서" 비어 있는 값이다. 이 가드가 없으면
       * `PlatformPlaneSummary` 가 그 초기값을 그대로 읽어 `PLATFORM_EMPTY_NOTE`("플랫폼에 설정된
       * 값이 없습니다")를 <b>사실</b>처럼 함께 그린다 — 진실은 "권한이 없어 볼 수 없다"인데
       * 화면은 "플랫폼에도 값이 없다"고 단정하는 것이다. `loadFailed` 가드(위)가 막는 것과 같은
       * 종류의 결함("모른다"를 "없다"로 잘못 말하는 것)이라 같은 원칙으로 막는다.
       */}
      {editable &&
        (cred.plane === 'platform' ? (
          <PlatformPlaneSummary cred={cred} resolvedModel={resolvedModel} />
        ) : (
          <TenantPlaneForm
            cred={cred}
            editable={editable}
            typeChanged={typeChanged}
            savedAgentType={savedAgentType}
            authStatus={authStatus}
            isVerifying={isVerifying}
            onVerifyAuth={onVerifyAuth}
          />
        ))}
    </fieldset>
  );
}

/**
 * "플랫폼 설정을 사용" 상태 — 입력칸 없음, 지금 적용 중인 값만 보여준다(설계서 §209).
 *
 * <b>세 갈래</b>(자문 리뷰에서 식별 — 브리프 Step 2 의 두 갈래로는 부족하다):
 * 1. `tenantOwned===true` — 라디오는 방금 "플랫폼"으로 옮겼지만 <b>아직 저장 전</b>이라 실제
 *    적용 중인 문서는 여전히 테넌트 것이다. `cred.payload`/`secretFieldNames` 는 이 순간에도
 *    (Ruling #38 필터를 지나면) 테넌트 문서를 반영하므로, 그대로 "플랫폼 값"이라며 그리면
 *    거짓말이 된다 — 목록을 그리지 않고 저장하면 무엇이 될지만 예고한다.
 * 2. `tenantOwned===false && secretFieldNames.length>0` — 진짜 플랫폼 값을 정의 목록으로.
 * 3. `tenantOwned===false && secretFieldNames.length===0` — 플랫폼에도 값이 없다(Ruling #15:
 *    이 판정은 `secretFieldNames` 가 비었는지로만 유도된다).
 *
 * <b>모델은 원래 이 목록에 없었다</b>(자문 리뷰 반영, 브리프 Step 2 예시와 다름) — `ai.model` 은
 * `GET /settings/ai-credential` 응답에 아예 없는 별도 자원이라 이 컴포넌트가 원천적으로 알 수
 * 없다. 다만 Ruling #43(fix round 1)으로, "지금 적용 중인 값" 목록이 모델을 빼면 반쪽짜리라는
 * 지적을 받아 페이지가 <b>해석된(resolved)</b> 값을 `resolvedModel` prop 으로 내려주고, 진짜
 * 플랫폼 값 목록(2번 갈래)에서만 그 값을 함께 그린다 — `SettingsPage.tsx` 의 "모델" 필드가 자기
 * 배지로 따로 보여주는 것과는 별개로, 여기 정의 목록도 완전해야 한다.
 */
function PlatformPlaneSummary({
  cred,
  resolvedModel,
}: {
  cred: UseAiCredentialFormResult;
  resolvedModel: string;
}) {
  if (cred.tenantOwned) {
    // Ruling #45 — 테넌트가 소유 중일 때는 이 화면(테넌트 사용자)에게 "진짜 플랫폼 값"을 보여줄
    // 방법이 없다: `GET /settings/ai-credential` 은 테넌트 우선 해석 결과 하나만 주고, 플랫폼
    // 원본을 보려면 플랫폼 운영자 권한이 필요한 별도 엔드포인트를 타야 한다. 그래서 목록 대신
    // "저장하면 이렇게 된다"는 예고 문구만 보여주는 것이 지금 API 로 할 수 있는 정직한 최선이다.
    return <p className="text-sm text-muted-foreground">저장하면 플랫폼 운영자가 정한 값이 적용됩니다.</p>;
  }
  if (cred.secretFieldNames.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        플랫폼에 설정된 값이 없습니다 — AI 기능이 동작하지 않습니다. 직접 설정하거나 플랫폼
        운영자에게 요청하세요.
      </p>
    );
  }
  const payloadFields = payloadFieldsFor(cred.agentType).filter(
    (f) => (cred.payload[f.name] ?? '') !== '',
  );
  return (
    <div className="space-y-2">
      <dl className="space-y-1 text-sm">
        <div className="flex gap-2">
          <dt className="text-muted-foreground">유형</dt>
          <dd>{AGENT_TYPE_LABELS[cred.agentType]}</dd>
        </div>
        <div className="flex gap-2">
          <dt className="text-muted-foreground">모델</dt>
          <dd>{resolvedModel || '(설정 안 됨)'}</dd>
        </div>
        {payloadFields.map((f) => (
          <div key={f.name} className="flex gap-2">
            <dt className="text-muted-foreground">{f.label}</dt>
            <dd>{cred.payload[f.name]}</dd>
          </div>
        ))}
        <div className="flex gap-2">
          <dt className="text-muted-foreground">설정된 비밀</dt>
          <dd>{cred.secretFieldNames.map((n) => secretFieldLabel(cred.agentType, n)).join(', ')}</dd>
        </div>
      </dl>
      <p className="text-sm text-muted-foreground">지금 적용 중인 플랫폼 값입니다.</p>
    </div>
  );
}

/** `CREDENTIAL_FIELDS[type]` 의 payload 필드만 뽑는다(정의 목록에 시크릿 필드가 값과 함께 섞여
 * 나가지 않도록 — 시크릿은 `secretFieldNames` 로 이름만 별도로 보여준다). */
function payloadFieldsFor(agentType: AgentType) {
  return CREDENTIAL_FIELDS[agentType].filter((f) => f.plane === 'payload');
}

function secretFieldLabel(agentType: AgentType, fieldName: string): string {
  return CREDENTIAL_FIELDS[agentType].find((f) => f.name === fieldName)?.label ?? fieldName;
}

/** "우리 조직이 직접 설정" 상태 — 유형(맨 앞) → 유형별 필드(설계서 §180 공통 레이아웃). */
function TenantPlaneForm({
  cred,
  editable,
  typeChanged,
  savedAgentType,
  authStatus,
  isVerifying,
  onVerifyAuth,
}: {
  cred: UseAiCredentialFormResult;
  editable: boolean;
  typeChanged: boolean;
  savedAgentType: AgentType;
  authStatus: AiCredentialFieldsetProps['authStatus'];
  isVerifying: boolean;
  onVerifyAuth: () => void;
}) {
  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <Label htmlFor="ai-cred-agent-type">에이전트 유형</Label>
        <Select
          value={cred.agentType}
          onValueChange={(v) => cred.setAgentType(v as AgentType)}
          disabled={!editable}
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
        {typeChanged && (
          <p className="text-sm text-destructive">
            유형을 바꾸면 이전 유형({AGENT_TYPE_LABELS[savedAgentType]})의 저장된 비밀이
            삭제됩니다. 복구할 수 없습니다.
          </p>
        )}
      </div>

      {cred.agentType === 'opencode' ? (
        <OpencodeFields cred={cred} editable={editable} />
      ) : (
        <ClaudeFields
          cred={cred}
          editable={editable}
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
  editable,
  authStatus,
  isVerifying,
  onVerifyAuth,
}: {
  cred: UseAiCredentialFormResult;
  editable: boolean;
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
              disabled={!editable}
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
          <div id="ai-cred-oauth-token-hint">{secretHint('oauthToken', cred, editable)}</div>
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
              disabled={!editable}
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
          <div id="ai-cred-api-key-hint">{secretHint('apiKey', cred, editable)}</div>
        </div>
      )}
    </div>
  );
}

/** opencode 전용 입력 — 공급자·기본 URL·API 키·추론 강도. 검증 수단은 "모델 불러오기"(모델
 * 칸은 `SettingsPage.tsx` 가 이 fieldset 밖에서 그린다, 이 파일 헤더 주석 참고). */
function OpencodeFields({ cred, editable }: { cred: UseAiCredentialFormResult; editable: boolean }) {
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
        <Input
          id="ai-cred-opencode-api-key"
          type="password"
          className="max-w-md"
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
