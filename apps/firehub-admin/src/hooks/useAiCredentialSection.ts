import axios from 'axios';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '@/api/settings';
import type { AgentType } from '@/lib/ai-credential';
import { AGENT_TYPES, CREDENTIAL_FIELDS, stripProviderPrefix, withProviderPrefix } from '@/lib/ai-credential';
import { serverMessage } from '@/lib/http-errors';
import type {
  PlatformAiAuthStatusResponse,
  PlatformAiCredentialProbeRequest,
  PlatformAiCredentialResponse,
  SettingResponse,
} from '@/types/platform';

/**
 * `AiCredentialSection` 의 폼 상태 기계(Task 13, Ruling #50 로 `pages/settings/AiCredentialSection.tsx`
 * 에서 이 파일로 옮겼다) — `react-refresh/only-export-components` 가 컴포넌트 파일에는 컴포넌트만
 * export 하기를 요구하기 때문이기도 하고, 더 중요하게는 <b>이 훅을 어디서 부르느냐가 곧
 * Ruling #50 의 해법</b>이기 때문이다: `SettingsPage.tsx` 가 `Tabs` 바깥(탭이 전환돼도 언마운트
 * 되지 않는 위치)에서 딱 한 번 불러 그 결과를 `<AiCredentialSection cred={cred} />` 로 내려준다.
 * 예전에는 이 훅을 `AiCredentialSection` 컴포넌트 자신이 불렀는데, 그 컴포넌트는
 * `TabsContent value="ai"` 안에 있어 Radix 가 비활성 탭의 `TabsContent` 를 기본적으로
 * 언마운트한다 — SMTP/임베딩 탭으로 옮겼다 AI 탭으로 돌아오면 이 훅이 통째로 다시 마운트되어
 * 타이핑 중이던 OAuth 토큰·API 키·모델이 전부 사라졌다(값을 다시 불러오는 게 아니라 **로컬
 * state 자체가 초기화**되는 것이라 새로고침도 아니다). 이 화면은 서버가 비밀을 절대 돌려주지
 * 않으므로(§ 공통 절), 잃어버린 편집은 "다시 입력"이 아니라 "다시 타이핑할 수 없는 값을 잃음"
 * 이다 — §213 이 "즉시 DELETE 대신 폼 상태"를 택한 것과 같은 이유로, 이 상태도 탭 전환에
 * 취약하면 안 된다. 훅을 부르는 위치를 `Tabs` 바깥으로 옮기면 그 부모(`SettingsPage`)가 애초에
 * 언마운트되지 않으므로 값이 자연히 살아남는다 — `forceMount` 로 DOM 에 숨겨 두는 대안보다
 * 이 화면이 이미 하나의 자원(`ai.credential` + `ai.model`)을 다루는 훅+프레젠테이션 컴포넌트
 * 구조(테넌트 화면과 같은 모양, `useAiCredentialForm` + `AiCredentialFieldset`)에 더 가깝다.
 *
 * <b>`ai.model` 값은 이 훅이 스스로 조회하지 않는다</b> — `settingsData` 인자로 `SettingsPage`
 * 가 이미 갖고 있는 `GET /api/platform/settings` 응답을 그대로 받는다(실측 버그: 처음엔 이
 * 훅이 `settingsApi.getAll()` 을 독자적으로 또 불렀는데, 그러면 같은 경로에 요청이 두 번
 * 나가 "저장 후 재조회" 를 세는 e2e 가 순서를 잃고 깨졌다 — 페이지가 이미 부르는 응답을
 * 재사용하는 게 맞다).
 *
 * <b>모델 저장 후 페이지의 react-query 캐시를 무효화하지 않는다(실측 버그, fix round 1 리뷰
 * 에서 발견).</b> 처음엔 저장 성공 시 `queryClient.invalidateQueries(['platform-settings'])`
 * 를 불렀는데, 그 무효화가 `SettingsPage` 의 `data !== seededFrom` 재시드 블록을 다시 돌려
 * 나머지 탭(예: 이메일 6키)에 타이핑 중이던 **미저장** 편집까지 서버 값으로 덮어썼다 —
 * `ai.model` 하나를 갱신하려다 이 컴포넌트가 전혀 모르는 다른 자원의 미저장 상태를 없애는,
 * 바로 이 라운드가 고치려던 결함(#50)과 같은 종류의 사고였다. `ai.model` 값은 이 섹션
 * 바깥 어디에서도 다시 읽지 않으므로(`ALL_SETTING_KEYS` 에서 빠졌다, `settings-catalog.ts`
 * 참고) 캐시를 무효화할 필요 자체가 없다 — 저장이 성공하면 그냥 `originalModel` 을 방금
 * 저장한 값으로 로컬에서 갱신한다.
 */

const DEFAULT_AGENT_TYPE: AgentType = 'sdk';

interface OriginalSnapshot {
  agentType: AgentType;
  payload: Record<string, string>;
  secretFieldNames: string[];
}

const EMPTY_ORIGINAL: OriginalSnapshot = {
  agentType: DEFAULT_AGENT_TYPE,
  payload: {},
  secretFieldNames: [],
};

/** payload 필드가 문자열이 아닌 값을 담고 있으면 무시한다 — 이 화면이 정의한 필드는 전부
 * text/select 이고, 서버 `Map<String,Object>` 가 줄 수 있는 다른 타입을 렌더할 방법이 없다. */
function toStringPayload(raw: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {};
  Object.entries(raw).forEach(([key, value]) => {
    if (typeof value === 'string') out[key] = value;
  });
  return out;
}

export function useAiCredentialSection(settingsData: SettingResponse[] | undefined) {
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [staleNotice, setStaleNotice] = useState<string | null>(null);

  const [agentType, setAgentTypeState] = useState<AgentType>(DEFAULT_AGENT_TYPE);
  const [payload, setPayloadState] = useState<Record<string, string>>({});
  const [secretInputs, setSecretInputsState] = useState<Record<string, string>>({});
  const [models, setModels] = useState<string[] | null>(null);
  const [modelsError, setModelsError] = useState<string | null>(null);
  const [original, setOriginal] = useState<OriginalSnapshot>(EMPTY_ORIGINAL);

  // Ruling #48(fix round 1) — `ai.model` 은 `ai.credential` 문서에 속하지 않는 별도 평면 키다
  // (설계서 §48). 그래도 이 훅이 함께 들고 있는 이유: opencode 를 고른 관리자가 이 화면만으로
  // "쓸 수 있는" 상태(자격증명 + providerId/modelId 형식 모델)에 도달해야 한다는 게 Ruling #48
  // 의 핵심이라, 두 값을 한 저장 흐름 안에서 같이 다뤄야 그 상태가 원자적으로 만들어진다.
  //
  // <b>`model` state 는 항상 "맨 모델 id"(접두어 없음)다(Ruling #52, fix round 2).</b>
  // 예전엔 이 state 가 서버 원형(opencode 는 `providerId/modelId`)을 그대로 들고 있었고,
  // 입력칸 `onChange` 가 **타이핑하는 그 순간의** `providerId` 로 접두어를 즉시 다시 붙였다 —
  // 그러면 "모델을 먼저 타이핑하고 공급자를 나중에 고르면" 접두어가 다시 붙지 않는다(리뷰가
  // 실측: PUT 바디가 `{"ai.model":"gpt-4o"}` 로 나갔다, `openai/gpt-4o` 가 아니라). 서버는
  // 슬래시가 없는 `ai.model` 을 "아직 대조 불가"로 통과시키므로(Ruling #28) 400 으로도 안
  // 잡히고, 실제 채팅 시점에야 `splitOpencodeModel` 에서 깨진다(스펙 §199 가 예고한 실패
  // 그대로). 고침: `model` 은 접두어 없이 그대로 두고, 접두어는 저장 직전(`doSave`)에만
  // 조립한다 — "타이핑 순서"가 아예 성립하지 않는 값이 된다.
  const [model, setModelState] = useState('');
  const [originalModel, setOriginalModel] = useState('');
  const [modelDescription, setModelDescription] = useState<string | null>(null);
  /** `settingsData` 를 어느 참조까지 반영했는지 — `SettingsPage.tsx` 의 `seededFrom` 과 같은
   * 관용구(렌더 중 `setState`). 참조가 바뀔 때만(최초 로드, 또는 페이지의 "나머지 설정" 저장
   * 등 이 섹션이 모르는 이유로 이 쿼리가 다시 불려 새 참조가 온 경우) 다시 시드한다 — 이
   * 섹션 자신의 저장은 더 이상 이 쿼리를 무효화하지 않는다(파일 헤더 주석 참고, 실측 버그).
   *
   * <b>`!isLoading` 도 함께 요구한다(fix round 2).</b> 맨 모델 id 로 벗기려면 이 시점의
   * `payload.providerId` 가 필요한데, 그 값은 별도 요청(자격증명 GET)에서 온다 — 두 요청이
   * 어느 순서로 끝날지 보장이 없다. 자격증명이 아직 안 끝났을 때(`isLoading===true`) 시드를
   * 건너뛰면(=`seededModelFrom.current` 를 갱신하지 않으면), 다음 렌더에서 `settingsData`
   * 참조는 그대로인데 `isLoading` 만 바뀌어도 이 블록이 다시 평가돼 그제서야 정확히 벗겨
   * 시드한다. */
  const seededModelFrom = useRef<SettingResponse[] | undefined>(undefined);
  if (settingsData !== undefined && !isLoading && settingsData !== seededModelFrom.current) {
    const isInitialSeed = seededModelFrom.current === undefined;
    seededModelFrom.current = settingsData;
    const row = settingsData.find((s) => s.key === 'ai.model');
    const value = row?.value ?? '';
    // fix round 2, item 3(3번째 #50 부류 결함) — 이 블록이 다시 도는 건 최초 로드뿐 아니라
    // <b>페이지의 "나머지 설정 저장"</b>이 `['platform-settings']` 을 무효화할 때도다(그
    // 무효화는 SettingsPage.tsx 자신의 저장 흐름이 부르는 것이라 이 훅이 막을 수 없다, 파일
    // 헤더 주석의 "이 섹션 자신의 저장은 무효화하지 않는다"와는 별개). 이메일·임베딩 탭
    // 값이 하나라도 바뀌면 react-query 구조적 공유가 <b>최상위 배열 참조</b>를 새로 만든다
    // (`ai.model` 행 자체는 안 바뀌었어도) — 배열 참조만 비교하면 지금 막 타이핑 중인(아직
    // 저장 안 한) 모델 편집을 "예전에 봤던 그 값"으로 조용히 되돌리게 된다(리뷰가 실측:
    // Claude Opus 4.8 로 편집 중이던 값이 Claude Sonnet 5 로 되돌아갔다). 그래서 "최초
    // 로드"거나 "지금 편집 중인 값이 마지막으로 시드한 값에서 전혀 손대지 않았다"일 때만
    // model/originalModel 을 갱신한다 — 사용자가 실제로 타이핑을 시작했으면 그 뒤로는 이
    // 섹션과 무관한 재조회가 그 편집을 침범하지 않는다. `modelDescription` 은 순수 안내
    // 문구(폼 값이 아니다)라 덮어써도 데이터 손실이 없으므로 이 가드 밖에서 항상 갱신한다.
    const untouched = model === stripProviderPrefix(originalModel, payload.providerId ?? '');
    if (isInitialSeed || untouched) {
      setModelState(stripProviderPrefix(value, payload.providerId ?? ''));
      setOriginalModel(value);
    }
    setModelDescription(row?.description ?? null);
  }

  // Ruling #47(fix round 1) — 인증 확인 배지. 탭 전환에도 살아남아야 하므로(이 훅 자체가 그
  // 이유로 여기 있다) 자격증명 state 와 같은 컴포넌트 수명 주기를 공유한다.
  const [authStatus, setAuthStatus] = useState<PlatformAiAuthStatusResponse | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);
  /** 마지막으로 시작한 인증 확인의 일련번호 — 늦게 도착한 낡은 응답을 버린다(firehub-web
   * `SettingsPage.tsx` 의 `verifySeqRef` 와 같은 가드, 같은 이유). */
  const verifySeqRef = useRef(0);

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

  const applyResponse = useCallback((data: PlatformAiCredentialResponse) => {
    // 알 수 없는 agentType(손으로 고친 행)은 화면 안전을 위해 sdk 로 보인다 — 저장을 누르지
    // 않는 한 실제 문서는 그대로다. 서버 쪽 판정(resolve())은 별도로 fail-closed 다.
    const nextAgentType: AgentType = (AGENT_TYPES as readonly string[]).includes(data.agentType)
      ? (data.agentType as AgentType)
      : DEFAULT_AGENT_TYPE;
    const nextPayload = toStringPayload(data.payload);

    setAgentTypeState(nextAgentType);
    setPayloadState(nextPayload);
    setSecretInputsState({});
    setModels(null);
    setModelsError(null);
    setOriginal({ agentType: nextAgentType, payload: nextPayload, secretFieldNames: data.secretFieldNames });
    // 재조회(최초 로드·저장 후 재조회 둘 다)마다 낡은 배지를 지운다 — 이 응답 자체는 인증
    // 상태를 말해주지 않으니, 이전 배지를 그대로 두면 "방금 막 확인한 것"처럼 보인다.
    setAuthStatus(null);
  }, []);

  // `ai.model` 을 다시 읽지 않는다 — `settingsData` prop(페이지가 이미 부른
  // `GET /api/platform/settings`)이 그 역할을 한다. 여기서 `getAll()` 을 또 부르면 같은
  // 경로에 요청이 두 번 나가 "저장 후 재조회" 순서를 세는 e2e 가 깨진다(실측, 이 파일 헤더
  // 주석 참고).
  const fetchAndApply = useCallback(async () => {
    const { data } = await settingsApi.getAiCredential();
    applyResponse(data);
  }, [applyResponse]);

  const load = useCallback(async () => {
    setIsLoading(true);
    try {
      await fetchAndApply();
      setLoadFailed(false);
    } catch {
      setLoadFailed(true);
      toast.error('AI 자격증명을 불러오지 못했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, [fetchAndApply]);

  // 최초 1회만 조회한다 — StrictMode 이중 실행이나 `load` 참조 변동으로 재조회가 걸리면
  // `original` 이 다시 시드되어 입력 중이던 편집이 조용히 덮인다.
  const didInitialLoad = useRef(false);
  useEffect(() => {
    if (didInitialLoad.current) return;
    didInitialLoad.current = true;
    void load();
  }, [load]);

  const setAgentType = useCallback(
    (next: AgentType) => {
      if (next === agentType) return;
      setAgentTypeState(next);
      // 유형이 바뀌면 payload/secret 모두 새로 시작한다 — 이름이 겹치는 필드(`apiKey`)가
      // 있어도 예외 없이 비운다. `Sdk.apiKey`(Anthropic)와 `Opencode.apiKey`(OpenAI 호환)는
      // 이름만 같고 다른 비밀이다.
      setPayloadState({});
      setSecretInputsState({});
      setModels(null);
      setModelsError(null);
      // fix round 2 — opencode 를 "떠날 때"만 모델을 비운다. 예전엔 아예 안 비워서, opencode
      // (예: 맨 id "gpt-4o")에서 sdk 로 돌아가면 그 값이 그대로 남아 `withPreservedValue` 가
      // Claude 세 모델 목록에 없는 그 값을 가짜 옵션으로 끼워 넣었다 — 그래서 처음엔 유형이
      // 바뀔 때마다 무조건 비웠는데, 그러면 sdk↔cli↔cli-api 끼리(모델 카탈로그가 완전히 같다,
      // 셋 다 접두어 없는 평면 Claude 모델 이름)도 손대지 않은 모델이 사라져 "유형만 바꿨는데
      // 모델 칸이 비었다"는 놀람을 준다 — 자격증명만 바꾸고 저장하는 흐름(테스트: "유형을
      // 바꾸고 저장하면 확인 다이얼로그가 뜨고...")에서 빈 모델을 서버에 실수로 써 보낼
      // 뻔했다(실측, e2e 로 잡았다). opencode 는 표현 형식 자체가 다르므로(맨 id ↔
      // providerId/맨 id) 그쪽을 "떠날 때"만 비운다 — "들어올 때"는 기존 맨 id 를 그대로 두면
      // 저장 시점에 현재 providerId 로 다시 조립되어(Ruling #52, 아래 `effectiveModel`)
      // 손대지 않아도 opencode 형식으로 자동 보정된다(sibling 버그: "opencode 로 바꾸고
      // 저장해도 예전 Claude 모델 값이 그대로 남는다"를 여기서 막는다).
      if (agentType === 'opencode') {
        setModelState('');
      }
    },
    [agentType],
  );

  const setPayloadField = useCallback((name: string, value: string) => {
    setPayloadState((prev) => ({ ...prev, [name]: value }));
    if (name === 'baseURL') {
      setModels(null);
      setModelsError(null);
    }
  }, []);

  const setSecretInput = useCallback((name: string, value: string) => {
    setSecretInputsState((prev) => ({ ...prev, [name]: value }));
  }, []);

  // `secretFieldNames` 의 노출본 — 지금 화면이 보여주는 유형이 원본 문서의 유형과 같을 때만
  // 그대로 내준다(firehub-web Ruling #38 과 같은 규칙). 유형만 로컬에서 바꾼 상태에서 원본을
  // 그대로 내주면 새 유형 폼에 "설정되어 있습니다"를 잘못 붙이게 된다.
  // 서버 원본은 `original.secretFieldNames` 하나다 — 예전에는 같은 값을 별도 state 로도 들고
  // 있었는데(설정되는 지점이 applyResponse 한 곳뿐이라 항상 같은 값이었다) 두 출처가 있는 것처럼
  // 보였다.
  const visibleSecretFieldNames =
    agentType === original.agentType ? original.secretFieldNames : [];

  // [모델 불러오기(연결 확인)] 활성 조건. **`secretFieldNames` 기반 "저장된 키" 폴백을 쓰지
  // 않는다** — 테넌트 평면과 달리 플랫폼 `POST /probe` 는 apiKey 생략을 아예 허용하지 않는다
  // (설계서 「프로브」 절 Ruling #24, `PlatformAiCredentialController` javadoc: "플랫폼은 상위
  // 평면이 없어 빌려줄 상위가 없다"). 저장된 키가 있어도 **지금 타이핑한** 값이 없으면 버튼은
  // 비활성이다 — 폴백을 허용하면 항상 400 으로 끝나는 막다른 버튼이 된다.
  const canLoadModels =
    (payload.baseURL ?? '').trim() !== '' && (secretInputs.apiKey ?? '').trim() !== '';

  const loadModels = useCallback(async () => {
    setModelsError(null);
    const body: PlatformAiCredentialProbeRequest = {
      baseURL: payload.baseURL ?? '',
      apiKey: (secretInputs.apiKey ?? '').trim(),
    };
    try {
      const { data } = await settingsApi.probeAiCredential(body);
      if (data.ok) {
        setModels(data.models);
      } else {
        setModels(null);
        setModelsError(data.message ?? '연결을 확인하지 못했습니다.');
      }
    } catch (err) {
      setModels(null);
      setModelsError(
        (axios.isAxiosError(err) ? serverMessage(err) : undefined) ?? '연결을 확인하지 못했습니다.',
      );
    }
  }, [payload.baseURL, secretInputs.apiKey]);

  // 지금 상태로 저장하면 서버에 실제로 나갈 `ai.model` 값(Ruling #52) — opencode 면 맨
  // id(`model`)에 **저장 시점의** `payload.providerId` 로 접두어를 조립하고, 그 외 유형은
  // `model` 자체가 곧 저장값이다(접두어 개념이 없다). `hasUnsavedInput`/`doSave` 양쪽이 이
  // 값을 통해서만 "모델이 바뀌었는가"를 판정한다 — `onChange`/`onValueChange` 어디서도 접두어를
  // 조립하지 않으므로, 입력 순서(모델을 먼저 타이핑하든 공급자를 먼저 고르든)가 결과에 영향을
  // 주지 않는다.
  //
  // <b>"손대지 않았으면 원본을 그대로 돌려준다"(fix round 2 정정).</b> 처음엔 opencode 일 때
  // 무조건 재조립했는데, 그러면 <b>아무것도 안 건드려도</b> 재조립한 문자열이 DB 원본과 문자
  // 그대로 다르면(예: 자격증명이 opencode 인데 `ai.model` 이 옛날부터 접두어 없는 flat 값으로
  // 남아 있던 픽스처/실사용 사례) "모델이 바뀌었다"고 오판해 불필요한 `PUT /settings` 를
  // 냈다 — 자격증명만 저장하려던 시나리오에서 그 PUT 이 응답을 기다리다 통째로 막혔다(실측,
  // e2e 로 잡았다). "손댔다"의 기준은 둘뿐이다: (1) 모델 텍스트 자체를 고쳤다, (2) opencode
  // 인 채로 공급자를 바꿨다(같은 맨 id 라도 접두어가 달라져야 한다). "opencode 로 막
  // 들어왔다/나갔다" 자체는 별도 조건으로 넣지 않는다 — 겉으론 세 번째 이유처럼 보이지만
  // 실제로는 (1)/(2) 로 이미 전부 덮인다: 들어올 때는 공급자를 반드시 고르게 되어 있어
  // (2)가 잡고(공급자를 고르지 않은 채라면 `withProviderPrefix` 가 빈 접두어를 그대로
  // 통과시켜 애초에 재조합해도 결과가 같다 — "손댔다"로 쳐도 안 쳐도 무해하다), 나갈 때는
  // 위 `setAgentType` 이 모델을 비워 `model !== 원본 bare` 가 자동으로 성립해 (1)이 잡는다.
  // 따로 검사를 더했다가 실제로는 결과를 절대 못 바꾸는 죽은 조건이 되는 걸 막으려고 뺐다
  // (뮤테이션 검사로 직접 확인했다 — 셋째 조건을 넣고 지워도 어떤 테스트도 갈리지 않았다).
  // 둘 다 아니면 재조립하지 않고 서버 원본을 그대로 돌려준다 — 재조합 결과가 우연히 원본과
  // 문자 그대로 다를 수 있다는 이유만으로 "바뀌었다"고 말하지 않는다.
  const modelProviderTouched =
    agentType === 'opencode' && (payload.providerId ?? '') !== (original.payload.providerId ?? '');
  const modelTextTouched = model !== stripProviderPrefix(originalModel, original.payload.providerId ?? '');
  const modelInputsTouched = modelProviderTouched || modelTextTouched;
  const effectiveModel = modelInputsTouched
    ? agentType === 'opencode'
      ? withProviderPrefix(model, payload.providerId ?? '')
      : model
    : originalModel;

  // 이 fieldset 이 갖는 두 저장 대상(자격증명 자체 / 모델) 중 <b>자격증명만</b> 봤을 때의
  // 미저장 여부(fix round 2) — "인증 확인" 버튼의 잠금 조건이 이걸 써야 한다. `hasUnsavedInput`
  // (아래, 모델 포함)을 그대로 썼더니 Claude 모델만 바꿔도(자격증명은 그대로인데) 버튼이
  // 잠겼다 — 그 가드의 원래 목적("타이핑 중인 낡은 자격증명을 검증하지 않는다")과 무관한
  // 이유로 잠기는 것이었다.
  const hasUnsavedCredentialInput =
    agentType !== original.agentType ||
    CREDENTIAL_FIELDS[agentType]
      .filter((field) => field.plane === 'payload')
      .some((field) => (payload[field.name] ?? '') !== (original.payload[field.name] ?? '')) ||
    Object.values(secretInputs).some((value) => value.trim() !== '');

  const hasUnsavedInput = hasUnsavedCredentialInput || effectiveModel !== originalModel;

  // 유형 전환 경고 게이트. `original.secretFieldNames.length > 0` 을 요구하는 이유: 방금
  // 설치돼 아직 아무 비밀도 저장하지 않은 플랫폼(원본 유형이 기본값 sdk, 비밀 없음)에서
  // 유형을 처음 고르는 것은 "잃을 것"이 없다 — 그런데도 경고를 띄우면 "지금 막 처음 고르는
  // 값인데 무언가 사라진다"는 거짓 경고가 된다(firehub-web `hasTypeChangedFromSaved` 의
  // `tenantOwned` 가드와 같은 목적을 플랫폼에는 `tenantOwned` 가 없으므로 이 조건으로 대신한다).
  const typeChanged = original.secretFieldNames.length > 0 && agentType !== original.agentType;

  const doSave = useCallback(async (): Promise<void> => {
    // 순서: 모델 먼저, 자격증명 다음(Task 13 fix round 1 자문 리뷰 — firehub-web
    // `SettingsPage.tsx` 의 같은 순서·같은 이유를 그대로 따른다). opencode 저장 시 서버
    // `OpencodeCredentialValidation.checkProviderConsistency`(`PlatformAiCredentialController`
    // 도 호출한다)가 **지금 DB 에 저장된 `ai.model`**을 읽어 요청 `providerId` 와 대조한다.
    // 자격증명을 먼저 저장하면 그 비교가 "새 providerId vs 아직 안 바뀐 옛 ai.model" 이 되어,
    // 정당한 "공급자 전환 + 모델 동시 변경" 조합이 400 으로 막힌다. 모델을 먼저 쓰면 그 시점에
    // 이미 새 값이 확정되어 있어 뒤이은 자격증명 검증이 항상 최신 상태와 비교한다(반대 방향 —
    // opencode→sdk — 은 이 검사가 opencode 요청에만 걸리므로 순서와 무관하게 안전하다).
    const finalModel = effectiveModel;
    // 빈 문자열은 "아직 고르지 않았다"이지 "지워라"가 아니다(fix round 2). 서버 `ai.model` 은
    // 검증이 없는 free-form 키다(`SettingsService`: "validated by frontend dropdown") — 빈
    // 문자열을 그대로 보내면 서버가 아무 불평 없이 모델을 완전히 비워 버린다. opencode 를
    // 떠나며 모델 칸을 비웠는데(위 `setAgentType`) 아직 새로 고르지 않은 채로 저장을 누르면,
    // 무엇을 보낼지 모르는 채로 지우는 것보다 예전 값을 그대로 두는 편이 안전하다 — 화면은
    // 여전히 빈 Select 로 "아직 안 골랐다"를 보여주므로 사용자가 그 사실을 놓치지 않는다.
    const modelChanged = finalModel !== '' && finalModel !== originalModel;
    if (modelChanged) {
      try {
        await settingsApi.update({ 'ai.model': finalModel });
        // fix round 2 — 모델 PUT 이 성공하는 즉시 확정한다(자격증명 PUT 을 기다리지 않는다).
        // 예전엔 두 PUT 이 **모두** 성공한 뒤에만 `originalModel` 을 갱신했는데, 그러면 모델은
        // 저장됐지만 자격증명 PUT 이 실패하는 부분 실패에서 `originalModel` 이 낡은 채로
        // 남는다 — 그 상태로 `되돌리기(reset)` 를 누르면 서버에 더는 없는 옛 모델 값을
        // "되돌린" 값이라며 보여주게 된다.
        setOriginalModel(finalModel);
      } catch (err) {
        toast.error(serverMessage(err) ?? '모델 저장에 실패했습니다.');
        throw err;
      }
    }

    const fields = CREDENTIAL_FIELDS[agentType];
    const payloadOut: Record<string, string> = {};
    fields
      .filter((field) => field.plane === 'payload')
      .forEach((field) => {
        payloadOut[field.name] = payload[field.name] ?? '';
      });
    const secretOut: Record<string, string> = {};
    fields
      .filter((field) => field.plane === 'secret')
      .forEach((field) => {
        const typed = secretInputs[field.name];
        // 빈 값 = 유지(생략). 여기서 ''를 실으면 서버 계약상 "삭제"가 되어, 사용자가 손대지
        // 않은 비밀이 저장 한 번에 사라진다 — 플랫폼 값이므로 그 삭제는 이 값을 상속하는
        // **모든 테넌트**에 영향을 준다. 이 훅은 빈 문자열을 절대 보내지 않는다(의도적).
        if (typed && typed.trim() !== '') secretOut[field.name] = typed;
      });
    try {
      await settingsApi.putAiCredential({ agentType, payload: payloadOut, secret: secretOut });
    } catch (err) {
      toast.error(serverMessage(err) ?? '저장에 실패했습니다.');
      throw err;
    }
    try {
      await fetchAndApply();
      // 모델의 `originalModel` 확정은 이미 위(모델 PUT 성공 직후)에서 끝났다 — 여기서는
      // 페이지의 react-query 캐시를 건드리지 않는다는 것만 남겨 둔다(파일 헤더 주석의 실측
      // 버그: 예전엔 여기서 무효화를 불러 나머지 탭의 미저장 편집을 지웠다).
      setStaleNotice(null);
      toast.success('AI 자격증명·모델을 저장했습니다.');
      // opencode 는 애초에 이 배지 자체가 없다(Anthropic 인증 개념이 없다) — 유형이 막
      // opencode 로 바뀐 경우, 이전 유형의 낡은 "✓ 인증됨" 배지가 남지 않도록 명시적으로
      // 비운다(firehub-web `SettingsPage.performSave` 와 같은 이유). 그 외 세 유형은 저장
      // 직후 최신 상태로 다시 확인한다 — 기다리지 않는다(fire-and-forget, 저장 완료 자체를
      // 지연시키지 않는다).
      if (agentType !== 'opencode') void verifyAuth();
      else setAuthStatus(null);
    } catch {
      const message =
        '저장은 완료됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';
      setStaleNotice(message);
      toast.error(message);
    }
  }, [agentType, payload, secretInputs, effectiveModel, originalModel, fetchAndApply, verifyAuth]);

  const reset = useCallback(() => {
    setAgentTypeState(original.agentType);
    setPayloadState(original.payload);
    setSecretInputsState({});
    // `model` 은 맨 id 로 되돌린다 — `originalModel`(서버 원형, opencode 면 접두어 포함)을
    // `original.payload.providerId` 로 벗긴다. 유형을 opencode 로 바꿨다가 저장 없이
    // 되돌리는 경우, `original.payload` 에는 애초에 `providerId` 키가 없어(원본이 Claude
    // 계열이었다면) `?? ''`로 안전하게 처리된다.
    setModelState(stripProviderPrefix(originalModel, original.payload.providerId ?? ''));
    setModels(null);
    setModelsError(null);
  }, [original, originalModel]);

  return {
    isLoading,
    loadFailed,
    staleNotice,
    agentType,
    setAgentType,
    payload,
    setPayloadField,
    secretInputs,
    setSecretInput,
    secretFieldNames: visibleSecretFieldNames,
    savedAgentType: original.agentType,
    models,
    modelsError,
    canLoadModels,
    loadModels,
    model,
    // setState 를 그대로 내준다 — 예전에는 `setModelState(next)` 만 하는 래퍼를 한 겹 끼웠는데,
    // 같은 파일의 다른 setter 들(부수 로직이 있는 것들)과 생김새가 같아 "여기에도 무언가 더
    // 있나" 를 매번 확인하게 만들었다.
    setModel: setModelState,
    modelDescription,
    authStatus,
    isVerifying,
    verifyAuth,
    hasUnsavedInput,
    hasUnsavedCredentialInput,
    typeChanged,
    doSave,
    reset,
  };
}

export type AiCredentialSectionState = ReturnType<typeof useAiCredentialSection>;
