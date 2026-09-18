/**
 * MessageList 단위 테스트
 *
 * 스트리밍 메시지의 toolCalls가 빈 배열([])일 때 렌더 조건이 숫자 0으로 평가되어
 * 화면에 "0"이 그대로 찍히던 회귀를 검증한다.
 */
import { act, render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';

// MessageBubble은 마크다운·차트 등 무거운 의존성을 가지므로 모킹한다.
vi.mock('./MessageBubble', () => ({
  MessageBubble: () => <div data-testid="message-bubble-mock" />,
}));

import { MessageList } from './MessageList';

// jsdom에는 scrollIntoView 구현이 없어 자동 스크롤 useEffect가 터진다 — 스텁으로 대체.
beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

describe('MessageList', () => {
  it('toolCalls가 빈 배열인 스트리밍 메시지는 아무것도 렌더하지 않는다', () => {
    const { container } = render(
      <MessageList
        messages={[]}
        streamingMessage={{ id: 'a1', role: 'assistant', content: '', toolCalls: [] }}
        isStreaming
      />,
    );

    // 스트리밍 버블이 렌더되지 않아야 하고, 텍스트도 전혀 남지 않아야 한다.
    // (조건식이 0으로 새어 나오면 textContent가 "0"이 된다)
    expect(screen.queryByTestId('message-bubble-mock')).not.toBeInTheDocument();
    expect(container.textContent).toBe('');
  });

  it('toolCalls가 있으면 스트리밍 버블을 렌더한다', () => {
    render(
      <MessageList
        messages={[]}
        streamingMessage={{
          id: 'a1',
          role: 'assistant',
          content: '',
          toolCalls: [{ name: 'get_pipeline', input: {} }],
        }}
        isStreaming
      />,
    );

    expect(screen.getByTestId('message-bubble-mock')).toBeInTheDocument();
  });

  // #692: 압축 중 대화 영역이 무변화라 사용자가 응답이 멈춘 것으로 오해했다.
  describe('컨텍스트 압축 진행 표시 (#692)', () => {
    it('압축 중이면 대화 영역에 경과 시간과 함께 진행 표시를 렌더한다', () => {
      vi.useFakeTimers();
      try {
        const startedAt = Date.now() - 12_000; // 이미 12초 경과한 상태
        render(
          <MessageList messages={[]} isStreaming isCompacting compactionStartedAt={startedAt} />,
        );

        const indicator = screen.getByTestId('compaction-indicator');
        expect(indicator).toHaveTextContent('요약하는 중');
        expect(indicator).toHaveTextContent('12초');

        // 1초 경과 시 표시도 따라 올라가야 한다 — 멈춘 스피너로 보이면 안 된다.
        act(() => {
          vi.advanceTimersByTime(1000);
        });
        expect(screen.getByTestId('compaction-indicator')).toHaveTextContent('13초');
      } finally {
        vi.useRealTimers();
      }
    });

    it('압축 중에는 "생각하는 중" 표시를 함께 띄우지 않는다', () => {
      render(<MessageList messages={[]} isStreaming isThinking isCompacting compactionStartedAt={Date.now()} />);

      expect(screen.getByTestId('compaction-indicator')).toBeInTheDocument();
      expect(screen.queryByText(/생각하는 중/)).not.toBeInTheDocument();
    });

    it('압축 중이 아니면 진행 표시를 렌더하지 않는다', () => {
      render(<MessageList messages={[]} isStreaming isThinking />);

      expect(screen.queryByTestId('compaction-indicator')).not.toBeInTheDocument();
    });
  });
});
