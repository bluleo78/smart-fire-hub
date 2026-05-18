/**
 * agent-cli argv 크기 한계 회귀 가드.
 *
 * #259/#260 의 spawn E2BIG 가 테스트에 걸리지 않은 근본 원인:
 *   - 기존 단위 테스트는 `loadSubagents` 를 작은 mock 으로 대체했고
 *   - `spawn` 자체를 모킹해 execve 를 호출하지 않았음.
 *   → argv 합산/단일 인자 byte 크기를 검사하는 테스트가 부재했다.
 *
 * 본 파일은 **실제 운영용 subagent 정의를 디스크에서 그대로 로드**해 executeCliAgent 가
 * spawn 에 넘기는 argv 의 byte 크기가 Linux MAX_ARG_STRLEN(=128KB) 을 넘지 않는지 검증한다.
 * 임의의 누군가 `--agents <json>` 인라인 전달이나 다른 큰 인자를 재도입하면 즉시 실패한다.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { EventEmitter } from 'events';
import { Readable } from 'stream';

const spawnMock = vi.fn();
vi.mock('child_process', () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

// loadSubagents 는 모킹하지 않는다 — 실제 운영 정의(11개, 약 172KB 상당)를 그대로 사용.

import { executeCliAgent } from './agent-cli.js';

// Linux execve 한계 (커널 PAGE_SIZE × 32). 컨테이너/호스트 동일.
const MAX_ARG_STRLEN = 128 * 1024;
// 단일 인자 byte 한도(여유 8KB) — 인자 하나라도 이 값을 넘기면 spawn E2BIG 위험.
const SINGLE_ARG_LIMIT = MAX_ARG_STRLEN - 8 * 1024;

function makeFakeChild() {
  const stdout = Readable.from([]);
  const stderr = new EventEmitter();
  const child = new EventEmitter() as EventEmitter & {
    stdout: Readable;
    stderr: EventEmitter;
    kill: (sig?: string) => void;
  };
  child.stdout = stdout;
  child.stderr = stderr as EventEmitter & { on: EventEmitter['on'] };
  child.kill = vi.fn();
  return child;
}

describe('executeCliAgent — spawn argv 크기 한계 (#260 회귀 가드)', () => {
  beforeEach(() => {
    spawnMock.mockReset();
    spawnMock.mockImplementation(() => makeFakeChild());
  });

  it('실제 subagent 정의로 spawn 호출 시 모든 인자가 MAX_ARG_STRLEN 미만이어야 한다', async () => {
    const gen = executeCliAgent({
      message: 'test',
      userId: 8888,
      useSubscription: false,
      apiKey: 'sk-test',
    });

    for await (const _ev of gen) {
      // drain
    }

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const argv = spawnMock.mock.calls[0][1] as string[];

    // 단일 인자 한도 검증 — 어느 하나라도 넘으면 spawn E2BIG.
    for (const arg of argv) {
      const size = Buffer.byteLength(arg, 'utf-8');
      expect(size, `argv 단일 인자 byte 초과: "${arg.slice(0, 40)}…" = ${size}B`).toBeLessThan(
        SINGLE_ARG_LIMIT,
      );
    }

    // 전체 합계도 가드(보수적으로 256KB) — 인자가 작아도 개수가 폭증하면 위험.
    const total = argv.reduce((sum, a) => sum + Buffer.byteLength(a, 'utf-8'), 0);
    expect(total).toBeLessThan(256 * 1024);
  });

  it('subagent 정의를 인라인 인자(--agents)로 전달해서는 안 된다 (#260)', async () => {
    const gen = executeCliAgent({
      message: 'test',
      userId: 8889,
      useSubscription: false,
      apiKey: 'sk-test',
    });
    for await (const _ev of gen) {
      // drain
    }
    const argv = spawnMock.mock.calls[0][1] as string[];
    expect(argv).not.toContain('--agents');
  });
});
