/**
 * 테스트용 임시 `HOME` 픽스처.
 *
 * <p><b>왜 공용으로 두는가.</b> `~/.firehub` 아래 경로를 만드는 코드를 검증하는 테스트는
 * `fs` 를 목으로 막는 대신 실제 파일을 쓰는 편이 낫다(목으로 하면 "readdir 을 몇 번 부르나" 를
 * 고정하게 되어 정작 판정 결과를 검증하지 못한다). 그런데 그러면 **개발자의 실제 홈 디렉터리에
 * 쓰게 되므로** 임시 HOME 으로 갈아 끼워야 하고, 그 6~8줄이 파일마다 복붙되면 다음 테스트를
 * 추가하는 사람이 복붙할지 진짜 홈에 쓸지를 매번 재량으로 결정하게 된다(이 리포에는 실제로 홈에
 * 쓰는 기존 테스트가 있다). 픽스처를 하나 두면 그 선택 자체가 없어진다.
 *
 * <p>파일명이 `*.test.ts` 가 아니라 `*.fixture.ts` 인 이유: vitest 가 테스트 파일로 수집하면
 * "테스트 없음" 으로 실패한다.
 */
import { mkdtemp, rm } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, beforeEach } from 'vitest';

/** {@link useTempHome} 가 돌려주는 핸들. `path` 는 각 테스트 시작 시 새 디렉터리로 갱신된다. */
export interface TempHome {
  /** 현재 테스트의 임시 HOME 절대 경로. `beforeEach` 이후에만 유효하다. */
  readonly path: string;
}

/**
 * `beforeEach`/`afterEach` 를 등록해 각 테스트를 격리된 임시 HOME 에서 돌린다.
 *
 * <p>`describe` 블록 안에서 한 번 호출하고 반환된 핸들의 `path` 를 읽어 쓴다. 복원은 원래 값이
 * 없었으면 삭제까지 처리한다 — 덮어쓴 채 남기면 뒤따르는 테스트가 지워진 디렉터리를 홈으로 본다.
 *
 * @param prefix 임시 디렉터리 이름 접두사(어느 테스트가 남긴 것인지 알아보기 위함)
 */
export function useTempHome(prefix: string): TempHome {
  const handle = { path: '' };
  let originalHome: string | undefined;

  beforeEach(async () => {
    handle.path = await mkdtemp(join(tmpdir(), `${prefix}-`));
    originalHome = process.env.HOME;
    process.env.HOME = handle.path;
  });

  afterEach(async () => {
    if (originalHome === undefined) delete process.env.HOME;
    else process.env.HOME = originalHome;
    await rm(handle.path, { recursive: true, force: true });
  });

  return handle;
}
