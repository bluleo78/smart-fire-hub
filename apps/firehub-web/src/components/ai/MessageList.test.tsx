/**
 * MessageList 단위 테스트
 *
 * 스트리밍 메시지의 toolCalls가 빈 배열([])일 때 렌더 조건이 숫자 0으로 평가되어
 * 화면에 "0"이 그대로 찍히던 회귀를 검증한다.
 */
import { render, screen } from '@testing-library/react';
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
});
