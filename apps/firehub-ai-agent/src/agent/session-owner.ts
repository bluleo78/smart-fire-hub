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
 * <p><b>알려진 한계 — 이 표식은 컨테이너 로컬 디스크에만 산다.</b> 오늘 ai-agent 는 단일
 * 인스턴스에 볼륨 마운트가 없어 동작하지만, 레플리카를 늘리거나 컨테이너를 재기동하면 표식이
 * 공유되지 않아 판정이 조용히 `unknown` 으로 후퇴한다 — 기능은 죽지 않고 심층방어만 사라진다.
 * 권위 있는 정보(`ai_session.tenant_id` + RLS)는 이미 firehub-api 의 DB 에 있으므로, 정공법은
 * firehub-api 에 "세션-테넌트 일치 확인" 내부 엔드포인트를 두고 여기서 그걸 묻는 것이다(그러면
 * 별도 저장소·무한 성장·인스턴스 간 불일치가 모두 사라진다). 이번 밴드에서는 범위를 이유로
 * 파일 표식을 택했고, 이 문단이 그 부채의 기록이다.
 *
 * <p><b>표식이 없는 세션은 거부하지 않는다.</b> 세그먼트 도입 전에 만들어진 세션은 표식이 없고,
 * 그것을 fail-closed 로 막으면 과거 이력이 전부 안 보이게 된다(기능 회귀). 표식이 **있는데
 * 다른 테넌트** 인 경우만 거부한다 — 이게 심층방어가 실제로 잡을 수 있는 유일한 사례이고,
 * 1차 게이트(API 소유권 검증)는 그대로 살아 있다.
 */
import { access, mkdir, readdir, writeFile } from 'fs/promises';
import { homedir } from 'os';
import { join } from 'path';
import { isSafeSessionId, tenantSegment } from './tenant-paths.js';

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
  if (!isSafeSessionId(sessionId)) return;
  try {
    const dir = join(ownerRoot(), tenantSegment(tenantId));
    // 이미 내 표식이 있으면 즉시 끝낸다. 이 함수는 세션 생성 시점이 아니라 **매 턴** 불리므로,
    // 이 단락이 없으면 멀티턴 대화가 턴마다 전 테넌트 스캔 + mkdir + writeFile 을 반복한다.
    try {
      await access(join(dir, sessionId));
      return;
    } catch {
      // 표식이 없다 — 아래에서 만든다.
    }
    // 이미 **다른 테넌트**가 표식을 가진 세션이면 두 번째 표식을 만들지 않는다(코드리뷰 지적).
    // `/agent/chat` 은 sessionId 를 클라이언트가 준 값 그대로 받으므로, 내부 토큰을 가진 호출부가
    // 테넌트 A 의 sessionId 를 tenantId=B 로 보내면 표식이 양쪽에 생긴다. 그러면 판정이 "먼저
    // 발견된 디렉터리" 에 좌우돼 `owned` 가 나올 수 있고, 그 순간 표식은 자기가 막으려던 것을
    // 통과시킨다. 여기서 거절하면 그 상태 자체가 만들어지지 않는다(판정 쪽에도 이중 안전장치가 있다).
    if ((await checkSessionOwnership(tenantId, sessionId)) === 'other-tenant') {
      console.warn(
        `[SessionOwner] 세션 ${sessionId} 는 이미 다른 테넌트의 표식을 갖고 있다 — 테넌트 ${tenantId} 표식을 만들지 않는다`,
      );
      return;
    }
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
 * <p>"다른 테넌트에 있다" 를 확인해야 `other-tenant` 와 `unknown` 을 구분할 수 있고, 그 구분이
 * 곧 "거부할지 1차 게이트에 맡길지" 를 가른다. 그래서 자기 디렉터리에서 찾았다고 멈추지 않고
 * **모든 테넌트 디렉터리를 끝까지 본다**(코드리뷰 지적) — 한 세션에 표식이 둘 이상이면 어느
 * 쪽이 먼저 열거되는지에 판정이 좌우되므로, 그런 경우는 무조건 `other-tenant` 로 떨어뜨린다.
 * {@link claimSession} 이 중복 생성을 막지만, 판정 쪽에서도 순서 의존을 남기지 않는다.
 *
 * <p>디렉터리 목록이 아니라 **파일 존재 확인**({@code access})을 쓴다 — 세션 수가 쌓이면
 * `readdir` 은 그 테넌트의 전체 목록을 만들어 문자열 비교를 하므로 총 세션 수에 비례해 커진다.
 * 확인 대상 경로는 이미 알고 있으니 테넌트 수만큼의 `access` 로 충분하다.
 */
export async function checkSessionOwnership(
  tenantId: number,
  sessionId: string,
): Promise<SessionOwnership> {
  if (!isSafeSessionId(sessionId)) return 'other-tenant';
  const mine = tenantSegment(tenantId);
  let tenantDirs: string[];
  try {
    tenantDirs = await readdir(ownerRoot());
  } catch {
    // 표식 디렉터리 자체가 없다 = 표식을 쓰기 시작하기 전 상태.
    return 'unknown';
  }
  let mineHasIt = false;
  let othersHaveIt = false;
  for (const dir of tenantDirs) {
    try {
      await access(join(ownerRoot(), dir, sessionId));
    } catch {
      continue;
    }
    if (dir === mine) mineHasIt = true;
    else othersHaveIt = true;
  }
  if (othersHaveIt) return 'other-tenant';
  return mineHasIt ? 'owned' : 'unknown';
}
