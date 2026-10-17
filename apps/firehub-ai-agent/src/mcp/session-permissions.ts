import type { FireHubApiClient } from './api-client.js';

/**
 * 권한 조회 요청 타임아웃 기본값(ms). 공용 axios 클라이언트에는 타임아웃이 없어, api 가 응답 없이
 * 멈추면 호출부도 함께 멈춘다. 초과하면 요청을 취소하고 실패(fail-closed)로 본다.
 * stdio MCP 서버는 핸드셰이크 전에 조회하므로 더 짧은 값을 넘긴다(stdio-server.ts 참고).
 */
export const SESSION_PERMISSIONS_TIMEOUT_MS = 10_000;

/**
 * 세션 사용자 권한 조회 — fail-closed 래퍼.
 *
 * 백엔드 `/auth/me/permissions` 호출이 실패하거나 timeoutMs 안에 끝나지 않으면 빈 배열(`[]`)로 폴백한다. 권한 필터는 `undefined`를 "전부 허용(permissive)"으로, `[]`를
 * "요구 권한 있는 파괴 도구 전부 차단(fail-closed)"으로 해석하므로, 실패 경로에서는 반드시 `[]` 를
 * 반환해야 기본 차단이 유지된다.
 *
 * SDK 경로(agent-sdk.ts)와 CLI·opencode 공용 stdio MCP 서버(stdio-server.ts)가 함께 쓴다(WD-63).
 * stdio 자식 프로세스가 agent-sdk.ts 의 무거운 의존 그래프를 끌어오지 않도록 가벼운 별도 모듈에 둔다.
 * 로그는 stderr(console.warn)로만 남긴다 — stdio 서버에서 stdout 은 JSON-RPC 스트림이다.
 *
 * @param tag 로그 접두사 — 호출 경로(예: `[Claude ...] [AI Chat]`, `[MCP Stdio]`)를 호출측이 정한다.
 */
export async function fetchSessionPermissionsFailClosed(
  apiClient: FireHubApiClient,
  tag: () => string = () => '[Claude]',
  timeoutMs: number = SESSION_PERMISSIONS_TIMEOUT_MS,
): Promise<string[]> {
  try {
    return await apiClient.getSessionPermissions(timeoutMs);
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.warn(
      `${tag()} failed to fetch session permissions, defaulting to [] (fail-closed): ${message}`,
    );
    return [];
  }
}
