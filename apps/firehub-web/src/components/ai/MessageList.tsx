import { useCallback, useEffect, useRef, useState } from 'react';

import type { AIMessage } from '../../types/ai';
import { MessageBubble } from './MessageBubble';
import { ThinkingIndicator } from './ThinkingIndicator';

interface MessageListProps {
  messages: AIMessage[];
  pendingUserMessage?: string | null;
  streamingMessage?: Partial<AIMessage> | null;
  isStreaming?: boolean;
  isThinking?: boolean;
}

/** 스크롤 컨테이너 하단 기준 허용 오차 (px) — 이 범위 이내면 "맨 아래"로 판단 */
const BOTTOM_THRESHOLD = 50;

export function MessageList({ messages, pendingUserMessage, streamingMessage, isStreaming, isThinking }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);

  /**
   * 사용자가 맨 아래에 있는지 여부.
   * true일 때만 스트리밍 이벤트에 반응하여 자동 스크롤을 수행한다.
   * 새 메시지 전송(messages 배열 변경) 시에는 항상 맨 아래로 스크롤하고 플래그를 true로 재설정한다.
   */
  const [isAtBottom, setIsAtBottom] = useState(true);

  /**
   * 스크롤 이벤트 핸들러 — 사용자가 맨 아래에 있는지 감지한다.
   * scrollTop + clientHeight >= scrollHeight - BOTTOM_THRESHOLD 이면 맨 아래로 판단.
   */
  const handleScroll = useCallback(() => {
    const el = scrollContainerRef.current;
    if (!el) return;
    const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - BOTTOM_THRESHOLD;
    setIsAtBottom(atBottom);
  }, []);

  /**
   * 새 메시지가 추가될 때(사용자 전송 또는 스트리밍 완료)마다 무조건 맨 아래로 스크롤하고
   * isAtBottom 플래그를 true로 재설정한다. pendingUserMessage도 동일하게 처리.
   */
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    setIsAtBottom(true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages, pendingUserMessage]);

  /**
   * 스트리밍 토큰 수신 시 자동 스크롤 — 사용자가 맨 아래에 있을 때만 실행.
   * 사용자가 위로 스크롤한 경우(isAtBottom=false) 강제 스크롤하지 않는다.
   */
  useEffect(() => {
    if (!isAtBottom) return;
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [streamingMessage, isStreaming, isThinking, isAtBottom]);

  const showThinking = isStreaming && isThinking;

  return (
    <div ref={scrollContainerRef} className="h-full overflow-y-auto" onScroll={handleScroll}>
      <div className="space-y-3 p-3">
        {messages.map((message) => (
          <MessageBubble key={message.id} message={message} />
        ))}
        {pendingUserMessage && (
          <MessageBubble message={{ role: 'user', content: pendingUserMessage, timestamp: new Date().toISOString() }} />
        )}
        {(streamingMessage?.content || streamingMessage?.toolCalls?.length) && <MessageBubble key="streaming" message={streamingMessage} isStreaming={isStreaming} />}
        {showThinking && <ThinkingIndicator />}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}
