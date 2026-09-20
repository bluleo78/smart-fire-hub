/**
 * executeOpenCodeAgent 의 자식 프로세스 배선(env/디스크) 테스트.
 *
 * agent-opencode.test.ts 는 순수 함수(buildOpenCodeConfig 등)만 다루므로, spawn() 을 모킹해야
 * 하는 이 관심사(Ruling #33 Part 1 배선 + Part 4 env 스크럽)는 별도 파일로 분리한다 — 기존
 * 파일에 child_process 모킹을 끼워 넣으면 그 파일의 나머지 20여 개 순수 함수 테스트의 훅 구조를
 * 건드리게 된다.
 *
 * 검증 대상:
 *  - opencode.json 을 더 이상 디스크에 쓰지 않고 OPENCODE_CONFIG_CONTENT(자식 env)로만 전달한다
 *    (비밀 apiKey 가 이제 config 에 포함되므로 — Interfaces 절 "committed").
 *  - userWorkDir 에 남아 있던 과거 opencode.json 을 지운다(세션 간 재사용 디렉터리라 과거 버전이
 *    남겼을 수 있다).
 *  - 컨테이너의 ambient ANTHROPIC_API_KEY/CLAUDE_CODE_OAUTH_TOKEN 을 자식 env 에서 제거한다
 *    (Ruling #33 Part 4) — opencode 본체는 provider 블록으로 이미 테넌트 자격증명을 받았으므로
 *    이 값들이 남아 있으면 플랫폼 Anthropic 계정으로 과금이 새는 6b1c6383 과 같은 회귀가 된다.
 */
import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest';
import { EventEmitter } from 'events';
import { Readable } from 'stream';
import { existsSync } from 'fs';
import { mkdir, rm, writeFile } from 'fs/promises';
import { join } from 'path';
import { tmpdir } from 'os';
import { useTempHome } from './temp-home.fixture.js';
import type { ChatProviderOptions } from '../providers/types.js';
import type { OpenCodeCredentials } from './agent-opencode.js';

// child_process.spawn 을 모킹해 실제 opencode CLI 를 띄우지 않고 인자/env 만 검증한다
// (agent-cli.test.ts 와 같은 패턴).
const spawnMock = vi.fn();
vi.mock('child_process', () => ({
  spawn: (...args: unknown[]) => spawnMock(...args),
}));

import { executeOpenCodeAgent } from './agent-opencode.js';
import { opencodeWorkspaceDir } from './tenant-paths.js';

/** spawn() 이 돌려줄 가짜 child — readline 이 즉시 끝나도록 빈 stdout, stderr 는 EventEmitter. */
function makeFakeChild() {
  const stdout = Readable.from([]);
  const stderr = new EventEmitter() as EventEmitter & { on: EventEmitter['on'] };
  const child = new EventEmitter() as EventEmitter & {
    stdout: Readable;
    stderr: EventEmitter;
    kill: (sig?: string) => void;
  };
  child.stdout = stdout;
  child.stderr = stderr;
  child.kill = vi.fn();
  return child;
}

/**
 * 자식 env 에서 반드시 제거돼야 하는 키 전체 목록(보안 리뷰 Fix4 + 재검토 N6).
 *
 * <p>왜: opencode 본체는 OPENCODE_CONFIG_CONTENT 의 provider 블록으로 테넌트 자격증명을 이미
 * 받았다. 컨테이너에 남은 ambient 자격증명/설정이 자식에게 상속되면, opencode(또는 그 안에서
 * 로드되는 provider)가 그 공유 값으로 테넌트 설정을 조용히 대체해 플랫폼 계정으로 과금하는
 * 6b1c6383 과 같은 회귀가 된다. ANTHROPIC_API_KEY 만 지우고 ANTHROPIC_AUTH_TOKEN/
 * OPENAI_API_KEY 를 남기거나, IRSA 환경에서 정적 AWS 키만 지우는 것은 같은 사고를 이름만 바꿔
 * 재현한다(재검토 N6).
 */
const DENIED_ENV_KEYS = [
  'INTERNAL_SERVICE_TOKEN',
  'ANTHROPIC_API_KEY',
  'CLAUDE_CODE_OAUTH_TOKEN',
  'OPENCODE_CONFIG',
  'OPENCODE_PERMISSION',
  'BEDROCK_API_KEY',
  'AWS_BEARER_TOKEN_BEDROCK',
  'AWS_ACCESS_KEY_ID',
  'AWS_SECRET_ACCESS_KEY',
  'AWS_SESSION_TOKEN',
  'AWS_PROFILE',
  // 재검토 N6 에서 추가된 키들
  'OPENAI_API_KEY',
  'ANTHROPIC_AUTH_TOKEN',
  'ANTHROPIC_BASE_URL',
  'AWS_WEB_IDENTITY_TOKEN_FILE',
  'AWS_ROLE_ARN',
  'AWS_CONTAINER_CREDENTIALS_RELATIVE_URI',
  'AWS_CONTAINER_CREDENTIALS_FULL_URI',
  'AWS_SHARED_CREDENTIALS_FILE',
  'AWS_CONFIG_FILE',
] as const;

const OPTIONS: ChatProviderOptions = {
  message: '안녕',
  tenantId: 3,
  userId: 7,
  model: 'openai/gpt-4o',
};

const CREDENTIALS: OpenCodeCredentials = {
  providerId: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: 'sk-tenant-key',
  reasoningEffort: 'medium',
};

describe('executeOpenCodeAgent — 자식 프로세스 env/디스크 배선', () => {
  const home = useTempHome('agent-opencode-env');
  /** driveToInit 이 만든 제너레이터 — afterEach 에서 return() 해 finally(정리)를 돌린다. */
  let openGenerators: AsyncGenerator<unknown>[] = [];

  beforeEach(() => {
    spawnMock.mockReset();
    spawnMock.mockReturnValue(makeFakeChild());
    openGenerators = [];
  });

  /** 테스트들이 실제로 쓴 XDG_CONFIG_HOME 값들 — afterAll 정리 대상(beforeEach 의 mockReset 이 지우기 전에 모은다). */
  const usedXdgDirs = new Set<string>();

  afterEach(async () => {
    for (const gen of openGenerators) await gen.return(undefined).catch(() => {});
    for (const call of spawnMock.mock.calls) {
      const dir = (call[2] as { env?: Record<string, string> })?.env?.XDG_CONFIG_HOME;
      if (dir) usedXdgDirs.add(dir);
    }
  });

  /**
   * 프로덕션 코드는 XDG 디렉터리를 프로세스 수명 동안 지우지 않는다(의도된 동작) — 그래서
   * 테스트 프로세스에도 그대로 남는다. 테스트가 OS 임시 디렉터리를 어지럽히지 않도록 여기서만
   * 치운다(vi.resetModules 로 만든 fresh module 의 디렉터리까지 포함해 실제로 쓰인 값 전부).
   */
  afterAll(async () => {
    for (const dir of usedXdgDirs) await rm(dir, { recursive: true, force: true }).catch(() => {});
  });

  /**
   * 첫 yield(init) 까지만 진행 — spawn 호출은 그 이전에 이미 끝나 있다.
   *
   * <p>여기서 만든 제너레이터는 afterEach 가 `return()` 으로 정리한다 — 그냥 버리면
   * executeOpenCodeAgent 의 finally 가 돌지 않아 가짜 자식 프로세스가 kill 되지 않은 채 남는다
   * (실제 배포에서는 opencode 서브프로세스가 좀비로 남는 모양이다).
   */
  async function driveToInit(options = OPTIONS, credentials = CREDENTIALS) {
    const gen = executeOpenCodeAgent(options, credentials);
    openGenerators.push(gen);
    const first = await gen.next();
    expect(first.done).toBe(false);
    expect(first.value).toMatchObject({ type: 'init' });
    return gen;
  }

  /** spawn 에 넘어간 env 를 꺼낸다(호출 순서 index). */
  function spawnEnv(index = 0): Record<string, string> {
    const [, , opts] = spawnMock.mock.calls[index] as [string, string[], { env: Record<string, string> }];
    return opts.env;
  }

  it('opencode.json 을 디스크에 쓰지 않고 OPENCODE_CONFIG_CONTENT 로만 전달한다', async () => {
    await driveToInit();

    expect(spawnMock).toHaveBeenCalledTimes(1);
    const [, , opts] = spawnMock.mock.calls[0] as [string, string[], { env: Record<string, string> }];
    const configContent = opts.env.OPENCODE_CONFIG_CONTENT;
    expect(configContent).toBeTruthy();
    const parsed = JSON.parse(configContent);
    expect(parsed.provider.openai.options).toEqual({
      baseURL: 'https://api.openai.com/v1',
      apiKey: 'sk-tenant-key',
    });
    expect(parsed.model).toBe('openai/gpt-4o');

    const workDir = opencodeWorkspaceDir(OPTIONS.tenantId, OPTIONS.userId);
    expect(existsSync(join(workDir, 'opencode.json'))).toBe(false);
  });

  it('userWorkDir 에 남아있던 과거 opencode.json 파일을 지운다', async () => {
    const workDir = opencodeWorkspaceDir(OPTIONS.tenantId, OPTIONS.userId);
    await mkdir(workDir, { recursive: true });
    await writeFile(join(workDir, 'opencode.json'), '{"stale":true}', 'utf-8');
    expect(existsSync(join(workDir, 'opencode.json'))).toBe(true);

    await driveToInit();

    expect(existsSync(join(workDir, 'opencode.json'))).toBe(false);
  });

  it('컨테이너의 ambient ANTHROPIC_API_KEY/CLAUDE_CODE_OAUTH_TOKEN 을 자식 env 에서 제거한다', async () => {
    const originalKey = process.env.ANTHROPIC_API_KEY;
    const originalOauth = process.env.CLAUDE_CODE_OAUTH_TOKEN;
    process.env.ANTHROPIC_API_KEY = 'ambient-must-not-leak';
    process.env.CLAUDE_CODE_OAUTH_TOKEN = 'ambient-oauth-must-not-leak';

    try {
      await driveToInit();

      const [, , opts] = spawnMock.mock.calls[0] as [string, string[], { env: Record<string, string> }];
      // in 연산자로 확인한다 — 빈 문자열('')과 부재(undefined)를 구분해야 한다(원래 버그가 정확히
      // "빈 apiKey" 모양이었다). 값이 있는 채로 남아 있어도, 지우려다 빈 문자열로만 덮어써도 이
      // 단언은 FALSE 가 되어야 한다.
      expect('ANTHROPIC_API_KEY' in opts.env).toBe(false);
      expect('CLAUDE_CODE_OAUTH_TOKEN' in opts.env).toBe(false);
    } finally {
      if (originalKey === undefined) delete process.env.ANTHROPIC_API_KEY;
      else process.env.ANTHROPIC_API_KEY = originalKey;
      if (originalOauth === undefined) delete process.env.CLAUDE_CODE_OAUTH_TOKEN;
      else process.env.CLAUDE_CODE_OAUTH_TOKEN = originalOauth;
    }
  });

  it('INTERNAL_SERVICE_TOKEN 을 opencode 본체 env 에서 제거한다 (자식 MCP 에만 전달)', async () => {
    const original = process.env.INTERNAL_SERVICE_TOKEN;
    process.env.INTERNAL_SERVICE_TOKEN = 'must-not-reach-opencode-body';
    try {
      await driveToInit();
      const [, , opts] = spawnMock.mock.calls[0] as [string, string[], { env: Record<string, string> }];
      expect('INTERNAL_SERVICE_TOKEN' in opts.env).toBe(false);
    } finally {
      if (original === undefined) delete process.env.INTERNAL_SERVICE_TOKEN;
      else process.env.INTERNAL_SERVICE_TOKEN = original;
    }
  });

  /**
   * 보안 리뷰 Fix4 — OPENCODE_CONFIG/OPENCODE_PERMISSION(opencode 자체 전역 설정 환경변수
   * 관례)과, 이 리포에는 참조가 없는데도 실제 배포 차트(k8s/smart-fire-hub/templates/
   * aiagent-deployment.yaml)가 컨테이너 env 로 주입하는 BEDROCK_API_KEY(및 AWS SDK 자격증명
   * 체인이 fallback 으로 보는 나머지 변수)가 남아 있으면 안 된다. 옛 배포 런북을 따른 환경은
   * 이 값들을 여전히 갖고 있을 수 있으므로(deploy.md 가 이제 심지 말라고 적어도), 이 프로세스가
   * 스스로 지운다 — 남아 있으면 opencode(또는 그 안에서 로드되는 provider)가 공유 게이트웨이
   * 자격증명으로 테넌트의 opencode 설정을 조용히 덮어쓸 수 있다(Ruling #34).
   */
  /**
   * 키마다 독립된 케이스로 돌린다(재검토 N6) — 하나의 it 안에서 루프로 단언하면 첫 실패에서
   * 멈춰 "어느 키가 빠졌는지"가 한 건의 RED 로 뭉개진다. it.each 면 구현에서 delete 한 줄을
   * 지웠을 때 <b>정확히 그 키의 케이스만</b> 빨간불이 된다.
   */
  it.each(DENIED_ENV_KEYS)('%s 를 자식 env 에서 제거한다', async (key) => {
    const original = process.env[key];
    process.env[key] = `ambient-${key}-must-not-leak`;
    try {
      await driveToInit();
      // in 연산자로 확인한다 — 빈 문자열로 덮어써도(값은 있지만 비어 있음) 이 단언은 통과하면
      // 안 된다. 반드시 키 자체가 없어야 한다.
      expect(key in spawnEnv()).toBe(false);
    } finally {
      if (original === undefined) delete process.env[key];
      else process.env[key] = original;
    }
  });

  // -------------------------------------------------------------------------
  // 재검토 N5/D1 — 자식의 XDG_CONFIG_HOME 을 ai-agent 프로세스 전용 임시 디렉터리로 돌린다
  // -------------------------------------------------------------------------

  /**
   * OPENCODE_CONFIG env 를 지워도 opencode CLI 는 기본 경로
   * `$XDG_CONFIG_HOME/opencode/opencode.json`(미설정이면 `$HOME/.config/opencode/opencode.json`)
   * 을 읽고, 배포 차트가 정확히 거기에 PVC 를 마운트한다 — 그래서 XDG_CONFIG_HOME 자체를
   * PVC 밖의 임시 디렉터리로 돌려 전역 설정 디렉터리를 자식에게서 숨긴다.
   */
  it('자식 env 의 XDG_CONFIG_HOME 이 PVC 밖 임시 디렉터리로 설정된다', async () => {
    await driveToInit();

    const xdg = spawnEnv().XDG_CONFIG_HOME;
    expect(xdg).toBeTruthy();
    expect(existsSync(xdg)).toBe(true);
    // OS 임시 디렉터리 아래여야 한다 — PVC 마운트 경로(HOME/.config)가 아님이 경로만으로 자명해야 한다.
    expect(xdg.startsWith(tmpdir())).toBe(true);
  });

  /**
   * 핵심 단언 — 자식의 설정 디렉터리가 HOME 아래(`~/.config`, 배포에서 PVC 가 마운트되는 자리)
   * 를 가리키면 안 된다. 이 단언이 통과하려면 XDG_CONFIG_HOME 이 실제로 다른 곳이어야 한다.
   */
  it('XDG_CONFIG_HOME 은 HOME/.config(PVC 마운트 자리) 하위가 아니다', async () => {
    await driveToInit();

    const xdg = spawnEnv().XDG_CONFIG_HOME;
    expect(xdg.startsWith(join(home.path, '.config'))).toBe(false);
  });

  /**
   * HOME 은 건드리지 않는다 — opencode 세션 상태가 `~/.local/share/opencode` 에 있어 HOME 을
   * 갈아끼우면 `--session` 재개가 매 요청 깨진다. 끊는 것은 설정 디렉터리 하나뿐이다.
   */
  it('HOME 과 XDG_DATA_HOME 은 바꾸지 않는다 (--session 재개·마운트 보존)', async () => {
    const originalData = process.env.XDG_DATA_HOME;
    delete process.env.XDG_DATA_HOME;
    try {
      await driveToInit();
      const env = spawnEnv();
      expect(env.HOME).toBe(process.env.HOME);
      expect('XDG_DATA_HOME' in env).toBe(false);
    } finally {
      if (originalData === undefined) delete process.env.XDG_DATA_HOME;
      else process.env.XDG_DATA_HOME = originalData;
    }
  });

  /**
   * 재검토 D1 — 디렉터리는 <b>요청별이 아니라 프로세스당 1개</b>여야 한다.
   *
   * <p>왜: opencode 는 이 디렉터리에 플러그인 npm 트리(실측 61MB/3,648 파일)를 스스로 설치한다.
   * 요청마다 새 빈 디렉터리를 주면 그 설치가 매 채팅에 콜드로 재실행된다(빈 4.35s vs 채워진
   * 0.63s, 레지스트리 차단 시 71s). 보안 요건은 "PVC 설정 디렉터리가 아닐 것" 하나뿐이라
   * 요청별일 필요가 없다.
   */
  it('요청 간 같은 임시 디렉터리를 재사용한다', async () => {
    spawnMock.mockReturnValue(makeFakeChild());
    await driveToInit();
    spawnMock.mockReturnValue(makeFakeChild());
    await driveToInit();

    expect(spawnEnv(0).XDG_CONFIG_HOME).toBe(spawnEnv(1).XDG_CONFIG_HOME);
  });

  /**
   * 동시 첫 요청 2건이 디렉터리를 둘 만들면 안 된다 — 캐시가 문자열이면
   * (`if (!dir) dir = await mkdtemp(...)`) 두 요청이 모두 await 지점을 통과해 경합한다.
   *
   * <p>모듈 캐시를 <b>리셋한 뒤</b> 재import 해야 "첫 요청"이 재현된다. 이미 초기화된 싱글턴을
   * 상대로는 어떤 구현이든 통과하므로, 이 테스트는 반드시 fresh module 에서 돌려야 의미가 있다.
   */
  it('동시 첫 요청 2건이 같은 디렉터리를 쓴다 (생성 경합 없음)', async () => {
    vi.resetModules();
    const fresh = await import('./agent-opencode.js');

    spawnMock.mockReturnValue(makeFakeChild());
    const genA = fresh.executeOpenCodeAgent(OPTIONS, CREDENTIALS);
    spawnMock.mockReturnValue(makeFakeChild());
    const genB = fresh.executeOpenCodeAgent(OPTIONS, CREDENTIALS);
    openGenerators.push(genA, genB);

    // 두 요청을 같은 tick 에 출발시켜 mkdtemp 를 동시에 밟게 한다.
    await Promise.all([genA.next(), genB.next()]);

    const dirs = spawnMock.mock.calls.map(
      (c) => (c[2] as { env: Record<string, string> }).env.XDG_CONFIG_HOME,
    );
    expect(dirs).toHaveLength(2);
    expect(dirs[0]).toBe(dirs[1]);
  });

  /**
   * 프로세스 공용이므로 <b>요청이 끝나도 지우지 않는다</b> — 지우면 다른 요청의 부트스트랩
   * 캐시까지 날아가 D1 이 그대로 재발한다. 반대로 자식 프로세스는 반드시 정리돼야 한다.
   */
  it('제너레이터를 버려도 임시 디렉터리는 남고 자식만 정리된다', async () => {
    const child = makeFakeChild();
    spawnMock.mockReturnValue(child);
    const gen = executeOpenCodeAgent(OPTIONS, CREDENTIALS);
    await gen.next();
    const xdg = spawnEnv().XDG_CONFIG_HOME;
    expect(existsSync(xdg)).toBe(true);

    // return() 이 finally 를 돌린다. finally 가 (done 이벤트를 못 봤으므로) error 를 한 번 더
    // yield 하므로 done 이 될 때까지 소진한다.
    let step = await gen.return(undefined);
    while (!step.done) step = await gen.next();

    expect(existsSync(xdg)).toBe(true);
    // try 가 첫 yield 앞에서 시작해야만 이 kill 이 일어난다(재검토 N5 범위 확대 고정).
    expect(child.kill).toHaveBeenCalled();
  });

  // home 픽스처를 실제로 사용해 lint no-unused-vars 를 피한다 — HOME 이 매 테스트 임시 디렉터리로
  // 갈리는지는 opencodeWorkspaceDir 의 존재/삭제 검증을 통해 간접적으로 이미 확인된다.
  it('임시 HOME 픽스처가 설정되어 있다', () => {
    expect(home.path).toBeTruthy();
  });
});
