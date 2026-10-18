import { constants } from 'fs';
import { lstat, mkdir, open } from 'fs/promises';
import { isAbsolute, join, normalize, sep } from 'path';
import { z } from 'zod/v4';
import { PROACTIVE_REPORT_ROOT } from '../../agent/tenant-paths.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * 스마트 작업(Proactive) 리포트 파일 쓰기 도구 (WD-62).
 *
 * <p>왜 필요한가: SDK 경로는 report-writer 하위 에이전트가 Write 도구로 report.html·report.md·summary.md 를
 * 쓰고 proactive 라우트가 그 파일을 읽는다. opencode 경로는 보안상 호스트 Write 를 끄고 task 위임도 막았으므로
 * 세 파일을 쓸 방법이 없어 항상 원문 폴백/502 로 떨어졌다. 호스트 Write 를 다시 여는 대신, 쓸 수 있는 곳을
 * "그 실행의 reportDir 안 세 파일"로 못 박은 전용 도구만 연다.
 *
 * <p>reportDir 은 모델 인자가 아니라 부모(agent-opencode)가 MCP 프로세스 env({@link REPORT_DIR_ENV})로
 * 주입한다 — 모델은 파일명(열거형)과 내용만 고를 수 있어 경로 탈출 인자가 원천적으로 없다. env 가 없으면
 * (일반 채팅·CLI) stdio-server 가 이 도구를 등록하지 않는다.
 */

/** 도구 이름. opencode 트랜스크립트가 이 도구의 content 를 생략할 때도 같은 상수를 쓴다(이름이 갈리면 생략이 조용히 꺼진다). */
export const WRITE_REPORT_FILE_TOOL = 'write_report_file';

/** 부모가 reportDir 을 stdio MCP 프로세스에 넘기는 env 이름. */
export const REPORT_DIR_ENV = 'FIREHUB_REPORT_DIR';

/** proactive 라우트가 읽는 세 파일 — 이 밖의 이름은 쓰지 않는다. */
export const REPORT_FILE_NAMES = ['report.html', 'report.md', 'summary.md'] as const;
export type ReportFileName = (typeof REPORT_FILE_NAMES)[number];

/** 파일 하나의 상한(바이트). 인라인 SVG 를 담은 HTML 도 충분히 들어가고, 폭주 출력이 디스크를 채우지는 못한다. */
const MAX_REPORT_BYTES = 5 * 1024 * 1024;

/**
 * reportDir 의 `proactive-report` 세그먼트부터 끝까지 모든 구성요소가 심볼릭 링크가 아닌 실제 디렉터리인지 확인한다.
 *
 * <p>왜 이 범위인가: 그 아래(테넌트·실행 디렉터리)는 Write/Bash 를 가진 SDK·CLI 에이전트도 닿는 공유 tmp 트리라
 * 링크를 심어 쓰기를 다른 곳으로 돌릴 수 있다. 그 위(시스템 임시 디렉터리, macOS `/var` → `/private/var` 등)는
 * 정상 환경에도 링크가 있어 판정에 쓰지 않는다.
 */
async function assertRealDirsBelowRoot(reportDir: string): Promise<void> {
  const parts = reportDir.split(sep);
  const rootIdx = parts.lastIndexOf(PROACTIVE_REPORT_ROOT);
  if (rootIdx < 1) {
    throw new Error(`reportDir 이 ${PROACTIVE_REPORT_ROOT} 경로가 아닙니다`);
  }
  for (let i = rootIdx; i < parts.length; i++) {
    const st = await lstat(parts.slice(0, i + 1).join(sep));
    if (!st.isDirectory()) {
      throw new Error('reportDir 경로에 실제 디렉터리가 아닌 구성요소(심볼릭 링크 등)가 있습니다');
    }
  }
}

/**
 * reportDir 안에 허용된 리포트 파일 하나를 쓴다(덮어쓰기).
 *
 * <p>방어 순서: 파일명 허용 목록 → reportDir 형식(정규화된 절대경로) → 크기 → 디렉터리 생성 후 링크 검사 →
 * O_NOFOLLOW 로 열기(대상 자리에 심긴 링크 거부) → 연 뒤 경로 재검사 + 연 파일과 경로의 dev/ino 대조 →
 * 그제서야 비우고 쓴다. O_TRUNC 로 열지 않는 이유: 검사와 열기 사이에 디렉터리가 링크로 바뀌면 바깥 파일을
 * 검증 전에 비워 버리기 때문이다(코드리뷰 TOCTOU 지적).
 */
export async function writeReportFile(
  reportDir: string,
  fileName: string,
  content: string,
): Promise<{ saved: ReportFileName; bytes: number }> {
  if (!(REPORT_FILE_NAMES as readonly string[]).includes(fileName)) {
    throw new Error(`허용되지 않는 파일명입니다: ${fileName} (허용: ${REPORT_FILE_NAMES.join(', ')})`);
  }
  if (!isAbsolute(reportDir) || normalize(reportDir) !== reportDir) {
    throw new Error('reportDir 이 정규화된 절대경로가 아닙니다');
  }
  const bytes = Buffer.byteLength(content, 'utf-8');
  if (bytes > MAX_REPORT_BYTES) {
    throw new Error(`리포트 파일이 너무 큽니다(${bytes}바이트, 상한 ${MAX_REPORT_BYTES}바이트)`);
  }

  await mkdir(reportDir, { recursive: true, mode: 0o700 });
  await assertRealDirsBelowRoot(reportDir);

  const target = join(reportDir, fileName);
  const handle = await open(target, constants.O_WRONLY | constants.O_CREAT | constants.O_NOFOLLOW, 0o600);
  try {
    await assertRealDirsBelowRoot(reportDir);
    const [opened, current] = await Promise.all([handle.stat(), lstat(target)]);
    if (opened.dev !== current.dev || opened.ino !== current.ino) {
      throw new Error('리포트 파일 경로가 쓰기 도중 바뀌었습니다');
    }
    await handle.truncate(0);
    await handle.writeFile(content, 'utf-8');
  } finally {
    await handle.close();
  }
  return { saved: fileName as ReportFileName, bytes };
}

/** reportDir 에 고정된 write_report_file 도구를 등록한다. stdio-server 가 env 주입 시에만 부른다. */
export function registerReportFileTools(reportDir: string, safeTool: SafeToolFn, jsonResult: JsonResultFn) {
  return [
    safeTool(
      WRITE_REPORT_FILE_TOOL,
      '스마트 작업 리포트 파일을 저장합니다. fileName 은 report.html(웹 뷰어용 HTML) · report.md(PDF/이메일용 마크다운) · ' +
        'summary.md(3~5줄 요약) 중 하나이며, 경로는 지정할 수 없습니다(이 실행의 리포트 디렉터리에 저장). ' +
        '같은 파일을 다시 저장하면 덮어씁니다.',
      {
        fileName: z.enum(REPORT_FILE_NAMES).describe('저장할 파일 이름'),
        // 글자 수 상한으로 폭주 입력을 검증 단계에서 먼저 끊는다 — 바이트 상한은 writeReportFile 이 다시 확인한다.
        content: z.string().max(MAX_REPORT_BYTES).describe('파일 전체 내용'),
      },
      async (args: { fileName: ReportFileName; content: string }) =>
        jsonResult(await writeReportFile(reportDir, args.fileName, args.content)),
    ),
  ];
}
