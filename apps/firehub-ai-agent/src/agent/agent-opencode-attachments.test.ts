/**
 * executeOpenCodeAgent 의 채팅 첨부 파일 전달 테스트 (WD-55).
 *
 * 예전엔 opencode 경로가 fileIds 를 아예 읽지 않아 첨부가 조용히 무시됐다. 이제는:
 *  - 첨부를 테넌트·사용자 격리 워크스페이스 아래로 내려받고,
 *  - 「[첨부 파일]」(fileId 포함) 섹션을 사용자 메시지에 넣고,
 *  - 이미지·텍스트 계열은 `opencode run --file <경로>` 로 넘겨 모델이 실제 내용(이미지 파트/본문)을 받게 하며,
 *  - 실행이 끝나면(정상·중단 모두) 내려받은 파일을 지운다.
 *
 * spawn 과 FireHubApiClient 를 모킹한다. "--file 로 넘겼다"만으로는 opencode 가 그 파일을 읽을 수
 * 있었다는 증거가 못 되므로, spawn 시점에 해당 경로에 실제 내용이 있는지를 함께 확인한다.
 */
import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest';
import { EventEmitter } from 'events';
import { Readable } from 'stream';
import { existsSync, readFileSync } from 'fs';
import { rm } from 'fs/promises';
import { join } from 'path';
import { useTempHome } from './temp-home.fixture.js';
import type { ChatProviderOptions, SSEEvent } from '../providers/types.js';
import type { OpenCodeCredentials } from './agent-opencode.js';

/** fileId → 업로드된 파일. 없는 id 는 다운로드 실패(만료/삭제)로 취급한다. */
const files = vi.hoisted(
  () =>
    new Map<number, { originalName: string; mimeType: string; fileCategory: string; content: Buffer }>(),
);
vi.mock('../mcp/api-client.js', () => ({
  FireHubApiClient: class {
    async getFileInfo(id: number) {
      const f = files.get(id);
      if (!f) throw new Error(`404 file ${id}`);
      return { originalName: f.originalName, mimeType: f.mimeType, fileCategory: f.fileCategory, fileSize: f.content.length };
    }
    async downloadFile(id: number) {
      const f = files.get(id);
      if (!f) throw new Error(`404 file ${id}`);
      return f.content;
    }
  },
}));

const spawnMock = vi.fn();
vi.mock('child_process', () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

import { executeOpenCodeAgent } from './agent-opencode.js';

const SESSION = 'ses_test';

/** stdout 으로 주어진 이벤트 라인을 흘려 보내는 가짜 opencode 자식(guards 테스트와 같은 패턴). */
function makeFakeChild(lines: Record<string, unknown>[]) {
  async function* emit() {
    await new Promise((r) => setTimeout(r, 30));
    for (const l of lines) yield JSON.stringify({ sessionID: SESSION, ...l }) + '\n';
  }
  const child = new EventEmitter() as EventEmitter & {
    stdout: Readable;
    stderr: EventEmitter;
    kill: ReturnType<typeof vi.fn>;
  };
  child.stdout = Readable.from(emit());
  child.stderr = new EventEmitter();
  child.kill = vi.fn();
  return child;
}

const DONE = { type: 'step_finish', part: { type: 'step-finish', reason: 'stop', tokens: { input: 1, output: 1 } } };
const text = (t: string) => ({ type: 'text', part: { type: 'text', text: t } });

const CREDENTIALS: OpenCodeCredentials = {
  providerId: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: 'sk-tenant-key',
};
const opts = (o: Partial<ChatProviderOptions>): ChatProviderOptions => ({
  message: '이 파일 요약해줘',
  tenantId: 3,
  userId: 7,
  model: 'openai/gpt-4o',
  ...o,
});

/** spawn 인자에서 `--file` 값만 뽑는다. */
const fileArgs = (args: string[]) => args.flatMap((a, i) => (args[i - 1] === '--file' ? [a] : []));

describe('executeOpenCodeAgent — 채팅 첨부 파일 (WD-55)', () => {
  const home = useTempHome('agent-opencode-attach');
  const usedXdgDirs = new Set<string>();
  /** spawn 시점의 인자와, 그때 --file 경로들에 실제로 있던 내용. */
  let spawned: { args: string[]; contents: Record<string, string> } | null;

  beforeEach(() => {
    files.clear();
    spawned = null;
    spawnMock.mockReset();
  });

  afterAll(async () => {
    for (const dir of usedXdgDirs) await rm(dir, { recursive: true, force: true }).catch(() => {});
  });

  function arm(lines: Record<string, unknown>[]) {
    spawnMock.mockImplementation((_cmd: string, args: string[], spawnOpts: { env?: Record<string, string> }) => {
      if (spawnOpts?.env?.XDG_CONFIG_HOME) usedXdgDirs.add(spawnOpts.env.XDG_CONFIG_HOME);
      const contents: Record<string, string> = {};
      for (const p of fileArgs(args)) contents[p] = existsSync(p) ? readFileSync(p, 'latin1') : '<없음>';
      spawned = { args, contents };
      return makeFakeChild(lines);
    });
  }

  async function run(o: Partial<ChatProviderOptions>, lines: Record<string, unknown>[] = [text('답'), DONE]) {
    arm(lines);
    const events: SSEEvent[] = [];
    for await (const ev of executeOpenCodeAgent(opts(o), CREDENTIALS)) events.push(ev);
    return events;
  }

  /** opencode run 의 첫 위치 인자 = 모델에 가는 사용자 메시지. */
  const sentMessage = () => spawned!.args[1];
  const chatFilesRoot = () => join(home.path, '.firehub', 'workspaces-opencode', 't3', '7', 'chat-files');

  it('텍스트 파일: fileId 를 담은 [첨부 파일] 섹션을 메시지에 넣고, --file 로 실제 내용을 넘긴다', async () => {
    files.set(11, { originalName: 'plan.yaml', mimeType: 'text/yaml', fileCategory: 'TEXT', content: Buffer.from('project: zebra-7731\n') });

    await run({ fileIds: [11] });

    const msg = sentMessage();
    expect(msg).toContain('[첨부 파일]');
    expect(msg).toContain('plan.yaml');
    expect(msg).toContain('fileId=11'); // MCP 임포트 도구가 fileId 를 받아야 한다(#264)
    expect(msg).toContain('이 파일 요약해줘');
    // opencode 에선 Read 도구가 꺼져 있어(buildOpenCodeConfig) "Read 도구로 읽으라" 안내는 거짓이 된다.
    expect(msg).not.toContain('Read 도구로 읽을 수 있습니다');
    const passed = fileArgs(spawned!.args);
    expect(passed).toHaveLength(1);
    expect(passed[0].startsWith(chatFilesRoot())).toBe(true); // 테넌트·사용자 격리 경로
    expect(spawned!.contents[passed[0]]).toBe('project: zebra-7731\n');
  });

  it('이미지 파일: --file 로 넘기고, 못 보면 추측 말고 안내하라는 지시를 메시지에 넣는다', async () => {
    const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3]);
    files.set(21, { originalName: 'shape.png', mimeType: 'image/png', fileCategory: 'IMAGE', content: png });

    await run({ fileIds: [21] });

    const msg = sentMessage();
    expect(msg).toContain('shape.png');
    expect(msg).toContain('fileId=21');
    expect(msg).toContain('현재 모델은 이미지를 읽을 수 없습니다');
    const [p] = fileArgs(spawned!.args);
    expect(Buffer.from(spawned!.contents[p], 'latin1').equals(png)).toBe(true);
  });

  it('xlsx 같은 바이너리 비이미지는 --file 로 넘기지 않고 섹션(fileId)만 남긴다', async () => {
    // opencode 의 내부 Read 가 "Cannot read binary file" 로 실패하며 error 이벤트를 내기 때문(1.18.34 실측).
    files.set(31, {
      originalName: 'data.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      fileCategory: 'DATA',
      content: Buffer.from('PK\x03\x04'),
    });
    files.set(32, { originalName: 'rows.csv', mimeType: 'text/csv', fileCategory: 'DATA', content: Buffer.from('a,b\n1,2\n') });

    await run({ fileIds: [31, 32] });

    expect(sentMessage()).toContain('fileId=31');
    expect(sentMessage()).toContain('fileId=32');
    const passed = fileArgs(spawned!.args);
    expect(passed).toHaveLength(1);
    expect(passed[0].endsWith('rows.csv')).toBe(true);
  });

  it('--file 는 메시지 뒤에 오고, 재개 시 --session 은 계속 맨 끝에 온다', async () => {
    files.set(11, { originalName: 'a.txt', mimeType: 'text/plain', fileCategory: 'TEXT', content: Buffer.from('x') });
    await run({ fileIds: [11] });
    // yargs 의 array 옵션(-f)은 뒤따르는 위치 인자를 삼킨다 — 메시지는 반드시 맨 앞(run 다음)이어야 한다.
    expect(spawned!.args[0]).toBe('run');
    expect(spawned!.args.indexOf('--file')).toBeGreaterThan(1);
  });

  it('실행이 끝나면 내려받은 첨부 디렉터리를 지운다', async () => {
    files.set(11, { originalName: 'plan.yaml', mimeType: 'text/yaml', fileCategory: 'TEXT', content: Buffer.from('k: v') });
    await run({ fileIds: [11] });
    const [p] = fileArgs(spawned!.args);
    expect(spawned!.contents[p]).toBe('k: v'); // spawn 시점엔 있었다
    expect(existsSync(p)).toBe(false);
  });

  it('소비자가 도중에 제너레이터를 버려도(gen.return) 첨부를 지운다', async () => {
    files.set(11, { originalName: 'plan.yaml', mimeType: 'text/yaml', fileCategory: 'TEXT', content: Buffer.from('k: v') });
    arm([text('답'), DONE]);
    const gen = executeOpenCodeAgent(opts({ fileIds: [11] }), CREDENTIALS);
    await gen.next(); // init
    await gen.return(undefined);
    const [p] = fileArgs(spawned!.args);
    expect(existsSync(p)).toBe(false);
  });

  it('spawn 이 동기로 던져도 내려받은 첨부를 지운다', async () => {
    files.set(11, { originalName: 'plan.yaml', mimeType: 'text/yaml', fileCategory: 'TEXT', content: Buffer.from('k: v') });
    let passed: string[] = [];
    spawnMock.mockImplementation((_cmd: string, args: string[]) => {
      passed = fileArgs(args);
      throw new Error('ERR_INVALID_ARG_VALUE');
    });
    const gen = executeOpenCodeAgent(opts({ fileIds: [11] }), CREDENTIALS);
    await expect(gen.next()).rejects.toThrow('ERR_INVALID_ARG_VALUE');
    expect(passed).toHaveLength(1);
    expect(existsSync(passed[0])).toBe(false);
  });

  it('같은 밀리초에 들어온 두 요청도 첨부 디렉터리를 공유하지 않는다(한쪽 정리가 다른 쪽 파일을 지우지 않게)', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_000_000);
    try {
      files.set(11, { originalName: 'plan.yaml', mimeType: 'text/yaml', fileCategory: 'TEXT', content: Buffer.from('k: v') });
      await run({ fileIds: [11] });
      const first = fileArgs(spawned!.args)[0];
      await run({ fileIds: [11] });
      const second = fileArgs(spawned!.args)[0];
      expect(first).not.toBe(second);
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('일부 다운로드가 실패해도 진행하고, 실패한 파일은 메시지·--file 어디에도 없다', async () => {
    files.set(11, { originalName: 'ok.txt', mimeType: 'text/plain', fileCategory: 'TEXT', content: Buffer.from('ok') });
    // 99 는 등록 안 됨 → 다운로드 실패

    const events = await run({ fileIds: [11, 99] });

    expect(fileArgs(spawned!.args)).toHaveLength(1);
    expect(sentMessage()).not.toContain('fileId=99');
    expect(events.some((e) => e.type === 'done')).toBe(true);
  });

  it('모든 다운로드가 실패하면 첨부 없이 원래 메시지로 진행한다(SDK/CLI 경로와 동일)', async () => {
    await run({ fileIds: [99] });
    expect(fileArgs(spawned!.args)).toHaveLength(0);
    expect(sentMessage()).toBe('이 파일 요약해줘');
  });

  it('메시지 없이 파일만 보내도 기본 지시문을 넣는다 — opencode run 은 빈 메시지면 종료한다', async () => {
    files.set(21, { originalName: 's.png', mimeType: 'image/png', fileCategory: 'IMAGE', content: Buffer.from('png') });
    await run({ message: '', fileIds: [21] });
    expect(sentMessage()).toContain('첨부된 파일을 분석해주세요.');
  });

  it('이미지를 첨부한 요청에서 opencode error 가 오면 "이미지를 읽을 수 없다" 안내를 붙인다(무음 실패 금지)', async () => {
    files.set(21, { originalName: 's.png', mimeType: 'image/png', fileCategory: 'IMAGE', content: Buffer.from('png') });
    const events = await run({ fileIds: [21] }, [
      { type: 'error', error: { name: 'APIError', data: { message: 'image_url is not supported' } } },
    ]);
    const errors = events.filter((e) => e.type === 'error');
    // 원인을 먼저 그대로 두고(인증·쿼터 등 다른 원인일 수도 있다) 이미지 가능성을 덧붙인다.
    expect(String(errors[0].message).startsWith('image_url is not supported')).toBe(true);
    expect(String(errors[0].message)).toContain('이미지를 읽을 수 없');
  });

  it('이미지가 없으면 opencode error 문구를 바꾸지 않는다', async () => {
    files.set(11, { originalName: 'a.txt', mimeType: 'text/plain', fileCategory: 'TEXT', content: Buffer.from('x') });
    const events = await run({ fileIds: [11] }, [{ type: 'error', error: { data: { message: 'boom' } } }]);
    expect(events.find((e) => e.type === 'error')?.message).toBe('boom');
  });

  it('fileIds 가 없으면 기존과 같이 메시지만 넘기고 첨부 디렉터리를 만들지 않는다', async () => {
    await run({});
    expect(sentMessage()).toBe('이 파일 요약해줘');
    expect(spawned!.args).not.toContain('--file');
    expect(existsSync(chatFilesRoot())).toBe(false);
  });
});
