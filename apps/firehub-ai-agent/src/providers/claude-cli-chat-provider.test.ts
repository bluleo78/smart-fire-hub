import { describe, it, expect, vi } from 'vitest';

const executeCliAgentMock = vi.fn();
vi.mock('../agent/agent-cli.js', () => ({ executeCliAgent: (o: unknown) => executeCliAgentMock(o) }));

import { ClaudeCliChatProvider } from './claude-cli-chat-provider.js';

// WD-765: 스마트 작업 리포트 디렉터리가 provider 를 거쳐 CliAgentOptions 까지 전달되는지 — 빠지면 CLI 경로의
// MCP 프로세스에 FIREHUB_REPORT_DIR 가 안 실려 report-writer 가 write_report_file 을 쓸 수 없다.
describe('ClaudeCliChatProvider — reportDir 전달 (WD-765)', () => {
  const run = async (reportDir?: string) => {
    executeCliAgentMock.mockReset();
    executeCliAgentMock.mockReturnValue((async function* () {})());
    const p = new ClaudeCliChatProvider(false, 'k');
    for await (const e of p.execute({ message: 'hi', tenantId: 1, userId: 1, reportDir })) void e;
    return executeCliAgentMock.mock.calls[0][0] as { reportDir?: string };
  };

  it('reportDir 을 CliAgentOptions.reportDir 로 넘긴다', async () => {
    expect((await run('/tmp/proactive-report/t1/1-100')).reportDir).toBe('/tmp/proactive-report/t1/1-100');
  });
  it('없으면 undefined', async () => {
    expect((await run()).reportDir).toBeUndefined();
  });
});
