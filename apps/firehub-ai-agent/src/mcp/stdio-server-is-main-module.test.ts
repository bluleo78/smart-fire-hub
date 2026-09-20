/**
 * {@link isMainModule}(stdio-server.ts) 전용 테스트 — 순수 함수라 실제 파일시스템에 임시
 * 경로(심링크/공백 포함)를 만들어 결정적으로 검증할 수 있다.
 *
 * 왜 별도 파일인가: stdio-server.test.ts 는 main() 의 배선(registerAllTools 로의 자격증명 전달)을
 * McpServer 등 무거운 협력자를 모킹해 검증한다 — 이 파일의 관심사(경로 정규화 자체)는 그 모킹과
 * 무관해서 같은 파일에 섞으면 두 관심사가 헷갈린다.
 *
 * 보안 리뷰 Fix6 — 이전 구현(`import.meta.url === \`file://${'$'}{argv[1]}\``)은 두 가지를 놓쳤다:
 *  1. Node 는 ESM 엔트리 로드 시 import.meta.url 을 realpath(심링크 해석)한 값으로 채우는데,
 *     argv[1] 은 명령행 문자열 그대로다 — 배포 경로에 심링크가 하나라도 있으면 절대 안 같아진다.
 *  2. 수동 `file://` 접두만으로는 퍼센트 인코딩을 안 해, 경로에 공백이 있으면 또 안 같아진다.
 * 이 조건이 잘못 false 가 되면 main() 이 안 불려 firehub MCP 도구 36개가 조용히 사라진다 —
 * 아래 테스트들이 정확히 그 두 함정을 재현한다.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { mkdtempSync, writeFileSync, symlinkSync, rmSync, mkdirSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isMainModule, shouldWarnNotMain } from './stdio-server.js';

describe('isMainModule', () => {
  const dirs: string[] = [];

  /**
   * macOS 는 `os.tmpdir()`(보통 `/var/folders/...`) 자체가 `/private/var/folders/...` 로 가는
   * 심링크다 — realpathSync 하지 않은 임시 디렉터리를 기준으로 삼으면 "심링크 없는 평범한 경로"
   * 시나리오조차 이미 심링크를 거치게 돼, 그 시나리오가 실제로 검증하려는 것(심링크가 "없을
   * 때"도 true)과 혼동된다. 생성 직후 realpath 해 기준선을 고정한다 — 심링크 테스트는 이 고정된
   * 기준선 아래에 자신이 직접 심링크를 만들어 별도로 재현한다.
   */
  function tempDir(prefix: string): string {
    const dir = realpathSync(mkdtempSync(join(tmpdir(), prefix)));
    dirs.push(dir);
    return dir;
  }

  afterEach(() => {
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it('argv1 이 undefined 면 false 다 (테스트 러너가 import 할 때의 기본 형태)', () => {
    expect(isMainModule(undefined, 'file:///anything')).toBe(false);
  });

  it('argv1 과 metaUrl 이 같은 실제 경로를 가리키면 true 다', () => {
    const dir = tempDir('isMainModule-plain-');
    const file = join(dir, 'server.js');
    writeFileSync(file, '// noop');

    const metaUrl = pathToFileURL(file).href;

    expect(isMainModule(file, metaUrl)).toBe(true);
  });

  /**
   * 심링크 케이스 — Node 는 실제 실행 시 import.meta.url 을 항상 realpath(심링크 해석)로
   * 채운다. argv1(=심링크 경로)을 realpath 하지 않고 그대로 문자열 비교했다면(예전 구현) 이
   * 테스트는 실패했을 것이다 — opencode 배포 경로가 pnpm 심링크(node_modules/.bin/tsx 등)를
   * 거치는 실제 구성을 재현한다.
   */
  it('argv1 이 심링크를 거쳐도(실제 파일과 다른 경로 문자열) realpath 기준으로 true 다', () => {
    const dir = tempDir('isMainModule-symlink-');
    const realFile = join(dir, 'real-server.js');
    writeFileSync(realFile, '// noop');
    const linkFile = join(dir, 'link-server.js');
    symlinkSync(realFile, linkFile);

    // Node 가 실제로 이 파일을 엔트리로 로드했다면 import.meta.url 은 realFile 의 realpath 를
    // 가리킨다 — 그 값을 그대로 재현한다.
    const metaUrlAsNodeWouldReportIt = pathToFileURL(realFile).href;

    expect(isMainModule(linkFile, metaUrlAsNodeWouldReportIt)).toBe(true);
  });

  /**
   * 공백 포함 경로 케이스 — `file://` 를 손으로 붙이기만 하면 공백이 인코딩되지 않아
   * import.meta.url(자동으로 %20 인코딩됨)과 바이트 단위로 달라진다. pathToFileURL 은 이 인코딩을
   * 올바르게 한다.
   */
  it('경로에 공백이 있어도 true 다(수동 file:// 접두 조립이면 실패했을 케이스)', () => {
    const dir = tempDir('isMainModule-space-');
    const subdir = join(dir, 'dir with space');
    mkdirSync(subdir);
    const file = join(subdir, 'my server.js');
    writeFileSync(file, '// noop');

    const metaUrl = pathToFileURL(file).href;
    // 수동 조립("file://" + file)과는 다른 값이어야 한다 — 그렇지 않으면 이 테스트가 아무것도
    // 구분하지 못한다.
    expect(metaUrl).not.toBe(`file://${file}`);

    expect(isMainModule(file, metaUrl)).toBe(true);
  });

  it('argv1 이 실제로 다른 파일을 가리키면 false 다', () => {
    const dir = tempDir('isMainModule-mismatch-');
    const fileA = join(dir, 'a.js');
    const fileB = join(dir, 'b.js');
    writeFileSync(fileA, '// noop');
    writeFileSync(fileB, '// noop');

    expect(isMainModule(fileA, pathToFileURL(fileB).href)).toBe(false);
  });

  it('argv1 이 존재하지 않는 경로면(realpathSync 실패) false 다', () => {
    const dir = tempDir('isMainModule-missing-');
    const missing = join(dir, 'does-not-exist.js');

    expect(isMainModule(missing, pathToFileURL(missing).href)).toBe(false);
  });
});

/**
 * {@link shouldWarnNotMain} 전용 테스트(재검토 N8).
 *
 * <p>이전에는 {@code isMainModule()} 이 false 인 <b>모든</b> 경우에 stderr 경고를 찍어, 이
 * 모듈을 import 하는 모든 테스트/도구 실행에 상시 노이즈가 깔렸다. 이제는 "이 파일을 스크립트로
 * 띄우려 했는데 실패한 경우"만 경고한다 — 그 판정이 이 함수다.
 */
describe('shouldWarnNotMain', () => {
  const META = pathToFileURL('/srv/app/dist/mcp/stdio-server.js').href;

  it('argv1 이 없으면 경고하지 않는다', () => {
    expect(shouldWarnNotMain(undefined, META)).toBe(false);
  });

  it('vitest 등 다른 엔트리가 import 한 경우에는 경고하지 않는다(상시 노이즈 제거)', () => {
    expect(shouldWarnNotMain('/srv/app/node_modules/vitest/vitest.mjs', META)).toBe(false);
  });

  it('무관한 엔트리(다른 CLI 바이너리)가 import 한 경우에도 경고하지 않는다', () => {
    expect(shouldWarnNotMain('/srv/app/node_modules/.bin/some-cli', META)).toBe(false);
  });

  it('이 파일 자신을 스크립트로 띄운 실행이면 경고한다(진짜 사고)', () => {
    expect(shouldWarnNotMain('/opt/other/stdio-server.js', META)).toBe(true);
  });

  /**
   * 확장자는 무시한다 — 개발(tsx, `.ts`)과 배포(node, `.js`)가 같은 사고를 낸다.
   * `getStdioServerCommand()`(stdio-server-command.ts:9,11)가 자식에게 넘기는 스크립트 인자가
   * 정확히 `stdio-server.js` 또는 `stdio-server.ts` 라, 이 술어의 파일명 대조가 실제 기동 실패를
   * 잡는 근거가 된다.
   */
  it('확장자가 달라도(.ts vs .js) 같은 파일명이면 경고한다', () => {
    expect(shouldWarnNotMain('/srv/app/src/mcp/stdio-server.ts', META)).toBe(true);
  });

  it('metaUrl 이 file: URL 이 아니면 경고하지 않는다(판정 불가 → 무소음)', () => {
    expect(shouldWarnNotMain('/srv/app/dist/mcp/stdio-server.js', 'data:text/javascript,0')).toBe(false);
  });
});
