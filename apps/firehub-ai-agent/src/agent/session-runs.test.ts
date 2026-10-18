import { describe, it, expect } from 'vitest';
import {
  acquireSessionRun,
  cancelSessionRun,
  registerNewSessionRun,
  releaseSessionRun,
} from './session-runs.js';

// 테스트마다 다른 세션 id 를 써서 모듈 전역 Map 상태가 서로 섞이지 않게 한다.
let seq = 0;
const sid = () => `sess-runs-${++seq}`;

describe('session-runs — 세션별 진행 중 실행 가드(WD-57)', () => {
  it('비어 있는 세션은 즉시 슬롯을 얻는다', async () => {
    const s = sid();
    const run = await acquireSessionRun(1, s, 10, 0);
    expect(run).not.toBeNull();
    releaseSessionRun(run!);
  });

  it('이전 실행이 대기 시간 안에 끝나면 이어서 들어간다', async () => {
    const s = sid();
    const first = (await acquireSessionRun(1, s, 10, 0))!;
    setTimeout(() => releaseSessionRun(first), 20);
    const second = await acquireSessionRun(1, s, 10, 1_000);
    expect(second).not.toBeNull();
    expect(second).not.toBe(first);
    releaseSessionRun(second!);
  });

  it('대기 시간이 지나도 이전 실행이 남아 있으면 null(=409)', async () => {
    const s = sid();
    const first = (await acquireSessionRun(1, s, 10, 0))!;
    const started = Date.now();
    const second = await acquireSessionRun(1, s, 10, 50);
    expect(second).toBeNull();
    expect(Date.now() - started).toBeGreaterThanOrEqual(45);
    releaseSessionRun(first);
  });

  it('대기자가 둘이면 하나만 들어가고 나머지는 계속 기다린다(동시 통과 없음)', async () => {
    const s = sid();
    const first = (await acquireSessionRun(1, s, 10, 0))!;
    const a = acquireSessionRun(1, s, 10, 200);
    const b = acquireSessionRun(1, s, 10, 200);
    releaseSessionRun(first);
    const [ra, rb] = await Promise.all([a, b]);
    // 하나는 슬롯을 얻고, 다른 하나는 그 슬롯이 계속 잡혀 있어 시간 초과로 null
    expect([ra, rb].filter(Boolean)).toHaveLength(1);
    releaseSessionRun((ra ?? rb)!);
  });

  it('테넌트가 다르면 같은 세션 id 라도 서로 막지 않는다', async () => {
    const s = sid();
    const t1 = (await acquireSessionRun(1, s, 10, 0))!;
    const t2 = await acquireSessionRun(2, s, 10, 0);
    expect(t2).not.toBeNull();
    releaseSessionRun(t1);
    releaseSessionRun(t2!);
  });

  it('이미 다른 실행이 차지한 슬롯은 옛 실행의 release 로 지워지지 않는다', async () => {
    const s = sid();
    const old = (await acquireSessionRun(1, s, 10, 0))!;
    releaseSessionRun(old);
    const current = (await acquireSessionRun(1, s, 10, 0))!;
    releaseSessionRun(old); // 중복 release
    expect(await acquireSessionRun(1, s, 10, 0)).toBeNull();
    releaseSessionRun(current);
  });

  it('취소는 같은 테넌트·같은 사용자의 실행만 abort 하고 슬롯은 놓지 않는다', async () => {
    const s = sid();
    const run = (await acquireSessionRun(1, s, 10, 0))!;
    expect(cancelSessionRun(2, s, 10)).toBe(false); // 다른 테넌트
    expect(cancelSessionRun(1, s, 99)).toBe(false); // 다른 사용자
    expect(run.controller.signal.aborted).toBe(false);
    expect(cancelSessionRun(1, s, 10)).toBe(true);
    expect(run.controller.signal.aborted).toBe(true);
    // 정리(트랜스크립트 저장)가 끝나 release 되기 전까지 새 실행은 들어올 수 없다
    expect(await acquireSessionRun(1, s, 10, 0)).toBeNull();
    releaseSessionRun(run);
  });

  it('진행 중 실행이 없으면 취소는 false', () => {
    expect(cancelSessionRun(1, sid(), 10)).toBe(false);
  });

  it('새 세션은 init 에서 받은 id 로 등록되고, 이미 있으면 덮어쓰지 않는다', async () => {
    const s = sid();
    const controller = new AbortController();
    const run = registerNewSessionRun(1, s, 10, controller)!;
    expect(run.controller).toBe(controller);
    expect(run).not.toBeNull();
    expect(registerNewSessionRun(1, s, 10, new AbortController())).toBeNull();
    expect(await acquireSessionRun(1, s, 10, 0)).toBeNull();
    releaseSessionRun(run);
  });
});
