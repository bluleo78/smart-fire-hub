import type {
  AiCredentialProbeRequest,
  AiCredentialProbeResponse,
  AiCredentialResponse,
  AiCredentialUpsertPayload,
  ResolvedSettingResponse,
  UpdateSettingsRequest,
} from '../types/settings';
import { client } from './client';

export const settingsApi = {
  // 해석된 값 + overridden/tenantEditable 플래그를 함께 받는다 (P7-b).
  getByPrefix: (prefix: string) =>
    client.get<ResolvedSettingResponse[]>('/settings', { params: { prefix } }),

  update: (data: UpdateSettingsRequest) =>
    client.put('/settings', data),

  // 테넌트 오버라이드 삭제 = 플랫폼 기본값으로 복귀. 멱등(이미 상속 중이어도 204).
  clearOverride: (key: string) =>
    client.delete(`/settings/overrides/${encodeURIComponent(key)}`),

  verifyAuthStatus: () =>
    client.get<{ valid: boolean; email?: string; subscriptionType?: string }>('/ai/auth-status'),

  // `ai.credential` 은 하위 필드(secret)에 비밀이 있는 JSON 문서라 위 범용 `/settings` 경로
  // (문자열 키·값 전제)에 얹을 수 없다 — 타입형 전환(2026-09)으로 전용 엔드포인트가 생겼다
  // (Task 7). 세 메서드 모두 `ai:settings` 권한이 필요하다. 자격증명은 테넌트 전용이라(#706)
  // "플랫폼 값으로 복귀"(DELETE) 경로는 없다 — 서버도 DELETE 를 405 로 거부한다.
  getAiCredential: () => client.get<AiCredentialResponse>('/settings/ai-credential'),

  putAiCredential: (data: AiCredentialUpsertPayload) => client.put('/settings/ai-credential', data),

  // opencode 전용 모델 목록 조회. 인증된 외부 호출(임의 baseURL 에 Bearer 전송)을 일으키므로
  // 쓰기 권한(`ai:settings`)이 필요하다 — 조회 전용처럼 보여도 GET 이 아니라 POST 인 이유다.
  probeAiCredential: (data: AiCredentialProbeRequest) =>
    client.post<AiCredentialProbeResponse>('/settings/ai-credential/probe', data),
};
