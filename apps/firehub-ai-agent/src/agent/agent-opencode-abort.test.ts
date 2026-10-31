/**
 * executeOpenCodeAgent 의 명시적 취소(abortSignal) 테스트 (WD-57).
 *
 * 사용자가 "응답 중단"을 누르면 라우트가 abortSignal 을 abort 한다. 이때 opencode 자식이 즉시 kill 되고,
 * 그때까지 받은 응답은 트랜스크립트에 남아야 한다(새로고침 후 이력에서 사라지면 안 된다).
 * 가짜 자식은 kill 될 때까지 stdout 을 열어 두므로, 제너레이터가 끝났다는 것 자체가 kill 로 끊겼다는 증거다.
 */
import { describe, it, expect, vi, afterAll } from 'vitest';
import { EventEmitter } from 'events';
import { PassThrough } from 'stream';
import { rm } from 'fs/promises';
import { useTempHome } from './temp-home.fixture.js';
import type { ChatProviderOptions, SSEEvent } from '../providers/types.js';
import type { OpenCodeCredentials } from './agent-opencode.js';

const spawnMock = vi.fn();
vi.mock('child_process', () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

import { executeOpenCodeAgent } from './agent-opencode.js';
import { readCliTranscript } from './agent-cli.js';

/**
 * 텍스트 한 줄을 내보낸 뒤 kill 될 때까지 멈춰 있는 가짜 opencode 자식(긴 응답 생성 중 상황).
 * writeDelayMs 뒤에 그 줄을 쓴다 — 0 이면 spawn 이 돌아오기 전에 이미 써 둔다(제너레이터가 루프에 들어가기 전, WD-473).
 */
function makeHangingChild(writeDelayMs = 30) {
  const stdout = new PassThrough();
  const child = new EventEmitter() as EventEmitter & {
    stdout: PassThrough;
    stderr: EventEmitter;
    kill: ReturnType<typeof vi.fn>;
  };
  child.stdout = stdout;
  child.stderr = new EventEmitter();
  // 실제 프로세스처럼 kill 되면 (조금 뒤) stdout 이 닫힌다. 즉시 닫으면 제너레이터가 readline 을 구독하기 전에
  // close 가 지나가 버려 영원히 기다린다(agent-opencode-guards.test.ts 의 makeFakeChild 주석과 같은 이유).
  child.kill = vi.fn(() => {
    setTimeout(() => stdout.end(), 50);
    return true;
  });
  setTimeout(() => {
    if (child.kill.mock.calls.length > 0) return; // 이미 kill 됐다
    stdout.write(
      JSON.stringify({ sessionID: 'ses_abort', type: 'text', part: { type: 'text', text: '중단 전까지의 답' } }) + '\n',
    );
  }, writeDelayMs);
  return child;
}

const CREDENTIALS: OpenCodeCredentials = {
  providerId: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: 'sk-tenant-key',
};

describe('executeOpenCodeAgent — 명시적 취소 (WD-57)', () => {
  useTempHome('agent-opencode-abort');
  const xdgDirs: string[] = [];
  afterAll(async () => {
    for (const d of xdgDirs) await rm(d, { recursive: true, force: true }).catch(() => {});
  });

  it('abort 되면 자식을 SIGTERM 으로 kill 하고, 그때까지의 응답을 트랜스크립트에 저장한다', async () => {
    const child = makeHangingChild();
    spawnMock.mockReturnValue(child);
    const controller = new AbortController();
    const options: ChatProviderOptions = {
      message: '긴 보고서를 써 줘',
      tenantId: 3,
      userId: 7,
      model: 'openai/gpt-4o',
      abortSignal: controller.signal,
    };

    const events: SSEEvent[] = [];
    let sessionId = '';
    for await (const ev of executeOpenCodeAgent(options, CREDENTIALS)) {
      events.push(ev);
      if (ev.type === 'init') sessionId = String(ev.sessionId);
      // 부분 응답을 받은 시점에 사용자가 중단한다
      if (ev.type === 'text') controller.abort();
    }
    const env = (spawnMock.mock.calls[0][2] as { env?: Record<string, string> }).env;
    if (env?.XDG_CONFIG_HOME) xdgDirs.push(env.XDG_CONFIG_HOME);

    expect(child.kill).toHaveBeenCalledWith('SIGTERM');
    const loaded = await readCliTranscript(3, sessionId);
    expect(loaded?.transcript.messages.map((m) => [m.role, m.content])).toEqual([
      ['user', '긴 보고서를 써 줘'],
      ['assistant', '중단 전까지의 답'],
    ]);
  });

  /**
   * 루프에 들어가기 전에 자식이 쓴 줄도 놓치지 않는다(WD-473).
   * 예전에는 init yield·claimSession 뒤에야 줄 반복자를 꺼내, 그 사이 도착한 줄이 버려졌다 — 첫 응답이 사라져 중단도 못 하고
   * 자식이 열린 채 요청이 끝나지 않았다(부하로 준비가 늦어지면 위 테스트가 5초 제한에 걸리던 원인). 줄을 spawn 직후 쓰고
   * 소비자가 init 처리에 시간을 쓰게 해 그 구간을 결정적으로 만든다.
   */
  it('init 을 처리하는 사이(루프 전)에 자식이 쓴 줄도 받는다', { timeout: 2000 }, async () => {
    const child = makeHangingChild(0);
    spawnMock.mockReturnValue(child);
    const controller = new AbortController();

    const texts: string[] = [];
    for await (const ev of executeOpenCodeAgent(
      { message: '빠른 첫 응답', tenantId: 3, userId: 7, model: 'openai/gpt-4o', abortSignal: controller.signal },
      CREDENTIALS,
    )) {
      // SSE 를 보내는 소비자처럼 init 에서 잠시 머문다 — 그동안 자식의 첫 줄이 이미 도착해 있다
      if (ev.type === 'init') await new Promise((r) => setTimeout(r, 50));
      if (ev.type === 'text') {
        texts.push(String(ev.content));
        controller.abort();
      }
    }
    const env = (spawnMock.mock.calls.at(-1)![2] as { env?: Record<string, string> }).env;
    if (env?.XDG_CONFIG_HOME) xdgDirs.push(env.XDG_CONFIG_HOME);

    expect(texts).toEqual(['중단 전까지의 답']);
    expect(child.kill).toHaveBeenCalledWith('SIGTERM');
  });

  it('실행 준비 중(spawn 전)에 이미 취소됐으면 spawn 직후 바로 kill 한다(리스너만으로는 놓친다)', async () => {
    const child = makeHangingChild();
    spawnMock.mockReturnValue(child);
    const controller = new AbortController();
    controller.abort();

    for await (const _ of executeOpenCodeAgent(
      { message: '취소된 요청', tenantId: 3, userId: 7, model: 'openai/gpt-4o', abortSignal: controller.signal },
      CREDENTIALS,
    )) {
      // 소비만 한다 — kill 로 stdout 이 닫혀야 루프가 끝난다
    }
    const env = (spawnMock.mock.calls.at(-1)![2] as { env?: Record<string, string> }).env;
    if (env?.XDG_CONFIG_HOME) xdgDirs.push(env.XDG_CONFIG_HOME);

    expect(child.kill).toHaveBeenCalledWith('SIGTERM');
    // 루프가 끝났다 = 첫 kill 이 finally 가 아니라 aborted 확인에서 왔다(아니면 stdout 이 열린 채 영원히 기다린다)
  });
});
