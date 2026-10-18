/**
 * 리포트 파일 쓰기 도구(write_report_file) 테스트 (WD-62).
 *
 * opencode 경로는 호스트 Write 도구와 하위 에이전트 위임이 막혀 있어, 스마트 작업(Proactive) 리포트
 * 세 파일을 이 도구로만 쓴다. 쓰기는 부모가 주입한 reportDir 안의 정해진 세 파일명으로만 허용돼야 한다.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'fs/promises';
import { existsSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { REPORT_FILE_NAMES, registerReportFileTools, writeReportFile } from './report-file-tools.js';
import { jsonResult } from '../firehub-mcp-server.js';
import type { SafeToolFn } from '../firehub-mcp-server.js';

describe('writeReportFile', () => {
  let base: string;
  let reportDir: string;

  beforeEach(async () => {
    base = await mkdtemp(join(tmpdir(), 'wd62-report-'));
    // 실제 경로처럼 아직 만들어지지 않은 하위 디렉터리 — 도구가 직접 만들어야 한다.
    reportDir = join(base, 'proactive-report', 't3', '7-123');
  });

  afterEach(async () => {
    await rm(base, { recursive: true, force: true });
  });

  it.each(REPORT_FILE_NAMES)('허용된 파일명 %s 는 reportDir 에 쓰고 디렉터리를 만든다', async (name) => {
    const result = await writeReportFile(reportDir, name, '내용');
    expect(await readFile(join(reportDir, name), 'utf-8')).toBe('내용');
    // 응답에 절대경로를 싣지 않는다 — 모델이 경로를 다른 도구 인자로 쓰도록 유도하지 않는다.
    expect(result).toEqual({ saved: name, bytes: Buffer.byteLength('내용') });
  });

  it('같은 파일을 다시 쓰면 덮어쓴다(이전 내용이 남지 않는다)', async () => {
    await writeReportFile(reportDir, 'summary.md', '아주 긴 첫 번째 초안 내용');
    await writeReportFile(reportDir, 'summary.md', '짧은 최종본');
    expect(await readFile(join(reportDir, 'summary.md'), 'utf-8')).toBe('짧은 최종본');
  });

  it.each(['other.html', '../report.html', '/etc/passwd', 'sub/report.html', 'report.html/..', ''])(
    '허용 목록 밖 파일명 %j 는 거부하고 아무것도 쓰지 않는다',
    async (name) => {
      await expect(writeReportFile(reportDir, name, 'x')).rejects.toThrow(/허용되지 않는 파일명/);
      expect(existsSync(reportDir)).toBe(false);
      expect(existsSync(join(base, 'proactive-report', 't3', 'report.html'))).toBe(false);
    },
  );

  it('reportDir 가 절대경로가 아니면 거부한다', async () => {
    await expect(writeReportFile('relative/dir', 'report.html', 'x')).rejects.toThrow(/reportDir/);
  });

  it('reportDir 에 .. 구간이 섞여 있으면 거부한다', async () => {
    await expect(writeReportFile(join(base, 'a') + '/../b', 'report.html', 'x')).rejects.toThrow(/reportDir/);
  });

  it('대상 파일 자리에 심볼릭 링크가 심겨 있으면 따라가지 않고 거부한다', async () => {
    await mkdir(reportDir, { recursive: true });
    const outside = join(base, 'outside.txt');
    await writeFile(outside, '원본', 'utf-8');
    await symlink(outside, join(reportDir, 'report.html'));

    await expect(writeReportFile(reportDir, 'report.html', '탈취')).rejects.toThrow();
    expect(await readFile(outside, 'utf-8')).toBe('원본');
  });

  it('reportDir 의 상위(테넌트 디렉터리)가 심볼릭 링크면 거부한다 — 공유 tmp 트리의 링크로 쓰기를 돌릴 수 없다', async () => {
    const elsewhere = join(base, 'elsewhere');
    await mkdir(elsewhere, { recursive: true });
    await mkdir(join(base, 'proactive-report'), { recursive: true });
    await symlink(elsewhere, join(base, 'proactive-report', 't3'));

    await expect(writeReportFile(reportDir, 'report.html', '탈취')).rejects.toThrow(/디렉터리/);
    expect(existsSync(join(elsewhere, '7-123', 'report.html'))).toBe(false);
  });

  it('proactive-report 세그먼트가 없는 reportDir 은 거부한다', async () => {
    await expect(writeReportFile(join(base, 'other', '7-1'), 'report.html', 'x')).rejects.toThrow(/proactive-report/);
  });

  it('reportDir 자체가 심볼릭 링크면 거부한다', async () => {
    const elsewhere = join(base, 'elsewhere');
    await mkdir(elsewhere, { recursive: true });
    await mkdir(join(base, 'proactive-report', 't3'), { recursive: true });
    await symlink(elsewhere, reportDir);

    await expect(writeReportFile(reportDir, 'report.html', '탈취')).rejects.toThrow(/디렉터리/);
    expect(existsSync(join(elsewhere, 'report.html'))).toBe(false);
  });

  it('내용이 상한을 넘으면 거부한다', async () => {
    await expect(writeReportFile(reportDir, 'report.html', 'x'.repeat(5 * 1024 * 1024 + 1))).rejects.toThrow(
      /너무 큽니다/,
    );
  });
});

describe('registerReportFileTools', () => {
  it('write_report_file 하나를 등록하고 핸들러는 reportDir 을 인자로 받지 않는다', async () => {
    const base = await mkdtemp(join(tmpdir(), 'wd62-reg-'));
    try {
      const reportDir = join(base, 'proactive-report', 't1', '1-1');
      const registered: Array<{ name: string; schema: Record<string, unknown>; handler: (a: unknown) => unknown }> = [];
      const fakeSafeTool = vi.fn((name: string, _d: string, schema: Record<string, unknown>, handler: (a: unknown) => unknown) => {
        registered.push({ name, schema, handler });
        return { name };
      }) as unknown as SafeToolFn;

      registerReportFileTools(reportDir, fakeSafeTool, jsonResult);

      expect(registered.map((t) => t.name)).toEqual(['write_report_file']);
      // 모델이 고를 수 있는 인자는 파일명과 내용뿐이다 — 경로 인자가 없어야 경로 탈출이 원천 차단된다.
      expect(Object.keys(registered[0].schema).sort()).toEqual(['content', 'fileName']);

      await registered[0].handler({ fileName: 'report.md', content: '# 제목' });
      expect(await readFile(join(reportDir, 'report.md'), 'utf-8')).toBe('# 제목');
    } finally {
      await rm(base, { recursive: true, force: true });
    }
  });
});
