/**
 * 세션별 진행 중 채팅 실행 레지스트리 (WD-57).
 *
 * <p><b>무엇을 하는가.</b> (테넌트, 세션) 마다 지금 돌고 있는 채팅 실행 하나를 기억한다. 두 가지에 쓴다.
 *  1. 세션 가드 — 같은 세션에 새 요청이 오면 이전 실행이 끝날 때까지 잠깐 기다리고, 그래도 남아 있으면 거절한다.
 *     opencode·CLI 경로는 시작 시 트랜스크립트 파일을 통째로 읽고 끝날 때 통째로 덮어쓰므로, 두 실행이 겹치면
 *     나중에 끝난 쪽이 먼저 끝난 쪽의 턴을 지운다(턴 유실). 겹치지 않게 하는 것이 유일한 정공법이다.
 *  2. 명시적 취소 — 사용자가 "응답 중단"을 누르면 그 실행의 AbortController 를 abort 해 공급자가 자식
 *     프로세스를 kill(SDK 는 abort)하게 한다. 클라이언트 연결 끊김만으로는 취소하지 않는다(의도된 설계).
 *
 * <p><b>왜 프로세스 메모리인가.</b> ai-agent 는 단일 인스턴스로 배포된다(docker-compose 단일 서비스, 볼륨 없음).
 * 트랜스크립트·세션 표식도 이미 컨테이너 로컬 디스크에 살아서(session-owner.ts 의 "알려진 한계" 참고) 레플리카를
 * 늘리면 이력부터 깨진다 — 이 가드도 같은 가정 위에 있다.
 */

/** 같은 세션의 이전 실행이 끝나기를 기다리는 최대 시간. 취소 직후 정리(트랜스크립트 저장)는 보통 1초 안에 끝난다. */
export const SESSION_BUSY_WAIT_MS = 5_000;

/** 진행 중 실행 하나. done 은 release 될 때 resolve 된다(대기자가 깨어나는 신호). */
export interface SessionRun {
  readonly tenantId: number;
  readonly sessionId: string;
  readonly userId: number;
  readonly controller: AbortController;
  readonly done: Promise<void>;
  /** done 을 resolve 한다 — release 전용. */
  readonly resolveDone: () => void;
}

const runs = new Map<string, SessionRun>();

function keyOf(tenantId: number, sessionId: string): string {
  return `${tenantId}:${sessionId}`;
}

/** 확인 직후 같은 틱 안에서 등록한다 — 사이에 await 가 없으므로 두 요청이 동시에 통과할 수 없다. */
function register(
  tenantId: number,
  sessionId: string,
  userId: number,
  controller: AbortController = new AbortController(),
): SessionRun {
  let resolveDone!: () => void;
  const done = new Promise<void>((resolve) => {
    resolveDone = resolve;
  });
  const run: SessionRun = { tenantId, sessionId, userId, controller, done, resolveDone };
  runs.set(keyOf(tenantId, sessionId), run);
  return run;
}

/**
 * 세션의 실행 슬롯을 얻는다. 이전 실행이 있으면 최대 waitMs 동안 끝나기를 기다리고, 그래도 남아 있으면 null.
 *
 * <p>대기자가 여럿이면 깨어난 순서대로 다시 확인하므로 하나만 들어가고 나머지는 계속 기다린다(동시 통과 없음).
 */
export async function acquireSessionRun(
  tenantId: number,
  sessionId: string,
  userId: number,
  waitMs: number = SESSION_BUSY_WAIT_MS,
): Promise<SessionRun | null> {
  const deadline = Date.now() + waitMs;
  for (;;) {
    const existing = runs.get(keyOf(tenantId, sessionId));
    if (!existing) return register(tenantId, sessionId, userId);
    const remaining = deadline - Date.now();
    if (remaining <= 0) return null;
    let timer: NodeJS.Timeout | undefined;
    await Promise.race([
      existing.done,
      new Promise<void>((resolve) => {
        timer = setTimeout(resolve, remaining);
      }),
    ]);
    clearTimeout(timer);
  }
}

/**
 * 새 세션(요청에 sessionId 가 없던 실행)을 init 이벤트에서 받은 id 로 등록한다 — 그래야 첫 턴도 취소할 수 있고,
 * 첫 턴이 끝나기 전에 같은 세션으로 들어온 후속 요청이 가드에 걸린다. 실행은 init 전에 이미 시작됐으므로 그때
 * 공급자에 넘긴 controller 를 그대로 받는다. 이미 같은 id 가 등록돼 있으면(이론상 없음) 덮어쓰지 않고 null.
 */
export function registerNewSessionRun(
  tenantId: number,
  sessionId: string,
  userId: number,
  controller: AbortController,
): SessionRun | null {
  if (runs.has(keyOf(tenantId, sessionId))) return null;
  return register(tenantId, sessionId, userId, controller);
}

/** 실행 슬롯을 놓는다. 이미 다른 실행이 자리를 차지했으면(정체성 불일치) 건드리지 않는다. */
export function releaseSessionRun(run: SessionRun): void {
  const key = keyOf(run.tenantId, run.sessionId);
  if (runs.get(key) === run) runs.delete(key);
  run.resolveDone();
}

/**
 * 진행 중 실행을 취소한다. 같은 테넌트·같은 사용자의 실행만 취소한다 — 아니면 존재 여부도 알리지 않고 false.
 * 슬롯은 여기서 놓지 않는다: 실행이 트랜스크립트 저장까지 마치고 라우트의 finally 에서 놓아야 다음 실행이
 * 최신 기록을 읽는다.
 */
export function cancelSessionRun(tenantId: number, sessionId: string, userId: number): boolean {
  const run = runs.get(keyOf(tenantId, sessionId));
  if (!run || run.userId !== userId) return false;
  run.controller.abort();
  return true;
}
