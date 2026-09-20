import type {
  PlatformAiAuthStatusResponse,
  PlatformAiCredentialProbeRequest,
  PlatformAiCredentialProbeResponse,
  PlatformAiCredentialResponse,
  PlatformAiCredentialUpsertPayload,
  SettingResponse,
} from '../types/platform';
import { client } from './client';

export const settingsApi = {
  /** 타입형 AI 설정 전환(Task 13)으로 `ai.api_key`/`ai.agent_type`/`ai.cli_oauth_token` 3키가
   * `ai.credential` 문서로 옮겨가 이 응답에서 빠졌다.
   *
   * <b>정확한 행 수는 여기 적지 않는다(fix round 2 — 이 클라이언트가 통제하지 않는 서버 응답의
   * 사실을 이 파일이 단언하면, 서버가 바뀌어도 이 주석은 그대로 남아 거짓말을 하게 된다. 실측
   * 사례: `ai.session_max_tokens` 는 어떤 마이그레이션도 시드하지 않아 행이 없을 수도, DB 상태에
   * 따라 있을 수도 있다).</b> 지금 기대되는 응답 형태(15행)는 `lib/settings-catalog.test.ts`
   * 가 `ALL_SETTING_KEYS` 를 통해 실행 가능한 단언으로 직접 검증한다 — 사실은 검증하는 테스트
   * 쪽에 두고, 여기는 "이 엔드포인트가 무엇을 반환하지 않는가"만 남긴다. */
  getAll: () => client.get<SettingResponse[]>('/settings'),
  /** 부분 갱신. 변경된 키만 담아 보낸다. 성공은 204. */
  update: (settings: Record<string, string>) => client.put<void>('/settings', settings),

  // `ai.credential` 은 하위 필드(secret)에 비밀이 있는 JSON 문서라 위 범용 `/settings` 경로
  // (문자열 키·값 전제)에 얹을 수 없다 — 전용 엔드포인트(Task 7 `PlatformAiCredentialController`).
  // 셋 다 `platform:settings:read`/`write` 권한이 필요하다(프로브는 쓰기 권한).
  getAiCredential: () => client.get<PlatformAiCredentialResponse>('/settings/ai-credential'),

  putAiCredential: (data: PlatformAiCredentialUpsertPayload) =>
    client.put<void>('/settings/ai-credential', data),

  // opencode 전용 모델 목록 조회 — 플랫폼 평면은 apiKey 생략을 허용하지 않는다(타입 자체가
  // apiKey 를 필수로 둔다, `types/platform.ts` 참고).
  probeAiCredential: (data: PlatformAiCredentialProbeRequest) =>
    client.post<PlatformAiCredentialProbeResponse>('/settings/ai-credential/probe', data),

  // Ruling #47(Task 13 fix round 1) — 플랫폼 자격증명 인증 상태. 백엔드 `PlatformAiController`
  // 는 `AiCredentialService.resolve()` 를 그대로 재사용하는 얇은 거울이라, 테넌트 쪽
  // `verifyAuthStatus`(firehub-web `api/settings.ts`)와 경로만 다르고 응답 모양은 같다.
  verifyAuthStatus: () => client.get<PlatformAiAuthStatusResponse>('/ai/auth-status'),
};
