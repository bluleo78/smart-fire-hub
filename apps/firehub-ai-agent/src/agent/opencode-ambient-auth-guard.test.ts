import { mkdtemp, mkdir, writeFile, rm } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  ALLOW_AMBIENT_AUTH_VAR,
  assertNoAmbientOpencodeAuth,
  opencodeDataDir,
} from './opencode-ambient-auth-guard.js';
import { AdminActionableError } from './admin-actionable-error.js';

/**
 * opencode ambient 인증 파일 가드 (이슈 #697).
 *
 * <p>실제 파일시스템을 쓴다 — 이 가드의 전부가 "그 경로에 파일이 있느냐"라서, `fs` 를 모킹하면
 * 정작 검증하려던 경로 계산이 테스트에서 빠진다.
 */
describe('assertNoAmbientOpencodeAuth', () => {
  let dataHome: string;

  beforeEach(async () => {
    dataHome = await mkdtemp(join(tmpdir(), 'firehub-auth-guard-'));
  });

  afterEach(async () => {
    await rm(dataHome, { recursive: true, force: true });
  });

  it('AUTH-01: 파일이 없으면 통과한다 — 정상 배포에서는 이 가드가 발동하지 않는다', async () => {
    await expect(
      assertNoAmbientOpencodeAuth({ XDG_DATA_HOME: dataHome }, {}),
    ).resolves.toBeUndefined();
  });

  it('AUTH-02: auth.json 이 있으면 그 요청을 막는다', async () => {
    await mkdir(join(dataHome, 'opencode'), { recursive: true });
    await writeFile(join(dataHome, 'opencode', 'auth.json'), '{}');

    // 어느 파일이 걸렸는지는 로그용 detail 에 있다 — 사용자 메시지에는 경로를 담지 않는다(AUTH-05b).
    const err = await assertNoAmbientOpencodeAuth({ XDG_DATA_HOME: dataHome }, {}).catch((e) => e);

    expect(err).toBeInstanceOf(Error);
    expect(err.detail).toMatch(/auth\.json/);
  });

  it('AUTH-03: mcp-auth.json 도 같은 부류로 막는다', async () => {
    await mkdir(join(dataHome, 'opencode'), { recursive: true });
    await writeFile(join(dataHome, 'opencode', 'mcp-auth.json'), '{}');

    const err = await assertNoAmbientOpencodeAuth({ XDG_DATA_HOME: dataHome }, {}).catch((e) => e);

    expect(err).toBeInstanceOf(Error);
    expect(err.detail).toMatch(/mcp-auth\.json/);
  });

  it('AUTH-04: 로컬 개발 탈출구를 켜면 통과한다 — 운영에는 설정하지 않는다', async () => {
    await mkdir(join(dataHome, 'opencode'), { recursive: true });
    await writeFile(join(dataHome, 'opencode', 'auth.json'), '{}');

    await expect(
      assertNoAmbientOpencodeAuth({ XDG_DATA_HOME: dataHome }, { [ALLOW_AMBIENT_AUTH_VAR]: '1' }),
    ).resolves.toBeUndefined();
  });

  it('AUTH-05: 탈출구는 정확히 "1" 일 때만 열린다 — "true"/"0" 같은 값으로 우연히 열리면 안 된다', async () => {
    await mkdir(join(dataHome, 'opencode'), { recursive: true });
    await writeFile(join(dataHome, 'opencode', 'auth.json'), '{}');

    await expect(
      assertNoAmbientOpencodeAuth({ XDG_DATA_HOME: dataHome }, { [ALLOW_AMBIENT_AUTH_VAR]: 'true' }),
    ).rejects.toThrow();
  });

  it('AUTH-05b: 사용자 메시지에는 파일 경로를 담지 않는다 — 채팅 사용자가 관리자라는 보장이 없다', async () => {
    await mkdir(join(dataHome, 'opencode'), { recursive: true });
    await writeFile(join(dataHome, 'opencode', 'auth.json'), '{}');

    // 이 오류만 채팅 SSE 로 문구가 그대로 나간다(routes/chat.ts) — 경로가 섞이면 내부 구조가 샌다.
    const err = await assertNoAmbientOpencodeAuth({ XDG_DATA_HOME: dataHome }, {}).catch((e) => e);

    expect(err).toBeInstanceOf(AdminActionableError);
    expect(err.message).not.toContain(dataHome);
    expect(err.message).not.toContain('auth.json');
    // 경로는 로그용 detail 에만 있다.
    expect(err.detail).toContain('auth.json');
  });

  it('AUTH-06: XDG_DATA_HOME 이 없으면 HOME 기준 기본 경로를 본다 (opencode 의 규칙과 같게)', () => {
    expect(opencodeDataDir({ HOME: '/home/appuser' })).toBe(
      '/home/appuser/.local/share/opencode',
    );
    expect(opencodeDataDir({ XDG_DATA_HOME: '/data', HOME: '/home/appuser' })).toBe(
      '/data/opencode',
    );
  });
});
