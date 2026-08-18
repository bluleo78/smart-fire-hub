/**
 * 세션 → 테넌트 귀속 표식(marker). `/agent/history/:sessionId` 의 **심층방어** 게이트가 쓴다.
 *
 * <p><b>왜 필요한가.</b> 오늘 트랜스크립트 열람의 권위는 firehub-api 다 —
 * `GET /api/v1/ai/sessions/{id}/messages` 가 `verifySessionOwnership` + `ai_session` RLS 로
 * 소유권을 검증한 뒤에야 이 서비스로 프록시한다. 하지만 ai-agent 쪽 `/agent/history` 자체는
 * 내부 토큰만 보고 통과시키므로, 그 한 층이 뚫리거나 다른 내부 호출부가 실수로 임의 sessionId 를
 * 넣으면 막을 것이 없다. 표식은 그 두 번째 층이다.
 *
 * <p><b>왜 별도 파일인가 — 경로만으로는 판정할 수 없다.</b> CLI 트랜스크립트는 테넌트 경로에
 * 저장되므로 경로 존재만으로도 귀속을 알 수 있지만, SDK 경로의 트랜스크립트는 Claude SDK 가
 * `~/.claude/projects/{cwd 파생}/{sessionId}.jsonl` 에 직접 쓰고 그 디렉터리엔 테넌트 개념이
 * 없다. 두 경로를 같은 규칙으로 게이팅하려면 우리가 소유하는 표식이 하나 필요하다.
 *
 * <p><b>표식이 없는 세션은 거부하지 않는다.</b> 세그먼트 도입 전에 만들어진 세션은 표식이 없고,
 * 그것을 fail-closed 로 막으면 과거 이력이 전부 안 보이게 된다(기능 회귀). 표식이 **있는데
 * 다른 테넌트** 인 경우만 거부한다 — 이게 심층방어가 실제로 잡을 수 있는 유일한 사례이고,
 * 1차 게이트(API 소유권 검증)는 그대로 살아 있다.
 */
import { mkdir, readdir, writeFile } from 'fs/promises';
import { homedir } from 'os';
import { join } from 'path';
import { tenantSegment } from './tenant-paths.js';

const SAFE_SESSION_ID = /^[a-zA-Z0-9_-]+$/;

function ownerRoot(): string {
  return join(homedir(), '.firehub', 'session-owner');
}

/**
 * 세션의 테넌트 귀속을 기록한다. 내용은 비어 있어도 되므로 **파일 존재 자체가 표식**이다
 * (경로에 이미 테넌트가 들어 있어 파일을 열어 볼 필요가 없다).
 *
 * <p>실패해도 던지지 않는다 — 표식은 심층방어이지 1차 게이트가 아니므로, 디스크 문제로 채팅
 * 자체가 죽는 편이 더 나쁘다. 표식이 없으면 아래 판정이 "미지"로 떨어져 1차 게이트만 남는다.
 */
export async function claimSession(tenantId: number, sessionId: string): Promise<void> {
  if (!SAFE_SESSION_ID.test(sessionId)) return;
  try {
    const dir = join(ownerRoot(), tenantSegment(tenantId));
    await mkdir(dir, { recursive: true });
    await writeFile(join(dir, sessionId), '');
  } catch {
    // 표식 기록 실패는 무시한다(위 주석).
  }
}

/** 판정 결과. `unknown` = 표식이 어느 테넌트에도 없다(레거시 세션 가능성). */
export type SessionOwnership = 'owned' | 'other-tenant' | 'unknown';

/**
 * 세션이 요청 테넌트의 것인지 판정한다.
 *
 * <p>먼저 요청 테넌트 디렉터리를 보고, 없으면 다른 테넌트 디렉터리들을 훑는다. "다른 테넌트에
 * 있다" 를 확인해야 `other-tenant` 와 `unknown` 을 구분할 수 있고, 그 구분이 곧 "거부할지
 * 1차 게이트에 맡길지" 를 가른다.
 */
export async function checkSessionOwnership(
  tenantId: number,
  sessionId: string,
): Promise<SessionOwnership> {
  if (!SAFE_SESSION_ID.test(sessionId)) return 'other-tenant';
  const mine = tenantSegment(tenantId);
  let tenantDirs: string[];
  try {
    tenantDirs = await readdir(ownerRoot());
  } catch {
    // 표식 디렉터리 자체가 없다 = 표식을 쓰기 시작하기 전 상태.
    return 'unknown';
  }
  for (const dir of tenantDirs) {
    let sessions: string[];
    try {
      sessions = await readdir(join(ownerRoot(), dir));
    } catch {
      continue;
    }
    if (!sessions.includes(sessionId)) continue;
    return dir === mine ? 'owned' : 'other-tenant';
  }
  return 'unknown';
}
