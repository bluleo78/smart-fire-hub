// 플래그 없는 `SettingResponse`(백엔드 DTO)는 web 에서 지웠다 — 마지막 소비자였던
// `GET /settings/smtp` 가 P7-c1 에서 사라졌고, 화면이 읽는 모든 설정은 이제 해석된
// `ResolvedSettingResponse` 다.

/**
 * 테넌트 설정 화면용 "해석된" 설정 1건 — 백엔드 `ResolvedSettingResponse` DTO와 1:1 대응.
 * `GET /settings?prefix=...` 가 이 형태로 응답한다.
 *
 * - `value`: 테넌트 오버라이드가 있으면 그 값, 없으면 플랫폼 값. 비밀 키는 `****` 로 마스킹된다.
 * - `overridden`: 지금 보이는 값이 테넌트 오버라이드에서 왔는지.
 * - `tenantEditable`: 이 키를 테넌트가 바꿀 수 있는지(오버라이드 존재 여부와 무관한 키 고유 성질).
 *
 * `value`/`description`/`updatedAt` 이 null 일 수 있는 이유: 플랫폼 시드 행 없이 오버라이드만
 * 존재하는 키는 설명·갱신시각을 줄 시스템 설정 행이 없다.
 */
export interface ResolvedSettingResponse {
  key: string;
  value: string | null;
  description: string | null;
  updatedAt: string | null;
  overridden: boolean;
  tenantEditable: boolean;
}

export interface UpdateSettingsRequest {
  settings: Record<string, string>;
}

/**
 * `GET /settings/ai-credential` 응답 — 백엔드 `AiCredentialService.AiCredentialView` 와 1:1 대응.
 *
 * `ai.credential` 은 하위 필드(secret)에 비밀이 있는 JSON 문서라 `ResolvedSettingResponse`
 * (문자열 단일 값 전제)로 표현할 수 없어 별도 타입을 둔다.
 *
 * - `payload`: 평문(비밀 아님) — baseURL·providerId·reasoningEffort 등.
 * - `secretFieldNames`: 값이 실재하는 비밀 필드 이름만. **값 자체는 어떤 경로로도 내려오지
 *   않는다** — 서버가 절대 평문/마스크를 돌려주지 않으므로, 화면은 이 목록으로만 "설정됨"을
 *   판정한다.
 * - `tenantOwned`: 지금 응답이 테넌트 오버라이드에서 왔는지(`true`) 플랫폼 값에서 왔는지
 *   (`false`). 플랫폼 미러(`/api/platform/settings/ai-credential`)에는 이 필드가 **없다** — 상위
 *   평면이 없어 의미가 없다.
 */
export interface AiCredentialResponse {
  agentType: string;
  payload: Record<string, unknown>;
  secretFieldNames: string[];
  tenantOwned: boolean;
}

/**
 * `PUT /settings/ai-credential` 요청 바디 — 백엔드 `AiCredentialUpsertRequest` 와 1:1 대응.
 *
 * `secret` 의 계약: **필드를 생략하면 현재 값을 유지**하고, 빈 문자열이면 삭제한다. 서버가
 * 저장된 비밀을 절대 평문으로 돌려주지 않으므로 "건드리지 않았다"를 표현하는 유일한 방법이
 * 생략이다 — `null` 은 서버가 400 으로 거부한다.
 */
export interface AiCredentialUpsertPayload {
  agentType: string;
  payload: Record<string, string>;
  secret: Record<string, string>;
}

/** `POST /settings/ai-credential/probe` 요청 바디 — opencode 전용 모델 목록 조회. */
export interface AiCredentialProbeRequest {
  baseURL: string;
  /** 생략하면 서버가 테넌트에 저장된 값을 재사용한다(평면 교차 폴백 없음 — 테넌트 값만 본다). */
  apiKey?: string;
}

/**
 * 프로브 응답 — 성공·실패 모두 HTTP 200 으로 오고(`SMTP 연결 테스트` 선례), 이 바디의 `ok` 로
 * 판정한다. 요청 형태 자체가 잘못됐을 때만 4xx 다.
 */
export interface AiCredentialProbeResponse {
  ok: boolean;
  models: string[];
  message: string | null;
}
