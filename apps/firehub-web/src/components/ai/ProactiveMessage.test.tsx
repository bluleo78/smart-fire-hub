/**
 * ProactiveMessage 의 리포트 id 해석 회귀 테스트 (WD-667 #1)
 *
 * 메시지 content 는 형식이 없는 값(Record<string, unknown>)이다. 예전엔 jobId·executionId 를 Number() 로 바꿔
 * 리포트 모달에 넘겨, 숫자가 아닌 값이면 닫힌 모달이 `/proactive/jobs/NaN/executions/NaN` 을 부르고 폴링까지 했다.
 * 이제 숫자로만 된 양의 정수만 리포트 id 로 받고, 아니면 「리포트 보기」를 그리지 않는다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// vi.mock 팩토리가 끌어올려지므로 목 객체도 vi.hoisted 로 올린다
const proactive = vi.hoisted(() => ({ getExecution: vi.fn(), getExecutionHtml: vi.fn() }));
vi.mock('../../api/proactive', () => ({ proactiveApi: proactive }));

import type { ProactiveMessage as ProactiveMessageType } from '../../api/proactive';
import { ProactiveMessage } from './ProactiveMessage';

function message(content: Record<string, unknown>): ProactiveMessageType {
  return {
    id: 1,
    userId: 1,
    jobId: null,
    executionId: null,
    jobName: '주간 현황',
    title: '주간 현황',
    content: { summary: '요약', ...content },
    messageType: 'REPORT',
    read: true,
    createdAt: '2026-11-09T00:00:00',
    canViewReport: true,
  };
}

function renderMessage(content: Record<string, unknown>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<ProactiveMessage message={message(content)} onMarkRead={() => {}} onFollowUp={() => {}} />, {
    wrapper,
  });
}

async function flush() {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}

beforeEach(() => {
  proactive.getExecution.mockReset().mockResolvedValue({ data: { id: 3, status: 'RUNNING' } });
  proactive.getExecutionHtml.mockReset().mockResolvedValue({ data: '' });
});

describe('ProactiveMessage 리포트 id (WD-667)', () => {
  it('숫자가 아닌 jobId·executionId 면 조회하지 않고 「리포트 보기」도 그리지 않는다', async () => {
    renderMessage({ jobId: 'abc', executionId: 'xyz' });
    await flush();
    expect(proactive.getExecution).not.toHaveBeenCalled();
    expect(proactive.getExecutionHtml).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: '리포트 보기' })).toBeNull();
  });

  it('숫자 문자열·숫자 id 면 「리포트 보기」를 그리고, 모달을 열기 전에는 조회하지 않는다', async () => {
    renderMessage({ jobId: '7', executionId: 3 });
    await flush();
    expect(screen.getByRole('button', { name: '리포트 보기' })).toBeInTheDocument();
    // 닫힌 모달은 실행·HTML 을 부르지 않는다(WD-667 #4)
    expect(proactive.getExecution).not.toHaveBeenCalled();
    expect(proactive.getExecutionHtml).not.toHaveBeenCalled();
  });
});
