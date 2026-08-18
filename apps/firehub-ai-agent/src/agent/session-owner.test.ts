import { describe, it, expect } from 'vitest';
import { mkdir, stat, utimes, writeFile } from 'fs/promises';
import { join } from 'path';
import { useTempHome } from './temp-home.fixture.js';
import { claimSession, checkSessionOwnership } from './session-owner.js';

/**
 * 표식 판정은 실제 디렉터리 구조를 읽는 동작이므로 `fs` 를 목으로 막지 않고 임시 HOME 을 만들어
 * 진짜 파일로 검증한다. 목으로 하면 "readdir 을 몇 번 부르는가" 를 고정하게 되어, 구현을 조금만
 * 바꿔도 깨지면서 정작 판정 결과는 검증하지 못한다.
 *
 * <p>`os.homedir()` 는 모듈 로드 시점이 아니라 호출 시점에 HOME 을 읽으므로 동적 재임포트가
 * 필요 없다 — 평범한 정적 임포트로 충분하다.
 */
describe('session-owner', () => {
  const home = useTempHome('firehub-session-owner');
  // SO-01: 자기 테넌트가 기록한 세션은 owned.
  it('SO-01: reports owned for a session claimed by the same tenant', async () => {
    await claimSession(5, 'sess-a');

    expect(await checkSessionOwnership(5, 'sess-a')).toBe('owned');
  });

  // SO-02: 다른 테넌트가 기록한 세션은 other-tenant — 심층방어가 실제로 잡는 유일한 사례다.
  it('SO-02: reports other-tenant for a session claimed by a different tenant', async () => {
    await claimSession(5, 'sess-a');

    expect(await checkSessionOwnership(9, 'sess-a')).toBe('other-tenant');
  });

  // SO-03: 표식이 없으면 unknown — 세그먼트 도입 전 세션을 거부하면 과거 이력이 전부 사라진다.
  it('SO-03: reports unknown when no marker exists anywhere', async () => {
    await claimSession(5, 'sess-a');

    expect(await checkSessionOwnership(5, 'sess-b')).toBe('unknown');
  });

  // SO-04: 표식 디렉터리 자체가 없어도 unknown 이어야 한다(첫 배포 직후 상태).
  it('SO-04: reports unknown when the marker root does not exist', async () => {
    expect(await checkSessionOwnership(5, 'sess-a')).toBe('unknown');
  });

  // SO-05: 경로 이탈 시도는 판정 단계에서 거부한다 — readdir 결과와 대조하는 방식이라 실제로
  // 파일을 열지는 않지만, 표식 이름으로 쓰일 수 있는 값은 여기서 걸러 두는 편이 안전하다.
  it('SO-05: rejects unsafe session ids instead of treating them as unknown', async () => {
    expect(await checkSessionOwnership(5, '../escape')).toBe('other-tenant');
    // 쓰기 쪽도 무시해야 한다 — 표식 파일이 엉뚱한 위치에 생기지 않는지 확인한다.
    await claimSession(5, '../escape');
    expect(await checkSessionOwnership(5, 'escape')).toBe('unknown');
  });

  // SO-06: 여러 테넌트가 섞여 있어도 소유 테넌트를 정확히 골라낸다.
  it('SO-06: picks the owning tenant among several', async () => {
    await claimSession(2, 'sess-x');
    await claimSession(3, 'sess-y');
    await claimSession(4, 'sess-z');

    expect(await checkSessionOwnership(3, 'sess-y')).toBe('owned');
    expect(await checkSessionOwnership(2, 'sess-y')).toBe('other-tenant');
  });

  // SO-08: 다른 테넌트가 이미 표식을 가진 세션은 두 번째 표식을 만들지 않는다(코드리뷰 지적).
  // `/agent/chat` 은 sessionId 를 클라이언트 값 그대로 받으므로 이 방어가 없으면 표식이 양쪽에 생긴다.
  it('SO-08: refuses to claim a session already marked by another tenant', async () => {
    await claimSession(5, 'sess-a');

    await claimSession(9, 'sess-a');

    expect(await checkSessionOwnership(9, 'sess-a')).toBe('other-tenant');
    expect(await checkSessionOwnership(5, 'sess-a')).toBe('owned');
  });

  // SO-09: 어떤 경로로든 표식이 둘 이상 생겼다면 판정은 **순서와 무관하게** other-tenant 여야 한다.
  // 표식 파일을 직접 심어 claimSession 의 방어를 우회한 상태를 재현한다.
  it('SO-09: a multi-tenant marker resolves to other-tenant regardless of iteration order', async () => {
    const root = join(home.path, '.firehub', 'session-owner');
    await mkdir(join(root, 't5'), { recursive: true });
    await mkdir(join(root, 't9'), { recursive: true });
    await writeFile(join(root, 't5', 'sess-dup'), '');
    await writeFile(join(root, 't9', 'sess-dup'), '');

    // 양쪽 테넌트 모두 거부돼야 한다 — 자기 디렉터리에서 찾자마자 멈추면 한쪽은 owned 가 된다.
    expect(await checkSessionOwnership(5, 'sess-dup')).toBe('other-tenant');
    expect(await checkSessionOwnership(9, 'sess-dup')).toBe('other-tenant');
  });

  // SO-10: 이미 내 표식이 있으면 다시 쓰지 않는다(효율 지적). 매 턴 불리는 함수라 단락이 없으면
  // 멀티턴 대화가 턴마다 전 테넌트 스캔 + 쓰기를 반복한다. 단락은 "아무 일도 안 함" 이라 직접
  // 관측할 수 없으므로, 표식 파일의 mtime 을 과거로 밀어 두고 재호출 후에도 그대로인지로 본다.
  it('SO-10: does not rewrite an existing marker for the same tenant', async () => {
    await claimSession(5, 'sess-a');
    const marker = join(home.path, '.firehub', 'session-owner', 't5', 'sess-a');
    const past = new Date(Date.now() - 60 * 60 * 1000);
    await utimes(marker, past, past);
    const before = (await stat(marker)).mtimeMs;

    await claimSession(5, 'sess-a');

    // 단락했다면 다시 쓰지 않으므로 mtime 이 유지된다. 다시 썼다면 현재 시각으로 갱신된다.
    expect((await stat(marker)).mtimeMs).toBe(before);
  });

  // SO-07: 표식 기록 실패는 채팅을 죽이지 않는다(심층방어이지 1차 게이트가 아니다).
  // 표식 루트 자리에 파일을 놓아 mkdir 을 실패시킨다.
  it('SO-07: never throws when the marker cannot be written', async () => {
    await mkdir(join(home.path, '.firehub'), { recursive: true });
    await writeFile(join(home.path, '.firehub', 'session-owner'), 'not a directory');

    await expect(claimSession(5, 'sess-a')).resolves.toBeUndefined();
    // 판정도 던지지 않고 unknown 으로 떨어져 1차 게이트에 맡긴다.
    expect(await checkSessionOwnership(5, 'sess-a')).toBe('unknown');
  });
});
