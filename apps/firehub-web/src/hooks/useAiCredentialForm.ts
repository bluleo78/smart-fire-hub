import axios from 'axios';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../api/settings';
import type { AgentType } from '../lib/ai-credential';
import { AGENT_TYPES, CREDENTIAL_FIELDS } from '../lib/ai-credential';
import { hasTypeChangedFromSaved } from '../lib/ai-credential-screen';
import { extractApiError } from '../lib/api-error';
import type {
  AiCredentialProbeRequest,
  AiCredentialProbeResponse,
  AiCredentialResponse,
  AiCredentialUpsertPayload,
} from '../types/settings';

/**
 * 최초 조회가 오기 전 화면이 잠깐 보여줄 기본값. `sdk` 인 이유는 백엔드
 * `AiCredentialService.DEFAULT_AGENT_TYPE` 과 같다 — 테넌트에 자격증명 행이 없을 때 서버가
 * 돌려주는 빈 문서(`configured:false`)의 유형과 화면 초기값을 맞춰 둔다.
 */
const DEFAULT_AGENT_TYPE: AgentType = 'sdk';

/** payload 필드가 문자열이 아닌 값을 담고 있으면 무시한다 — 이 화면이 정의한 필드는 전부 text/select 이고, 서버 `Map<String,Object>` 가 줄 수 있는 다른 타입을 화면이 렌더할 방법이 없다. */
function toStringPayload(raw: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {};
  Object.entries(raw).forEach(([key, value]) => {
    if (typeof value === 'string') out[key] = value;
  });
  return out;
}

/** 저장 직후 서버가 확정한 값 — dirty 판정과 `canLoadModels` 의 "저장된 키" 가드에 쓴다. */
interface OriginalSnapshot {
  agentType: AgentType;
  payload: Record<string, string>;
  /** 테넌트에 자격증명 행이 실제로 있는지(서버 `configured`). 미설정 안내·유형 전환 경고에 쓴다. */
  configured: boolean;
}

/**
 * 최초 조회 전(혹은 실패·403 후) 초기값. `configured:false` 는 "서버가 없다고 답했다"가 아니라
 * "아직 모른다"이다 — 그래서 화면은 `loadFailed`/`isLocked` 일 때 이 값으로 미설정 안내를 그리지
 * 않는다(`AiCredentialFieldset` 참고).
 */
const EMPTY_ORIGINAL: OriginalSnapshot = {
  agentType: DEFAULT_AGENT_TYPE,
  payload: {},
  configured: false,
};

/**
 * 폼이 부르는 엔드포인트 묶음. 채팅(`ai.credential`)과 분류(`ai.classify_credential`, #707)가 같은
 * 문서 구조라 폼 상태 기계를 그대로 공유하고 경로만 갈아 끼운다.
 */
export interface AiCredentialEndpoints<R extends AiCredentialResponse = AiCredentialResponse> {
  get: () => Promise<{ data: R }>;
  put: (data: AiCredentialUpsertPayload) => Promise<unknown>;
  probe: (data: AiCredentialProbeRequest) => Promise<{ data: AiCredentialProbeResponse }>;
}

/**
 * 채팅 자격증명 엔드포인트(기본값). 화살표로 감싸 호출 시점에 `settingsApi` 를 읽는다 — 테스트가
 * `settingsApi` 를 모듈 mock 으로 갈아 끼워도 그대로 따라간다.
 */
export const CHAT_CREDENTIAL_ENDPOINTS: AiCredentialEndpoints = {
  get: () => settingsApi.getAiCredential(),
  put: (data) => settingsApi.putAiCredential(data),
  probe: (data) => settingsApi.probeAiCredential(data),
};

/** `useAiCredentialForm` 선택 인자 — 전부 생략하면 기존 채팅 탭 동작과 같다. */
export interface UseAiCredentialFormOptions<R extends AiCredentialResponse> {
  /** 엔드포인트. 생략하면 채팅 자격증명. */
  api?: AiCredentialEndpoints<R>;
  /** 거짓이면 최초 조회를 미룬다 — 분류 탭은 처음 열릴 때만 조회한다(다른 탭 화면에 요청을 만들지 않게). */
  enabled?: boolean;
  /** 조회 응답마다 불린다 — 분류 탭이 이 문서 밖의 값(모델)을 받아 가는 통로. 참조를 안정적으로 넘길 것. */
  onResponse?: (data: R) => void;
  /** 최초 조회 실패 토스트 문구. */
  loadErrorMessage?: string;
}

export interface UseAiCredentialFormResult {
  /** 최초 조회가 아직 끝나지 않았다. */
  isLoading: boolean;
  /** 최초 조회가 실패했다(403 이 아닌 다른 오류 — 네트워크 오류 등). */
  loadFailed: boolean;
  /**
   * 조회 권한이 없다 — 최초 GET 이 <b>403</b> 으로 응답했다(라우트 권한 `ai:settings` 없음).
   * 이 전용 엔드포인트 응답에는 필드 단위 편집 가능 플래그가 없어, "편집 불가"의 유일한 실재
   * 신호가 이것이다. 잠김은 고장이 아니라 정상적인 권한 상태라 오류 토스트를 띄우지 않고,
   * 그 외 실패는 `loadFailed` 로 가른다. 이때 폼 값은 전부 초기값(모름)이다.
   */
  isLocked: boolean;
  /**
   * 테넌트에 자격증명이 저장돼 있는가(마지막 GET/저장 시점, 서버 `configured`). 테넌트 전용
   * 자격증명이므로 `false` 면 이 조직의 AI 기능은 동작하지 않는다 — 화면이 그 사실을
   * 안내한다. <b>`loadFailed`/`isLocked` 일 때는 의미가 없다</b>(초기값일 뿐이다).
   */
  configured: boolean;
  agentType: AgentType;
  /** 서버와 마지막으로 동기화된(=저장된) 유형 — 유형 전환 경고·저장 확인 다이얼로그의 비교 기준. */
  savedAgentType: AgentType;
  /** 저장된 자격증명이 있고 유형이 그것과 달라졌는가 — 저장 시 이전 유형의 비밀이 폐기된다(설계서 §193). */
  typeChanged: boolean;
  /**
   * 유형 전환. <b>이전 유형의 `payload`/`secretInputs` 를 전부 비운다.</b> 서버가 유형이
   * 바뀌면 payload/secret 을 통째로 새로 시작하는 것과 같은 규칙이다(`AiCredentialService#save`
   * javadoc: "agentType 이 바뀌면 payload 도 통째로 새로 시작한다" / "secret 을 통째로 비우고
   * 새로 받은 것만 넣는다"). 이름이 겹치는 필드(`apiKey`)가 있어도 예외 없이 비운다 —
   * `Sdk.apiKey`(Anthropic)와 `Opencode.apiKey`(OpenAI 호환)는 이름만 같고 다른 비밀이라,
   * 남겨두면 화면이 "이어지는 값"처럼 보여주는 거짓 신호가 된다.
   */
  setAgentType: (agentType: AgentType) => void;
  /** 현재 유형의 `payload`(평문) 필드 값. 서버 필드명을 그대로 키로 쓴다. */
  payload: Record<string, string>;
  /**
   * payload 필드 하나를 갱신한다. `baseURL` 을 바꾸면 이미 불러온 모델 목록을 무효화한다 —
   * "기본 URL 을 고치면 모델 칸이 미로드로 돌아간다"(설계서 "모델 칸 4상태" 절).
   */
  setPayloadField: (name: string, value: string) => void;
  /**
   * 사용자가 지금 타이핑 중인 비밀 값. <b>빈 값 = 유지</b> — 저장 시 이 맵에서 비어 있지 않은
   * 값만 요청에 싣는다(생략된 필드는 서버가 "현재 값 유지"로 해석한다, PUT 계약). 서버 마스크를
   * 절대 시드하지 않는다 — 그런 값 자체가 서버에서 오지 않는다(`secretFieldNames` 는 이름만
   * 준다, 값은 어떤 경로로도 내려오지 않는다).
   */
  secretInputs: Record<string, string>;
  setSecretInput: (name: string, value: string) => void;
  /**
   * "값이 실재하는 비밀 필드" 목록 — <b>지금 화면이 보여주는 유형의 문서에만</b> 한정한다.
   * 서버가 준 원본은 GET 시점(혹은 마지막 저장 시점) 문서 기준이라, 로컬에서 유형만 바꾸면
   * (§`setAgentType`) 원본은 이전 유형 문서의 비밀 이름이 되어 새 유형 폼에 "현재 값이 설정되어
   * 있습니다"를 잘못 붙이게 된다. 그래서 `agentType === 원본 agentType` 일 때만 원본을 내주고
   * 그 밖에는 빈 배열이다 — 화면이 이 판단을 다시 하지 않아도 된다.
   */
  secretFieldNames: string[];
  models: string[] | null;
  /**
   * opencode 모델 목록을 불러온다. <b>내부적으로 `canLoadModels` 를 강제하지 않는다</b> — 그
   * 판정은 화면이 버튼 `disabled` 로 미리 막는 UI 게이트이고, 이 함수 자체는 "지금 이
   * baseURL/키로 프로브를 실행하라"는 명령이다. 가드를 훅 안에 중복해서 두면 한쪽만 고치는
   * 사고가 난다 — 권위는 `canLoadModels` 하나다.
   */
  loadModels: () => Promise<void>;
  modelsError: string | null;
  /**
   * [모델 불러오기] 활성 조건 — 설계서 "화면" 절: "기본 URL 이 있고, 요청에 실을 키가 있거나
   * `secretFieldNames` 에 `apiKey` 가 있을 때".
   *
   * "저장된 키" 쪽은 위 `secretFieldNames`(이미 유형으로 걸러진 값)에 `apiKey` 가 있고, <b>거기에
   * 더해</b> 기본 URL 이 저장된 문서의 것과 같을 때만 참이다. 프로브가 `apiKey` 를 생략하면 서버는
   * 테넌트에 저장된 키를 재사용하는데(`OpencodeProbeService`), 유형을 로컬에서만 바꾼 경우엔
   * 저장된 문서가 이전 유형이라 그 키를 재사용하면 안 된다(유형 필터가 막는다).
   *
   * <b>기본 URL 조건이 필요한 이유</b>: 스펙 "프로브" 절은 "baseURL 이 저장된 값과 다르면
   * apiKey 를 요청에 반드시 포함해야 한다"고 규정하고, 서버는 이것을 `MSG_BASE_URL_MISMATCH`
   * → 400 으로 강제한다. 저장된 키가 있어도 사용자가 기본 URL 만 고친 상태에서 키를 생략한
   * 프로브를 보내면 그 400 을 그대로 맞는다 — 버튼이 활성인데 누르면 늘 실패하는 막다른 길이다.
   */
  canLoadModels: boolean;
  /**
   * 유형·payload·비밀 입력 중 <b>아직 저장하지 않은 변경</b>이 있는가. 저장 확인 다이얼로그가
   * 페이지의 "저장" 활성화·dirty 보고·이탈 가드와 "인증 확인" 버튼 잠금에 쓴다.
   */
  hasUnsavedInput: boolean;
  /**
   * 쓰기(PUT)가 실제로 성공했는지 알려준다(Ruling #42, fix round 1). 재조회
   * (`fetchAndApply`)가 실패해도 쓰기 자체가 성공했으면 `true` 다 — 호출자가 알아야 하는 것은
   * "서버에 반영됐는가"이지 "화면이 최신인가"가 아니다(후자는 `staleNotice` 가 별도로 말한다).
   *
   * <b>페이지가 이 값으로 하는 일</b>: 저장이 실제로 성공했을 때만 `verifyAuth()` 를 불러야
   * 한다 — 실패한 쓰기 뒤에 인증 확인을 돌리면 여전히 낡은(그러나 여전히 유효할 수 있는) 자격
   * 증명을 검사해 사용자를 혼란스럽게 한다. 이전에는 이 값이 없어(반환 타입이 `void`) 무조건
   * 다시 불렀다.
   */
  save: () => Promise<boolean>;
  /**
   * 저장은 성공했지만 재조회가 실패했을 때 세우는 지속 안내. SMTP/AI 다른 탭과 같은 어휘를
   * 쓴다 — 같은 사건에 화면마다 다른 말을 쓰면 사용자가 둘을 다른 사건으로 읽는다.
   */
  staleNotice: string | null;
  /**
   * 로컬 편집(유형·payload·비밀 입력)을 마지막으로 <b>서버와 동기화된 상태</b>로
   * 되돌린다(Ruling #41, fix round 1) — 네트워크 호출 없이, `original` 스냅샷으로 되돌아간다.
   * "되돌리기" 버튼이 동작 설정 6키만 되돌리고 자격증명은 그대로 남겨두면, 사용자에게는 한
   * 화면·한 버튼인데 절반만 되돌아가는 것이 설명되지 않는다 — 페이지가 `handleReset()`(동작
   * 설정)과 함께 이 함수도 불러야 한다.
   *
   * 비밀 입력은 애초에 원본이 없으므로(서버가 값을 절대 안 돌려준다) 빈 맵으로, 모델 로딩
   * 상태(`models`/`modelsError`)도 지금 편집과 무관해졌으니 함께 비운다.
   */
  reset: () => void;
  /**
   * 서버 상태로 다시 시드한다(최초 조회와 같은 경로·같은 실패 처리). 분류 탭의 "설정 해제"
   * 뒤처럼, 폼 밖의 쓰기가 서버 문서를 바꿨을 때 쓴다.
   */
  reload: () => Promise<void>;
}

/**
 * AI 자격증명(`ai.credential`) 전용 폼 상태 기계 — `useSettingsOverrideForm` 의 관용구(로드 →
 * 폼/원본 분리 → 변경분만 전송 → `staleNotice`)를 따르되, <b>비밀은 원본을 갖지 않는다</b>.
 * 서버가 비밀 값을 절대 돌려주지 않으므로(`secretFieldNames` 는 이름만 준다) "바뀌지 않았다"를
 * 표현할 원본 자체가 존재할 수 없다 — 대신 "입력이 비어 있으면 보내지 않는다"(생략=유지)로
 * 같은 효과를 낸다.
 *
 * `useSettingsOverrideForm` 을 재사용하지 않는 이유: 그 훅은 `GET /settings?prefix=` (여러
 * 평면 키의 배열)를 전제하는데, `ai.credential` 은 하나의 JSON 문서를 돌려주는 전용 엔드포인트
 * (Task 7)를 쓴다 — 응답 모양 자체가 다르다(`{agentType, payload, secretFieldNames,
 * configured}` vs `ResolvedSettingResponse[]`).
 *
 * 자격증명은 테넌트 전용이다(#706) — 이 폼은 항상
 * "우리 조직 자격증명" 하나만 편집하고, 저장은 언제나 PUT 이다.
 *
 * `options.api` 로 엔드포인트를 갈아 끼우면 같은 상태 기계를 AI 분류 전용 공급자(#707) 폼에도
 * 쓴다 — 두 자원이 같은 문서 구조라 규칙(비밀 생략=유지, 유형 전환 초기화 등)을 한 곳에 둔다.
 */
export function useAiCredentialForm<R extends AiCredentialResponse = AiCredentialResponse>(
  options: UseAiCredentialFormOptions<R> = {},
): UseAiCredentialFormResult {
  // 기본값은 채팅 엔드포인트다. R 이 기본(AiCredentialResponse)일 때만 성립하는 좁힘이라 단언한다 —
  // 분류 탭처럼 R 을 넓히는 호출부는 반드시 자기 api 를 넘긴다.
  const api = options.api ?? (CHAT_CREDENTIAL_ENDPOINTS as unknown as AiCredentialEndpoints<R>);
  const { enabled = true, onResponse, loadErrorMessage = 'AI 자격증명을 불러오지 못했습니다.' } = options;
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [isLocked, setIsLocked] = useState(false);
  const [staleNotice, setStaleNotice] = useState<string | null>(null);

  const [agentType, setAgentTypeState] = useState<AgentType>(DEFAULT_AGENT_TYPE);
  const [payload, setPayloadState] = useState<Record<string, string>>({});
  const [secretInputs, setSecretInputsState] = useState<Record<string, string>>({});
  const [secretFieldNames, setSecretFieldNames] = useState<string[]>([]);
  const [models, setModels] = useState<string[] | null>(null);
  const [modelsError, setModelsError] = useState<string | null>(null);
  const [original, setOriginal] = useState<OriginalSnapshot>(EMPTY_ORIGINAL);

  /**
   * GET 응답을 모든 폼 상태에 반영한다. 최초 조회와 저장 후 재조회가 둘 다 쓴다 — 두 경로가
   * 각자 시딩 로직을 복제하면 한쪽만 고치는 사고가 난다(이 프로젝트에서 반복된 실수 패턴).
   *
   * <b>비밀 입력(`secretInputs`)을 항상 빈 맵으로 되돌린다.</b> 서버는 비밀 "값"을 절대
   * 돌려주지 않으므로 여기서 시드할 원본이 없다 — 시드할 것이 있다는 착각으로 `secretFieldNames`
   * 의 이름을 플레이스홀더 값으로 채워 넣으면(예: `secretInputs.apiKey = '설정됨'`), 다음
   * `save()` 가 그 플레이스홀더를 <b>진짜 비밀값</b>인 양 그대로 암호화해 저장해 버린다 —
   * 마스크 오염 버그의 이 화면 버전이다.
   */
  const applyResponse = useCallback((data: AiCredentialResponse) => {
    // 알 수 없는 agentType(손으로 고친 행, 롤백된 배포)은 화면 안전을 위해 sdk 로 보인다 —
    // 저장을 누르지 않는 한 실제 문서는 그대로다. 서버 쪽 판정(`resolve()`)은 fail-closed 로
    // 별도로 막혀 있으므로, 여기서의 표시 폴백이 실제 호출 경로에 영향을 주지 않는다.
    const nextAgentType: AgentType = (AGENT_TYPES as readonly string[]).includes(data.agentType)
      ? (data.agentType as AgentType)
      : DEFAULT_AGENT_TYPE;
    const nextPayload = toStringPayload(data.payload);

    setAgentTypeState(nextAgentType);
    setPayloadState(nextPayload);
    setSecretInputsState({});
    setSecretFieldNames(data.secretFieldNames);
    setModels(null);
    setModelsError(null);
    setOriginal({
      agentType: nextAgentType,
      payload: nextPayload,
      configured: data.configured,
    });
  }, []);

  /** 순수 네트워크 호출 — 실패를 <b>호출자에게</b> 던진다. `load`(최초 조회)와 `save`(저장 후
   * 재조회)가 실패를 다르게 다뤄야 해서(전자는 `loadFailed`/`isLocked`, 후자는 `staleNotice`)
   * 상태 갱신과 오류 처리를 분리한다. */
  const fetchAndApply = useCallback(async () => {
    const { data } = await api.get();
    applyResponse(data);
    // 폼 문서 밖의 값(분류 모델 등)은 호출부가 받아 간다.
    onResponse?.(data);
  }, [api, applyResponse, onResponse]);

  const load = useCallback(async () => {
    setIsLoading(true);
    try {
      await fetchAndApply();
      setLoadFailed(false);
      setIsLocked(false);
    } catch (err) {
      if (axios.isAxiosError(err) && err.response?.status === 403) {
        // 서버가 편집 불가로 보고한 유일한 신호 — 위 인터페이스 `isLocked` 주석 참고.
        // 오류 토스트를 띄우지 않는다: 잠김은 고장이 아니라 정상적인 권한 상태다.
        setIsLocked(true);
        setLoadFailed(false);
      } else {
        setLoadFailed(true);
        setIsLocked(false);
        toast.error(loadErrorMessage);
      }
    } finally {
      setIsLoading(false);
    }
  }, [fetchAndApply, loadErrorMessage]);

  // 최초 1회만 조회한다 — `useSettingsOverrideForm` 과 같은 가드다. StrictMode 이중 실행이나
  // `load` 참조 변동으로 재조회가 걸리면 `original` 이 다시 시드되어, 사용자가 입력 중이던
  // payload 편집이 조용히 덮인다. `enabled` 가 거짓인 동안은 미루고, 처음 참이 되는 순간 한 번 조회한다.
  const didInitialLoad = useRef(false);
  useEffect(() => {
    if (!enabled || didInitialLoad.current) return;
    didInitialLoad.current = true;
    void load();
  }, [enabled, load]);

  const setAgentType = useCallback(
    (next: AgentType) => {
      // 같은 값을 다시 고른 것이면 아무 일도 하지 않는다 — Select 가 같은 옵션을 다시 선택해
      // onChange 를 쏘는 경우까지 payload/secret 을 지울 이유가 없다.
      if (next === agentType) return;
      setAgentTypeState(next);
      // 유형이 바뀌면 payload/secret 모두 새로 시작한다 — 위 인터페이스 setAgentType 주석 참고.
      setPayloadState({});
      setSecretInputsState({});
      setModels(null);
      setModelsError(null);
    },
    [agentType],
  );

  const setPayloadField = useCallback((name: string, value: string) => {
    setPayloadState((prev) => ({ ...prev, [name]: value }));
    if (name === 'baseURL') {
      // 기본 URL 이 바뀌면 그 URL 로 불러온 모델 목록은 더 이상 유효하지 않다.
      setModels(null);
      setModelsError(null);
    }
  }, []);

  const setSecretInput = useCallback((name: string, value: string) => {
    setSecretInputsState((prev) => ({ ...prev, [name]: value }));
  }, []);

  /** `secretFieldNames` 의 노출본 — 현재 유형이 원본 문서 유형과 같을 때만(인터페이스 주석 참고). */
  const visibleSecretFieldNames = agentType === original.agentType ? secretFieldNames : [];

  // canLoadModels 의 "저장된 키" 절반 — 유형 불일치는 `visibleSecretFieldNames` 가 이미 걸렀고,
  // 여기서는 기본 URL 일치 조건만 더한다(인터페이스 canLoadModels 주석).
  const hasStoredApiKey =
    (payload.baseURL ?? '') === (original.payload.baseURL ?? '') &&
    visibleSecretFieldNames.includes('apiKey');
  const canLoadModels =
    (payload.baseURL ?? '').trim() !== '' &&
    (Boolean((secretInputs.apiKey ?? '').trim()) || hasStoredApiKey);

  const loadModels = useCallback(async () => {
    setModelsError(null);
    const body: AiCredentialProbeRequest = { baseURL: payload.baseURL ?? '' };
    const typedKey = (secretInputs.apiKey ?? '').trim();
    // 생략하면 서버가 테넌트 저장값을 재사용한다 — 타이핑한 값이 있을 때만 싣는다.
    if (typedKey !== '') body.apiKey = typedKey;
    try {
      const { data } = await api.probe(body);
      if (data.ok) {
        setModels(data.models);
      } else {
        // upstream 실패 — 응답은 200 + ok:false 로 온다(SMTP 연결 테스트 선례).
        setModels(null);
        setModelsError(data.message ?? '모델 목록을 불러오지 못했습니다.');
      }
    } catch (err) {
      // 요청 형태 오류(4xx) — ok:false 로 오지 않고 HTTP 오류로 온다.
      setModels(null);
      setModelsError(extractApiError(err, '모델 목록을 불러오지 못했습니다.'));
    }
  }, [api, payload.baseURL, secretInputs.apiKey]);

  const hasUnsavedInput =
    agentType !== original.agentType ||
    CREDENTIAL_FIELDS[agentType]
      .filter((field) => field.plane === 'payload')
      .some((field) => (payload[field.name] ?? '') !== (original.payload[field.name] ?? '')) ||
    Object.values(secretInputs).some((value) => value.trim() !== '');

  const save = useCallback(async (): Promise<boolean> => {
    // 현재 유형의 필드만 골라 보낸다. 다른 유형의 필드가 로컬 상태에 남아 있어도(정상적으로는
    // setAgentType 이 비우므로 없어야 한다) 여기서 다시 한번 CREDENTIAL_FIELDS 로 걸러 싣지 않는다.
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
        // 빈 값 = 유지(생략). 서버 계약상 ''는 "삭제"라, 실으면 손대지 않은 비밀이 저장 한 번에
        // 사라진다 — 이 폼에는 필드 단위 삭제 제스처가 없으므로 빈 문자열은 절대 보내지 않는다.
        if (typed && typed.trim() !== '') secretOut[field.name] = typed;
      });
    try {
      await api.put({ agentType, payload: payloadOut, secret: secretOut });
    } catch (err) {
      // 서버 400(비밀 필수 규칙 등)의 한국어 메시지를 그대로 보여준다.
      toast.error(extractApiError(err, '저장에 실패했습니다.'));
      return false;
    }

    // 쓰기는 성공했다 — 서버가 확정한 값으로 다시 시드한다(비밀 입력도 함께 비워진다).
    // 재조회가 실패해도 쓰기 자체는 이미 성공했으므로 아래에서 항상 true 를 돌려준다 — 실패는
    // staleNotice 로만 알린다(Ruling #42, fix round 1: save() 는 "쓰기가 성공했는가"를
    // 보고하지 "화면이 최신인가"를 보고하지 않는다).
    try {
      await fetchAndApply();
      setStaleNotice(null);
      toast.success('저장했습니다.');
    } catch {
      // 저장은 성공했고 다시 읽기가 실패했다 — SMTP/AI 다른 탭과 같은 문구.
      const message =
        '저장은 완료됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';
      setStaleNotice(message);
      toast.error(message);
    }
    return true;
  }, [api, agentType, payload, secretInputs, fetchAndApply]);

  /**
   * 로컬 편집을 마지막 `original` 스냅샷으로 되돌린다(Ruling #41, fix round 1) — 인터페이스
   * `reset` 주석 참고. 네트워크 호출 없이 즉시 반영된다.
   */
  const reset = useCallback(() => {
    setAgentTypeState(original.agentType);
    setPayloadState(original.payload);
    setSecretInputsState({});
    setModels(null);
    setModelsError(null);
  }, [original]);

  return {
    isLoading,
    loadFailed,
    isLocked,
    configured: original.configured,
    agentType,
    savedAgentType: original.agentType,
    typeChanged: hasTypeChangedFromSaved(agentType, original.agentType, original.configured),
    setAgentType,
    payload,
    setPayloadField,
    secretInputs,
    setSecretInput,
    secretFieldNames: visibleSecretFieldNames,
    models,
    loadModels,
    modelsError,
    canLoadModels,
    hasUnsavedInput,
    save,
    staleNotice,
    reset,
    // 최초 조회와 같은 경로(`load`)를 그대로 내준다 — 실패 처리(loadFailed/isLocked)도 같다.
    reload: load,
  };
}
