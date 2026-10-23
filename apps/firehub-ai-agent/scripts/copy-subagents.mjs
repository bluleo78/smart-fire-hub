// 서브에이전트 정의(md)를 src 에서 dist 로 복사하고 결과 배치를 검증하는 빌드 스크립트.
//
// 왜 필요한가: 예전 build 는 `tsc && cp -r src/agent/subagents dist/agent/subagents` 였다.
// tsc 가 이미 dist/agent/subagents/<name>/*.test.js 를 만들어 두기 때문에 대상 디렉터리가 존재하고,
// cp -r 은 그 "안쪽"에 복사해 dist/agent/subagents/subagents/<name>/agent.md 가 생겼다(WD-217).
// 그 결과 dist 의 loadSubagents() 가 서브에이전트를 0개 불러왔다(Docker 빌드도 동일).
// 그래서 디렉터리 "내용"을 병합 복사(fs.cpSync, macOS/alpine 모두 동일 동작)하고,
// 잘못된 배치가 되면 빌드를 실패시킨다.
//
// 사용: node scripts/copy-subagents.mjs           (복사 + 검증)
//       node scripts/copy-subagents.mjs --verify  (검증만)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = path.join(root, 'src/agent/subagents');
const dst = path.join(root, 'dist/agent/subagents');

/** src 의 agent.md 를 가진 서브에이전트 디렉터리 이름 목록. */
function sourceSubagents() {
  return fs
    .readdirSync(src, { withFileTypes: true })
    .filter((e) => e.isDirectory() && fs.existsSync(path.join(src, e.name, 'agent.md')))
    .map((e) => e.name);
}

/** dist 배치를 검증한다. 문제 목록과 서브에이전트 수를 반환(문제가 없으면 정상). */
function verify() {
  const problems = [];
  const names = sourceSubagents();
  for (const name of names) {
    // 서브에이전트 본문과, 있으면 Tibero 방언 하위 디렉터리까지 같은 위치에 있어야 한다.
    if (!fs.existsSync(path.join(dst, name, 'agent.md'))) {
      problems.push(`누락: dist/agent/subagents/${name}/agent.md`);
    }
    if (
      fs.existsSync(path.join(src, name, 'tibero')) &&
      !fs.existsSync(path.join(dst, name, 'tibero'))
    ) {
      problems.push(`누락: dist/agent/subagents/${name}/tibero`);
    }
  }
  // 중첩 복사(WD-217 증상) 감지.
  if (fs.existsSync(path.join(dst, 'subagents'))) {
    problems.push('중첩 복사 감지: dist/agent/subagents/subagents 가 존재한다');
  }
  return { problems, count: names.length };
}

/**
 * 복사 대상 필터 — 디렉터리와 .md(서브에이전트 정의·규칙·예시)만 dist 로 보낸다.
 * src 의 *.test.ts 같은 TS 원본은 dist 에 둘 이유가 없다(실행 코드는 tsc 가 .js 로 따로 만든다).
 * cpSync 는 최상위 src 디렉터리에도 필터를 부르므로 디렉터리는 항상 통과시킨다.
 */
function copyFilter(source) {
  return fs.statSync(source).isDirectory() || source.endsWith('.md');
}

if (!process.argv.includes('--verify')) {
  // 예전 build(cp -r)가 남긴 중첩 복사본을 먼저 지운다 — 이 디렉터리는 정상 배치에선 생기지 않으므로 지워도 안전하고,
  // 지우지 않으면 아래 검증이 영원히 실패해 옛 dist 를 가진 기기가 스스로 복구되지 않는다(WD-217).
  fs.rmSync(path.join(dst, 'subagents'), { recursive: true, force: true });
  fs.mkdirSync(dst, { recursive: true });
  // 디렉터리 자체가 아니라 내용을 병합 복사한다(대상이 이미 있어도 중첩되지 않음).
  fs.cpSync(src, dst, { recursive: true, filter: copyFilter });
}

const { problems, count } = verify();
if (problems.length > 0) {
  console.error('[copy-subagents] 서브에이전트 dist 배치 오류:\n  ' + problems.join('\n  '));
  process.exit(1);
}
console.log(`[copy-subagents] 서브에이전트 ${count}개 dist 배치 확인`);
