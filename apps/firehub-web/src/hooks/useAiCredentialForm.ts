import axios from 'axios';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../api/settings';
import type { AgentType } from '../lib/ai-credential';
import { AGENT_TYPES, CREDENTIAL_FIELDS } from '../lib/ai-credential';
import { extractApiError } from '../lib/api-error';
import type { AiCredentialProbeRequest, AiCredentialResponse } from '../types/settings';

export type CredentialPlane = 'platform' | 'tenant';

/**
 * 최초 조회가 오기 전 화면이 잠깐 보여줄 기본값. `sdk` 인 이유는 백엔드
 * `AiCredentialService.DEFAULT_AGENT_TYPE` 과 같다 — 문서가 어느 평면에도 전혀 없을 때(마이그레이션
 * 이전 테스트 환경 등) 쓰는 값과 화면 초기값을 맞춰 둔다.
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
  plane: CredentialPlane;
  agentType: AgentType;
  payload: Record<string, string>;
  /** GET 응답이 테넌트 오버라이드에서 왔는지. `canLoadModels` 의 평면 교차 폴백 가드에 쓴다(아래 주석). */
  tenantOwned: boolean;
}

const EMPTY_ORIGINAL: OriginalSnapshot = {
  plane: 'platform',
  agentType: DEFAULT_AGENT_TYPE,
  payload: {},
  tenantOwned: false,
};

export interface UseAiCredentialFormResult {
  /** 최초 조회가 아직 끝나지 않았다. */
  isLoading: boolean;
  /** 최초 조회가 실패했다(403 이 아닌 다른 오류 — 네트워크 오류 등). */
  loadFailed: boolean;
  /**
   * 서버가 편집 불가로 보고했다(설계서 "잠금 상태" 절: "서버가 편집 불가로 보고하면 라디오
   * 둘 다 비활성 + `PlatformLockedNote`").
   *
   * <b>오늘은 이 값이 절대 `true` 가 되지 않는다 — 이것은 알려진 계약 간극이다.</b>
   * `ai.credential` 은 `SettingsOverridePolicy.TENANT_OVERRIDABLE` 에 조건 없이 들어 있고
   * (스펙 §"저장 계약" · 백엔드 `SettingsOverridePolicy` 주석), 이 전용 엔드포인트의 응답
   * (`AiCredentialService.AiCredentialView` = `{agentType, payload, secretFieldNames,
   * tenantOwned}`)에는 다른 설정 키가 갖는 `tenantEditable` 같은 필드 단위 편집 가능 플래그가
   * 아예 없다. 그래서 "서버가 편집 불가를 보고하는" 유일한 실재 경로는 라우트 권한
   * (`ai:settings`) 자체가 없어 GET 이 <b>403</b> 으로 응답하는 경우뿐이다 — 이 훅은 최초
   * 조회가 403 이면 `isLocked=true`, 그 외 실패는 `loadFailed=true` 로 가른다(일반 오류
   * 토스트를 띄우지 않는다 — 잠김은 오류가 아니라 정상적인 권한 상태다).
   *
   * Task 11/리뷰어가 이 간극을 알아야 한다: 나중에 키 단위(혹은 테넌트별) 편집 제한이 생기면
   * 이 판정부터 다시 설계해야 한다.
   */
  isLocked: boolean;
  plane: CredentialPlane;
  /**
   * 서버가 확정한(마지막 GET/저장 시점의) 소유 평면 — `plane` 과 다른 개념이다. `plane` 은
   * 라디오의 <b>폼 상태</b>라 사용자가 저장하지 않고 토글하면 즉시 갈라진다(예: 테넌트가 직접
   * 설정 중이라 `tenantOwned=true` 인데, 사용자가 "플랫폼 설정을 사용" 라디오만 눌러 아직
   * 저장 전이면 `plane==='platform'` 이지만 `tenantOwned` 는 여전히 `true` 다).
   *
   * Task 11 의 "플랫폼 설정을 사용" 화면이 "지금 적용 중인 값" 정의 목록을 그릴 때 이 값이
   * 필요하다 — GET 이 준 `payload`/`secretFieldNames` 는 <b>두 평면 해석 결과</b>(테넌트 오버라이드가
   * 있으면 그 값, 없으면 플랫폼 값)라, `tenantOwned=true` 인 상태에서 그 값을 "플랫폼 값"이라고
   * 그리면 거짓말이 된다. 그 경우엔 목록을 그리지 말고 "저장하면 플랫폼 값으로 되돌아갑니다"
   * 처럼 예고만 해야 한다 — 실제 플랫폼 값은 이 엔드포인트(테넌트 우선 해석)로는 <b>알 수
   * 없다</b>. "플랫폼에도 자격증명이 없으면" 안내(설계서)도 `tenantOwned=false` 일 때만 그 GET
   * 결과를 근거로 정직하게 판단할 수 있다.
   */
  tenantOwned: boolean;
  /**
   * 라디오 선택. <b>폼 상태다 — 즉시 DELETE 를 부르지 않는다.</b> 평문 비밀은 서버가 절대
   * 돌려주지 않으므로, 실수로 한 번 누른 라디오의 대가가 "모든 비밀 재입력"이 되면 안 된다.
   * 실제 삭제는 `save()` 가 `plane === 'platform'` 일 때만 일으킨다.
   */
  setPlane: (plane: CredentialPlane) => void;
  agentType: AgentType;
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
   * "값이 실재하는 비밀 필드" 목록 — <b>지금 화면이 보여주고 있는 유형·평면의 문서에만</b>
   * 한정한다(Ruling #38). 서버가 준 원본은 GET 시점(혹은 마지막 저장 시점)의 문서 기준이라,
   * 로컬에서 유형을 바꾸거나(§`setAgentType`) `plane` 라디오를 토글하면(§`setPlane`) 화면이
   * 지금 보여주는 정체성과 이 목록이 가리키는 문서의 정체성이 갈라진다. 원본을 그대로 내주면
   * 다음 두 경우에 거짓을 말하게 된다:
   *
   * - <b>유형만 로컬에서 바꾼 경우</b>: 원본은 이전 유형 문서의 비밀 이름이라, 새 유형 폼에
   *   "현재 값이 설정되어 있습니다"를 잘못 붙이게 된다.
   * - <b>`tenantOwned=false` 인데 `plane==='tenant'`(플랫폼 상속 중에 "직접 설정" 폼을 막 연
   *   경우)</b>: 원본은 플랫폼 문서의 비밀 이름이라, 테넌트가 아직 아무것도 저장하지 않은
   *   자기 문서에 "설정됨"이라고 잘못 붙이게 된다(반대 방향, `tenantOwned=true` 인데
   *   `plane==='platform'` 도 같은 이유로 걸러진다 — 그 경우는 `tenantOwned` 필드로 Task 11 이
   *   판단할 수도 있지만, 여기서도 같은 규칙 하나로 함께 걸러 화면이 두 가지 판단을 따로
   *   조합할 필요가 없게 한다).
   *
   * 그래서 <b>`agentType === 원본 agentType` 이고 `plane` 이 원본 문서의 실제 소유 평면과
   * 일치할 때만</b> 서버 원본을 그대로 내준다. 그 밖에는 빈 배열이다 — Task 11 이 이 판단을
   * 다시 하지 않아도 되게, 훅이 이미 "지금 보여줘도 되는" 목록으로 걸러 내준다.
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
   * <b>"저장된 키가 있다"를 `secretFieldNames.includes('apiKey')` 하나로만 판정하지 않는다.</b>
   * `secretFieldNames` 는 GET 이 해석한 <b>지금 적용 중인</b> 자격증명 기준이라, 테넌트가
   * 플랫폼 상속 중(`tenantOwned=false`)에 "직접 설정" 폼을 막 연 상태에서도 플랫폼의 키 이름을
   * 그대로 보여준다. 그런데 프로브가 `apiKey` 를 생략했을 때 재사용하는 "저장된 값"은
   * `OpencodeProbeService`/`AiCredentialService#tenantOpencodeCredential` 가 <b>테넌트 행만</b>
   * 본다(평면 교차 폴백 금지 — 플랫폼 키가 테넌트가 지정한 임의 baseURL 로 새는 것을 막는
   * 서버 쪽 방어, 설계서 "프로브" 절 "평면 교차 폴백 금지"). `tenantOwned` 를 확인하지 않고
   * 이 조건만 보면 버튼이 활성인데 누르면 항상 400 이 나는 막다른 길이 된다(같은 절:
   * "`tenantOwned=false` 인데 secret 을 생략했으면 400"). 유형을 로컬에서만 바꾸고 아직
   * 저장하지 않은 경우도 같은 이유로 제외한다 — 서버에 저장된 문서는 <b>이전</b> 유형이라, 그
   * 문서의 `apiKey` 를 지금 선택한(아직 저장 안 한) 유형의 키로 재사용하면 안 된다.
   *
   * 그래서 실제 가드는 위 `secretFieldNames`(이미 정체성으로 걸러진 값, Ruling #38) 에
   * `apiKey` 가 있고, <b>거기에 더해</b> 기본 URL 이 저장된 문서의 것과 같을 때다(Ruling #37).
   *
   * <b>기본 URL 조건이 필요한 이유</b>: 스펙 "프로브" 절은 "baseURL 이 저장된 값과 다르면
   * apiKey 를 요청에 반드시 포함해야 한다"고 규정하고, 서버는 이것을 `MSG_BASE_URL_MISMATCH`
   * → 400 으로 강제한다. 저장된 키가 있어도(유형·평면이 일치해도) 사용자가 기본 URL 만 고친
   * 상태에서 키를 생략한 프로브를 보내면 그 400 을 그대로 맞는다 — Ruling #35 가 고친 것과
   * 같은 모양의 막다른 버튼이 기본 URL 변경 경로로도 열려 있었다(같은 스펙 절의 이웃한
   * 문장이다).
   */
  canLoadModels: boolean;
  /**
   * 유형·payload·비밀 입력 중 <b>아직 저장하지 않은 변경</b>이 있는가. 저장 확인 다이얼로그가
   * "미저장 입력이 있으면 그 사실을 문구에 더한다"(설계서)에 쓴다.
   *
   * <b>`plane` 라디오 자체는 포함하지 않는다.</b> 라디오 전환이 곧 저장 확인 다이얼로그를 여는
   * 계기라, 라디오만 만지고 아무 필드도 입력하지 않았는데 "입력이 사라집니다"류 문구까지
   * 덧붙이면 매번 뜨는 잡음이 된다.
   */
  hasUnsavedInput: boolean;
  /**
   * 쓰기(PUT/DELETE)가 실제로 성공했는지 알려준다(Ruling #42, fix round 1). 재조회
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
   * 로컬 편집(유형·payload·비밀 입력·라디오)을 마지막으로 <b>서버와 동기화된 상태</b>로
   * 되돌린다(Ruling #41, fix round 1) — 네트워크 호출 없이, `original` 스냅샷으로 되돌아간다.
   * "되돌리기" 버튼이 동작 설정 6키만 되돌리고 자격증명은 그대로 남겨두면, 사용자에게는 한
   * 화면·한 버튼인데 절반만 되돌아가는 것이 설명되지 않는다 — 페이지가 `handleReset()`(동작
   * 설정)과 함께 이 함수도 불러야 한다.
   *
   * 비밀 입력은 애초에 원본이 없으므로(서버가 값을 절대 안 돌려준다) 빈 맵으로, 모델 로딩
   * 상태(`models`/`modelsError`)도 지금 편집과 무관해졌으니 함께 비운다.
   */
  reset: () => void;
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
 * tenantOwned}` vs `ResolvedSettingResponse[]`).
 */
export function useAiCredentialForm(): UseAiCredentialFormResult {
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [isLocked, setIsLocked] = useState(false);
  const [staleNotice, setStaleNotice] = useState<string | null>(null);

  const [plane, setPlaneState] = useState<CredentialPlane>('platform');
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
    const nextPlane: CredentialPlane = data.tenantOwned ? 'tenant' : 'platform';
    const nextPayload = toStringPayload(data.payload);

    setPlaneState(nextPlane);
    setAgentTypeState(nextAgentType);
    setPayloadState(nextPayload);
    setSecretInputsState({});
    setSecretFieldNames(data.secretFieldNames);
    setModels(null);
    setModelsError(null);
    setOriginal({
      plane: nextPlane,
      agentType: nextAgentType,
      payload: nextPayload,
      tenantOwned: data.tenantOwned,
    });
  }, []);

  /** 순수 네트워크 호출 — 실패를 <b>호출자에게</b> 던진다. `load`(최초 조회)와 `save`(저장 후
   * 재조회)가 실패를 다르게 다뤄야 해서(전자는 `loadFailed`/`isLocked`, 후자는 `staleNotice`)
   * 상태 갱신과 오류 처리를 분리한다. */
  const fetchAndApply = useCallback(async () => {
    const { data } = await settingsApi.getAiCredential();
    applyResponse(data);
  }, [applyResponse]);

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
        toast.error('AI 자격증명을 불러오지 못했습니다.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [fetchAndApply]);

  // 최초 1회만 조회한다 — `useSettingsOverrideForm` 과 같은 가드다. StrictMode 이중 실행이나
  // `load` 참조 변동으로 재조회가 걸리면 `original` 이 다시 시드되어, 사용자가 입력 중이던
  // payload 편집이 조용히 덮인다.
  const didInitialLoad = useRef(false);
  useEffect(() => {
    if (didInitialLoad.current) return;
    didInitialLoad.current = true;
    void load();
  }, [load]);

  const setPlane = useCallback((next: CredentialPlane) => {
    // 폼 상태일 뿐이다 — DELETE 는 save() 가 plane==='platform' 일 때만 부른다(브리프 테스트 1).
    setPlaneState(next);
  }, []);

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

  /**
   * `secretFieldNames` 의 노출본 — Ruling #38. 서버 원본(`secretFieldNames` 상태)은 마지막
   * GET/저장 시점 문서의 비밀 이름이다. 화면이 지금 보여주는 정체성(현재 `agentType`, 현재
   * `plane`)이 그 문서의 정체성과 같을 때만 그대로 내준다 — 위 인터페이스 `secretFieldNames`
   * 주석에 전체 근거가 있다.
   *
   * `original.tenantOwned ? 'tenant' : 'platform'` 이 "원본 문서가 실제로 속한 평면"이다.
   */
  const visibleSecretFieldNames =
    agentType === original.agentType && plane === (original.tenantOwned ? 'tenant' : 'platform')
      ? secretFieldNames
      : [];

  // canLoadModels 의 "저장된 키" 절반 — 위 인터페이스 canLoadModels 주석의 근거를 그대로 코드로
  // 옮긴다. `visibleSecretFieldNames` 를 쓰므로 유형·평면 불일치 가드(Ruling #38)를 다시 적지
  // 않아도 자동으로 상속되고, 여기서는 Ruling #37(기본 URL 일치)만 추가한다.
  const hasStoredApiKey =
    original.tenantOwned &&
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
      const { data } = await settingsApi.probeAiCredential(body);
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
  }, [payload.baseURL, secretInputs.apiKey]);

  /**
   * <b>`plane` 자체는 넣지 않는다.</b> 라디오 전환은 그 자체로 저장 확인 다이얼로그를 여는
   * 계기이므로(`useSettingsOverrideForm` 관용구와 달리 이 화면은 라디오가 곧 "저장할 의도"다),
   * 라디오만 만지고 아무 필드도 입력하지 않았는데 "미저장 입력이 있습니다"라는 덧문구까지
   * 붙으면 매번 뜨는 잡음이 된다 — 그 문구는 <b>입력한 값을 잃는다</b>는 경고이지 "라디오를
   * 눌렀다"는 사실을 알리는 것이 아니다.
   */
  const hasUnsavedInput =
    agentType !== original.agentType ||
    CREDENTIAL_FIELDS[agentType]
      .filter((field) => field.plane === 'payload')
      .some((field) => (payload[field.name] ?? '') !== (original.payload[field.name] ?? '')) ||
    Object.values(secretInputs).some((value) => value.trim() !== '');

  const save = useCallback(async (): Promise<boolean> => {
    if (plane === 'platform') {
      // "플랫폼 설정을 사용" 으로 저장 — 테넌트 오버라이드를 지워 상속으로 되돌린다.
      // 로컬 agentType/payload/secretInputs 편집은 무시한다: 이 평면에는 입력칸 자체가 없다
      // (설계서 "플랫폼 설정을 사용 — 입력칸 없음").
      try {
        await settingsApi.deleteAiCredential();
      } catch (err) {
        toast.error(extractApiError(err, '플랫폼 값으로 되돌리지 못했습니다.'));
        return false;
      }
    } else {
      // "우리 조직이 직접 설정" 으로 저장 — 현재 유형의 필드만 골라 보낸다. 다른 유형의
      // 필드가 로컬 상태에 남아 있어도(정상적으로는 setAgentType 이 비우므로 없어야 한다)
      // 여기서 다시 한번 CREDENTIAL_FIELDS 로 걸러 실어 보내지 않는다.
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
          // 빈 값 = 유지(생략). 여기서 ''를 실으면 서버 계약상 "삭제"가 되어, 사용자가
          // 손대지 않은 비밀이 저장 한 번에 사라진다 — 이 분기가 이 훅에서 가장 위험한 한 줄이다.
          //
          // <b>이 훅은 빈 문자열을 절대 보내지 않는다 — 의도적이다, "고칠 거리"가 아니다.</b>
          // 스펙 §"PUT 의 비밀 의미"는 "필드를 생략하면 현재 값 유지, 빈 문자열이면 삭제"라
          // 명시하고, 화면의 "비워두면 현재 값 유지" 어포던스를 그 <b>생략</b> 분기에
          // 대응시킨다 — 빈 문자열(삭제) 분기는 이 폼의 입력 제스처가 아니다. 이 훅에 필드
          // 단위 "삭제" 버튼이 없기 때문이다(설계 의도). 비밀을 실제로 지우는 경로는 따로 있다:
          // (1) `setAgentType` 로 유형을 바꾸면 이전 유형의 비밀이 통째로 폐기되고,
          // (2) `plane` 을 `'platform'` 으로 두고 저장하면 문서 전체가 DELETE 된다.
          //
          // `typed` 가 `undefined`(생략)든 `''`(지웠다가 빈 채로 둠)든 `secretOut` 에서
          // 빠지는 것은 같은 결과이고, <b>그것이 맞다</b> — 반대로 "빈 문자열이면 삭제로
          // 보낸다"로 고치면, 사용자가 기본 URL 만 고치고 API 키 입력칸은 건드리지 않은 채
          // 저장해도(그 칸은 처음부터 빈 채였다) 저장된 API 키가 조용히 사라진다. 토스트는
          // 여전히 "저장했습니다"라고 말한다.
          if (typed && typed.trim() !== '') secretOut[field.name] = typed;
        });
      try {
        await settingsApi.putAiCredential({ agentType, payload: payloadOut, secret: secretOut });
      } catch (err) {
        toast.error(extractApiError(err, '저장에 실패했습니다.'));
        return false;
      }
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
  }, [plane, agentType, payload, secretInputs, fetchAndApply]);

  /**
   * 로컬 편집을 마지막 `original` 스냅샷으로 되돌린다(Ruling #41, fix round 1) — 인터페이스
   * `reset` 주석 참고. 네트워크 호출 없이 즉시 반영된다.
   */
  const reset = useCallback(() => {
    setPlaneState(original.tenantOwned ? 'tenant' : 'platform');
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
    plane,
    tenantOwned: original.tenantOwned,
    setPlane,
    agentType,
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
  };
}
