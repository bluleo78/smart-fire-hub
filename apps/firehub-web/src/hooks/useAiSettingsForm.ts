import { useCallback, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../api/settings';
import type { SettingFieldState } from '../lib/settings-fields';
import { BUILTIN_AI_DEFAULTS } from '../lib/settings-fields';
import type { SettingsOverrideForm } from './useSettingsOverrideForm';
import { useSettingsOverrideForm } from './useSettingsOverrideForm';

// 인덱스 시그니처(`[key: string]: string`)를 <b>명시하지 않는다</b> — 명시하면 keyof 가
// string|number 로 넓어져 키를 설정 키 문자열로 다루는 곳마다 타입이 무너진다. 폼 키는 이 9개로
// 닫혀 있다.
//
// `interface` 가 아니라 `type` 인 이유: `useSettingsOverrideForm<F extends SettingsFormShape>` 의
// 제약(`Record<string, string>`)은 <b>암묵적</b> 인덱스 시그니처로 만족되는데, TS 는 그것을
// 타입 별칭에만 준다(interface 는 선언 병합으로 나중에 넓어질 수 있어 주지 않는다).
// keyof 는 그대로 9개 리터럴이므로 위 문단의 성질은 유지된다.
export type AISettingsForm = {
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

/**
 * AI <b>자격증명 번들</b> 3키 — 백엔드 `SettingsService.AI_CREDENTIAL_KEYS` 와 같은 집합이다.
 *
 * 서버가 이 3키를 <b>원자적으로</b> 해석한다(`applyAiCredentialBundle`): 하나라도 테넌트 행이
 * 있으면 3키 전부가 테넌트 평면에서 해석되고, 행이 없는 키는 플랫폼 값이 아니라 채움 값이 된다 —
 * 자격증명 2키는 `""`, `ai.agent_type` 만 예외로 `"sdk"`(`BUNDLE_FILL_VALUES`). 실행 형태
 * 선택자라 빈 값이 안전한 방향이 아니기 때문이다(빈 값은 `AiAgentProxyService` 에서 `cli-api`
 * 분기로 떨어진다).
 *
 * SMTP 연결 5키와 같은 이유로 <b>화면이 이 목록을 갖는 것은 해석이 아니라 배치 때문</b>이다.
 * 해석의 권위는 전적으로 서버 플래그이고(아래 `resolveCredentialGroupState` 는 서버가 내려준
 * `overridden` 만 읽는다), 이 상수가 정하는 것은 "어느 필드가 그룹 테두리 안에 들어가는가"와
 * "그룹 해제가 어느 키를 지우는가" 뿐이다.
 */
export const AI_CREDENTIAL_BUNDLE_KEYS: (keyof AISettingsForm)[] = [
  'ai.agent_type',
  'ai.api_key',
  'ai.cli_oauth_token',
];

/**
 * 서버가 마스킹해서 내려주는 <b>비밀</b> 2키. 마스크를 편집 가능한 입력에 시드하지 않기 위해
 * 공통 훅의 `emptySeedKeys` 로 넘긴다 — 무엇을 막는지는 그 옵션의 주석에 있다.
 *
 * <b>`ai.agent_type` 은 여기 없다.</b> 같은 번들이지만 비밀이 아니고 Select 로 그리므로 서버 값을
 * 그대로 시드해야 한다 — 빈 값을 시드하면 Select 가 placeholder 로 떨어져 "지금 무슨 유형으로
 * 동작 중인지"가 화면에서 사라진다.
 */
const AI_SECRET_KEYS: ReadonlySet<keyof AISettingsForm> = new Set([
  'ai.api_key',
  'ai.cli_oauth_token',
]);

// 필드의 화면 표시 이름 — 저장이 거부된 필드와 번들 해제 부분 실패를 이름으로 지목하는 데 쓴다.
// "어떤 필드가 문제인지" 말해주지 않으면 사용자가 무엇을 고쳐야 할지 알 수 없다.
//
// 폼의 9키 전부를 담는다. 저장 대상 판정이 web 상수가 아니라 서버 플래그(fieldState)로 바뀌었으므로,
// 서버가 지금 잠겨 있는 키를 열어 주면 그 키도 이 목록에 나타날 수 있다 — 일부만 담아 두면 그때
// 이름 대신 undefined 가 사용자에게 보인다.
export const AI_FIELD_LABELS: Record<keyof AISettingsForm, string> = {
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

/**
 * 자격증명 번들의 그룹 상태. 해석이 번들 단위인데 배지가 필드 단위면 배지가 거짓말을 한다 —
 * `ai.agent_type` 이 재정의된 상태에서 `API 키` 옆의 `기본값 사용 중` 은 "그 플랫폼 API 키는
 * 쓰이지 않는다"(번들 채움이 `""` 로 덮었다)는 사실과 정면으로 어긋난다.
 *
 * <b>`locked` 가 하나라도 섞이면 그룹 전체가 `locked` 다(fail-closed).</b> `SettingsOverridePolicy`
 * 가 3키를 함께 열었으므로 오늘 이 조합은 오지 않지만, 서버가 3키 중 일부만
 * `tenantEditable=false` 로 내려주는 모순 상태에서 나머지를 편집 가능하게 그리면 사용자가 저장할
 * 수 없는 폼을 채우게 된다. 모호하면 잠그는 쪽이다. (SMTP 연결 그룹과 같은 규칙 — 같은 이유다.)
 *
 * <b>모듈 레벨 순수 함수인 이유</b>: 훅의 `resolveState` 로 넘기려면 렌더마다 새 참조가 되지
 * 않아야 한다.
 */
function resolveCredentialGroupState(
  fieldState: (key: keyof AISettingsForm) => SettingFieldState,
): 'locked' | 'overridden' | 'inherited' {
  if (AI_CREDENTIAL_BUNDLE_KEYS.some((key) => fieldState(key) === 'locked')) return 'locked';
  if (AI_CREDENTIAL_BUNDLE_KEYS.some((key) => fieldState(key) === 'overridden')) return 'overridden';
  return 'inherited';
}

/**
 * 공통 훅에 넘기는 상태 치환기 — 자격증명 3키는 그룹 상태, 나머지는 개별 상태다.
 * 배지·disabled·저장 대상·dirty 가 <b>모두</b> 이 하나를 거친다. 두 갈래를 호출부마다 다시
 * 조합하면 "화면은 잠갔는데 저장은 보낸다" 같은 어긋남이 생긴다.
 */
function resolveAiState(
  key: keyof AISettingsForm,
  fieldState: (k: keyof AISettingsForm) => SettingFieldState,
): SettingFieldState {
  return AI_CREDENTIAL_BUNDLE_KEYS.includes(key)
    ? resolveCredentialGroupState(fieldState)
    : fieldState(key);
}

/** AI 에이전트 탭이 그리는 데 필요한 <b>번들 레이어</b> + 공통 폼 상태 기계. */
export interface AiSettingsFormState {
  /** 공통 폼 상태 기계. 번들 레이어는 이 위에 얹힌다. */
  base: SettingsOverrideForm<AISettingsForm>;
  staleNotice: string | null;
  credentialGroupState: 'locked' | 'overridden' | 'inherited';
  isEmptyInBundle: (key: keyof AISettingsForm) => boolean;
  /** 서버에 이 비밀의 값이 실재하는가(마스크가 내려왔는가). 빈 입력창의 힌트를 고르는 데 쓴다. */
  isSecretStored: (key: keyof AISettingsForm) => boolean;
  /** "저장된 OAuth 토큰 삭제"를 지금 제시해도 되는가 — 조건은 `handleDeleteOauthToken` 주석 참고. */
  canDeleteOauthToken: boolean;
  /**
   * 번들 3키 중 <b>아직 저장하지 않은 입력</b>이 하나라도 있는가. 확인 다이얼로그가 이것을 읽어
   * "저장된 값만 사라진다"는 기본 카피에 한 문장을 덧붙인다 — 이유는 호출부 주석 참고.
   */
  hasUnsavedCredentialInput: boolean;
  handleClearCredentialBundle: () => Promise<void>;
  handleDeleteOauthToken: () => Promise<void>;
  /**
   * <b>저장은 성공했는데 재조회가 실패했다</b>를 낡음 안내 슬롯에 세운다.
   *
   * `handleSave` 는 이 탭에서 <b>페이지가</b> 갖고 있는데(번들과 무관한 숫자 검증이 붙어 있다)
   * 안내 슬롯은 이 훅의 상태라, 그 경로가 안내를 세울 통로가 필요하다. `setStaleNotice` 를
   * 통째로 내주지 않는 이유: 그러면 문구를 호출부가 짜게 되어 SMTP 와 글자가 갈라지고, 낡음
   * 안내의 어휘가 화면마다 달라진다.
   */
  markSaveRefreshFailed: () => void;
}

/**
 * AI 에이전트 탭의 <b>번들 레이어</b> — 공통 폼 상태 기계 위에 자격증명 3키 원자성을 얹는다.
 * 구조는 `useSmtpSettingsForm` 을 그대로 <b>거울</b>로 삼는다: 같은 서버 규칙(번들 원자 해석)에
 * 같은 화면 문제(그룹 배지·그룹 해제·부분 실패·낡음 안내)가 걸려 있으므로, 다른 모양으로 풀면
 * 한쪽만 고치는 사고가 난다.
 *
 * <b>공통 훅에 넣지 않는 이유</b>: 그 훅의 헤더 주석이 번들 개념 일체를 명시적으로 거부한다.
 * 훅이 여는 유일한 구멍이 `resolveState` 이고, 거기에 `resolveAiState` 를 끼워 "자격증명 3키는
 * 그룹 상태로 판정"을 얹는다. 그룹 배지·그룹 해제·낡음 안내는 전부 이 파일에 남는다.
 *
 * <b>페이지가 여전히 갖는 것</b>: 숫자 검증(`NUMBER_RULES`)·`handleSave`·인증 확인(`verifyAuth`).
 * 이 셋은 번들과 무관해 옮길 이유가 없다 — 다만 `verifyAuth` 는 <b>번들 조작이 트리거한다</b>:
 * 자격증명 번들을 해제하거나 저장된 OAuth 토큰을 지우면 인증 배지가 낡으므로, 훅이
 * `onCredentialsChanged` 로 사건만 알리고 실행은 페이지가 한다(그 옵션 주석 참고).
 */
/** 훅이 페이지에서 받아야 하는 협력자. `useSettingsOverrideForm` 의 `onMetaRefreshed` 와 같은 모양이다. */
export interface UseAiSettingsFormOptions {
  /**
   * 번들의 <b>적용되는 자격증명이 바뀐</b> 직후 호출된다 — 페이지의 `verifyAuth` 를 물린다.
   * 지금 이걸 쏘는 곳은 둘이다: 토큰 삭제 성공 직후, 그리고 번들 해제 시도 직후.
   *
   * <b>왜 훅이 직접 못 하나</b>: 인증 배지(`authStatus`)는 페이지 상태이고 `settingsApi.verifyAuthStatus`
   * 는 번들과 무관한 관심사라, 훅 안으로 끌어오면 이 파일이 "번들 레이어"라는 경계를 잃는다.
   * 그래서 훅은 <b>사건만</b> 알리고 무엇을 할지는 페이지가 정한다.
   *
   * <b>왜 필요한가</b>: 배지의 `✓ 인증됨` 은 <b>방금 치운 그 자격증명</b>으로 얻은 결과다. 그대로
   * 두면 화면이 "인증됨"이라고 말하는데 서버에는 그 값이 없거나 더 이상 적용되지 않는다 —
   * `handleSave` 가 저장 뒤 `verifyAuth()` 를 부르는 것과 정확히 같은 이유다.
   *
   * <b>"토큰 삭제"에만 달지 않는 이유</b>: 번들 해제도 <b>적용되는 자격증명을 바꾼다</b>(테넌트
   * 값에서 플랫폼 값으로). 한쪽에만 달면 같은 종류의 낡은 배지가 다른 버튼으로 다시 들어온다.
   */
  onCredentialsChanged?: () => void;
}

export function useAiSettingsForm(options: UseAiSettingsFormOptions = {}): AiSettingsFormState {
  /**
   * <b>"지금 화면이 서버 상태와 다를 수 있다"</b>를 알리는 지속 안내. 토스트로 끝내지 않는 이유는
   * SMTP 와 같다 — 사용자가 다시 조작해야 하는 상태인데 토스트는 사라지고 스크린리더 사용자가
   * 놓칠 수 있다. 번들 해제 <b>부분 실패</b>와 해제 후 <b>재조회 실패</b>가 이 한 슬롯을 공유한다.
   */
  const [staleNotice, setStaleNotice] = useState<string | null>(null);

  // 안내를 지우는 자리를 한 곳으로 묶는다 — 화면이 실제로 새로워진 그 지점(훅의 메타 갱신 직후)
  // 에서만 지운다. 호출부마다 지우면 하나를 빠뜨리고, SMTP 쪽에서 실제로 빠뜨린 전례가 있다.
  const clearStaleNotice = useCallback(() => setStaleNotice(null), []);

  // 콜백을 ref 로 들고 있는다 — `useSettingsOverrideForm` 이 `onMetaRefreshed` 에 쓰는 것과 같은
  // 관용구다. 호출부가 인라인 화살표를 넘겨도(렌더마다 새 참조) 핸들러가 낡은 클로저를 잡지 않는다.
  const onCredentialsChangedRef = useRef(options.onCredentialsChanged);
  onCredentialsChangedRef.current = options.onCredentialsChanged;

  /**
   * 저장 성공 + 재조회 실패를 알리는 문구. <b>SMTP 와 글자까지 같아야 한다</b> — 같은 사건에
   * 화면마다 다른 어휘를 쓰면 사용자가 둘을 다른 사건으로 읽는다.
   */
  const markSaveRefreshFailed = useCallback(() => {
    const message =
      '저장은 완료됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';
    setStaleNotice(message);
    toast.error(message);
  }, []);

  const base = useSettingsOverrideForm<AISettingsForm>({
    prefix: 'ai',
    defaults: AI_DEFAULTS,
    // 비밀 2키는 서버 마스크를 폼에 시드하지 않는다(덧붙이기 결함 — 옵션 주석 참고).
    emptySeedKeys: AI_SECRET_KEYS,
    resolveState: resolveAiState,
    onMetaRefreshed: clearStaleNotice,
  });

  const { settings, form, original, fieldState, setIsClearing, refreshMeta, resyncFromServer } = base;

  // 그룹 배지·그룹 해제 버튼·그룹 설명문이 읽는 값. 훅에 `resolveState` 로 넘긴 것과 <b>같은</b>
  // 함수를 쓴다 — 표시용 그룹 상태와 저장/dirty 를 지배하는 그룹 상태가 갈라질 자리를 없앤다.
  const credentialGroupState = resolveCredentialGroupState(fieldState);

  /**
   * 번들이 재정의됐는데 이 키에는 테넌트 행이 없어 <b>빈 값으로 해석되는</b> 상태.
   * 키 단위 모델에는 대응하는 상태가 없다(예전이라면 `기본값 사용 중` 이었다).
   *
   * 폼 값이 아니라 <b>서버가 내려준 값</b>을 본다 — 비밀 2키의 폼 값은 이제 <b>항상</b> 빈
   * 문자열이라(`emptySeedKeys`) 폼을 보면 모든 비밀이 비어 있다고 답하게 된다.
   *
   * <b>멤버십을 스스로 확인한다.</b> 번들 밖 키(`ai.model` 등)에 이 노트를 달면 거짓이 된다 —
   * 그 키들은 키 단위로 상속되므로 번들이 재정의됐다고 플랫폼 값이 안 쓰이는 것이 아니다.
   */
  const isEmptyInBundle = (key: keyof AISettingsForm) =>
    AI_CREDENTIAL_BUNDLE_KEYS.includes(key) &&
    credentialGroupState === 'overridden' &&
    (settings[key]?.value ?? '') === '';

  /**
   * 서버가 이 키의 값을 실제로 갖고 있는가. 빈 입력창이 "아직 안 채운 칸"과 "이미 값이 있는 칸"
   * 중 어느 쪽인지는 <b>오직 이 판정으로만</b> 화면에 전달된다 — 입력창 자체는 두 경우에 똑같이
   * 비어 보인다.
   *
   * 폼이 아니라 `settings`(서버 응답)를 본다. 이유는 위와 같다.
   */
  const isSecretStored = (key: keyof AISettingsForm) => (settings[key]?.value ?? '') !== '';

  /**
   * 자격증명 3키 <b>전체</b>의 재정의를 해제한다 — 그룹 머리의 버튼 하나가 3번의 DELETE 를 발행한다.
   *
   * <b>키 단위 해제로는 이 일을 표현할 수 없다.</b> `SettingsService.clearOverride` 는 행 하나를
   * 지우지만 `applyAiCredentialBundle` 은 <b>남은 두 행</b>을 보고 여전히 발동하므로, "해제한" 키는
   * 플랫폼 값이 아니라 번들 채움(`""` / `"sdk"`)으로 해석된다. 즉 <b>3키를 모두 지워야만</b>
   * "플랫폼 상속으로 돌아간다"가 참이 된다 — 그래서 이 3키에는 개별 해제 버튼을 아예 붙이지 않는다.
   *
   * <b>번들 삭제 엔드포인트는 없다.</b> 그래서 부분 실패가 실재하고, 반드시 화면에 그려야 한다.
   * 중간 상태는 원자 해석 아래에서 <b>안전하지만</b>(행이 하나라도 남으면 3키가 전부 테넌트
   * 평면에서 해석된다) 사용자가 보기엔 "해제했는데 아직 재정의 배지"다.
   *
   * 3키를 <b>조건 없이</b> 지운다. 서버가 번들 재정의 상태에서 3키 전부를 `overridden=true` 로
   * 내려주므로 화면은 어느 키에 실제 행이 있는지 알 수 없고, 알 필요도 없다 — `clearOverride` 는
   * 행이 없으면 아무 일도 하지 않는 멱등한 성공이다.
   */
  const handleClearCredentialBundle = async () => {
    setIsClearing(true);
    // 여기서 안내를 지우지 않는다 — 성공적인 재조회가 공통 훅 안에서 지우고(onMetaRefreshed),
    // 실패하면 아래에서 새 안내를 세운다.
    const failedLabels: string[] = [];
    for (const key of AI_CREDENTIAL_BUNDLE_KEYS) {
      try {
        await settingsApi.clearOverride(key);
      } catch {
        failedLabels.push(AI_FIELD_LABELS[key]);
      }
    }

    // 적용되는 자격증명이 바뀌었으므로 인증 배지도 다시 읽는다 — 전부 성공했으면 플랫폼 값으로,
    // 부분 실패면 남은 테넌트 행으로 해석이 바뀌어 있다. 어느 쪽이든 해제 <b>전에</b> 얻은
    // `✓ 인증됨` 은 더 이상 지금 적용되는 자격증명의 답이 아니다. 한 건도 못 지운 경우까지 포함해
    // 무조건 쏜다 — 확인은 멱등하고, 실패 여부를 여기서 다시 판정하면 판정이 두 벌이 된다.
    onCredentialsChangedRef.current?.();

    try {
      // 성공·실패 어느 쪽이든 서버에서 다시 읽는다 — 화면 상태가 실제 행 상태에서 파생되므로
      // 부분 실패도 자동으로 올바르게 그려진다.
      const byKey = await refreshMeta();
      resyncFromServer(AI_CREDENTIAL_BUNDLE_KEYS, byKey);

      if (failedLabels.length > 0) {
        // <b>실패한 항목을 이름으로 지목한다.</b> `AI_FIELD_LABELS` 가 존재하는 이유가 이것인데
        // (그 상수의 주석이 "번들 해제 부분 실패"를 직접 지목한다) 정작 모아만 두고 쓰지 않아,
        // 사용자는 3개 중 무엇이 남았는지 알 길이 없었다 — 다시 시도하라면서 무엇을 다시 시도해야
        // 하는지 말하지 않는 안내였다. 나열 형태는 이 화면의 다른 안내와 같다(`handleSave` 의
        // 거부 목록, SMTP 의 빈 자격증명 목록 — 전부 라벨을 `', '` 로 잇는다).
        const message = `일부 항목만 해제되었습니다. 해제하지 못한 항목: ${failedLabels.join(', ')}. 이 항목은 아직 우리 조직 값으로 적용됩니다 — 다시 시도하세요.`;
        setStaleNotice(message);
        toast.error(message);
      } else {
        toast.success('플랫폼 기본값으로 되돌렸습니다.');
      }
    } catch {
      // 재조회가 실패하면 화면이 지금 어느 상태인지 알 수 없다 — 성공이라고 말하지 않는다.
      const message = '재정의 해제 결과를 확인하지 못했습니다. 새로고침 후 다시 확인하세요.';
      setStaleNotice(message);
      toast.error(message);
    } finally {
      setIsClearing(false);
    }
  };

  /**
   * 저장된 테넌트 OAuth 토큰을 <b>빈 값으로</b> 덮어쓴다 — 번들은 그대로 두고 토큰만 비운다.
   *
   * <b>왜 입력창을 비우는 것으로는 안 되는가</b>: 비밀 입력은 이제 항상 빈 채로 시작하므로
   * (`emptySeedKeys`) `form === original === ''` 이고, "비웠다"는 편집이 성립하지 않는다 —
   * `buildChangedPayload` 는 바뀐 키만 담으므로 이 의도를 표현할 입력 제스처가 존재하지 않는다.
   * (그래서 `ai.cli_oauth_token` 을 `blankAllowed` 에 넣지 않는다: 도달할 수 없는 분기가 된다.)
   * 그렇다고 이 조작 자체를 없애면, `sdk` 로 동작하는 테넌트가 자기 OAuth 토큰을 내려 자기 API
   * 키로 떨어질 길이 사라진다(백엔드는 OAuth 를 우선 적용한다).
   *
   * <b>서버가 허용한다.</b> `SettingsService.validateValues` 의 `ai.cli_oauth_token` 분기는 명시적
   * no-op 이고(빈 값 합법), `encryptIfSecret` 도 이 키만은 빈 값을 그대로 암호화한다. `ai.api_key`
   * 에는 같은 조작이 없다 — 그쪽은 빈 값을 서버가 거부하므로 탈출구가 그룹 해제뿐이다.
   *
   * <b>게이트가 중요하다</b>(`canDeleteOauthToken`): 번들이 아직 상속 중일 때 이 PUT 을 보내면
   * 테넌트 행이 <b>새로</b> 생겨 번들이 발동하고, 상속 중이던 `ai.api_key` 가 `""` 로 떨어진다 —
   * "토큰만 지운다"가 "API 키까지 날린다"가 된다. 그래서 <b>이미 번들이 재정의됐고 토큰에 실제
   * 값이 있을 때만</b> 노출한다. 그 상태에서는 3키가 이미 테넌트 평면이라 바뀌는 것은 토큰 행뿐이다.
   */
  const canDeleteOauthToken =
    credentialGroupState === 'overridden' && isSecretStored('ai.cli_oauth_token');

  /**
   * 번들 3키에 <b>아직 저장하지 않은 입력</b>이 있는가. SMTP 의 `bundleTransitionPending` 과 같은
   * 모양(`form[key] !== original[key]`)이지만 쓰임이 다르다 — 저쪽은 "저장이 번들을 전환시킨다"는
   * 예고이고, 이쪽은 "지금 누르면 그 입력까지 사라진다"는 <b>확인 다이얼로그의 경고</b>다.
   *
   * <b>해제/삭제가 실제로 그 입력을 버린다.</b> 두 핸들러 모두 끝에서 3키를 `resyncFromServer`
   * 하므로, 사용자가 방금 친 API 키·토큰·유형 선택은 서버 해석 값으로 덮인다. 그걸 되살리지
   * 않는 것은 <b>의도한 결정</b>이다 — "플랫폼 기본값으로 되돌린다"고 확인해 놓고 입력만 남으면
   * 그게 더 놀라운 결과다. 대신 <b>누르기 전에</b> 말한다.
   *
   * 비밀 2키의 폼 값은 시드되지 않아 항상 `''` 에서 출발하므로(`emptySeedKeys`), 여기서 `!==` 가
   * 참이라는 것은 곧 "사용자가 직접 쳤다"는 뜻이다 — 서버 마스크가 만드는 거짓 양성이 없다.
   */
  const hasUnsavedCredentialInput = AI_CREDENTIAL_BUNDLE_KEYS.some(
    (key) => form[key] !== original[key],
  );

  const handleDeleteOauthToken = async () => {
    setIsClearing(true);
    try {
      await settingsApi.update({ settings: { 'ai.cli_oauth_token': '' } });
    } catch {
      // 쓰기 자체가 실패했다 — 서버는 그대로이므로 화면이 낡지 않았다. 낡음 안내를 세우면
      // "새로고침하세요"라는 잘못된 다음 동작을 지시하게 된다.
      toast.error('OAuth 토큰 삭제에 실패했습니다.');
      setIsClearing(false);
      return;
    }

    // 쓰기가 성공한 바로 이 지점에서 알린다 — <b>재조회 성공 여부와 무관하게</b>. 배지의
    // `✓ 인증됨` 은 방금 지운 토큰으로 얻은 결과라, 재조회가 실패한 경우엔 오히려 더 낡는다.
    onCredentialsChangedRef.current?.();

    try {
      const byKey = await refreshMeta();
      // 3키를 함께 재시드한다. 토큰 행을 빈 값으로 쓰는 것도 번들 쓰기라, 서버가 나머지 두 키의
      // 해석값을 함께 확정한다 — 토큰 하나만 재시드하면 화면의 나머지 둘이 낡은 채 남는다.
      resyncFromServer(AI_CREDENTIAL_BUNDLE_KEYS, byKey);
      toast.success('저장된 OAuth 토큰을 삭제했습니다.');
    } catch {
      // <b>삭제는 성공했고 다시 그리기가 실패했다.</b> 뭉뚱그려 "삭제 실패"라고 말하면 사용자가
      // 이미 지워진 토큰을 다시 지우려 들고, 그냥 삼키면 화면이 옛 "설정되어 있습니다" 힌트를
      // 계속 보여준다.
      const message =
        '토큰은 삭제됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';
      setStaleNotice(message);
      toast.error(message);
    } finally {
      setIsClearing(false);
    }
  };

  return {
    base,
    staleNotice,
    credentialGroupState,
    isEmptyInBundle,
    isSecretStored,
    canDeleteOauthToken,
    hasUnsavedCredentialInput,
    handleClearCredentialBundle,
    handleDeleteOauthToken,
    markSaveRefreshFailed,
  };
}
