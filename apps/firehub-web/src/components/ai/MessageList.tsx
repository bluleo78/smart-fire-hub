import { useEffect, useRef } from 'react';
import { MessageBubble } from './MessageBubble';
import { ThinkingIndicator } from './ThinkingIndicator';
import type { AIMessage } from '../../types/ai';

interface MessageListProps {
  messages: AIMessage[];
  pendingUserMessage?: string | null;
  streamingMessage?: Partial<AIMessage> | null;
  isStreaming?: boolean;
}

export function MessageList({ messages, pendingUserMessage, streamingMessage, isStreaming }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, pendingUserMessage, streamingMessage, isStreaming]);

  // 도구 실행 중(마지막 toolCall에 result 없음)이면 ThinkingIndicator 표시
  const lastToolPending = streamingMessage?.toolCalls?.length
    ? !streamingMessage.toolCalls[streamingMessage.toolCalls.length - 1].result
    : false;
  const showThinking = isStreaming && (!streamingMessage?.content || lastToolPending);

  return (
    <div className="h-full overflow-y-auto">
      <div className="space-y-3 p-3">
        {messages.map((message) => (
          <MessageBubble key={message.id} message={message} />
        ))}
        {pendingUserMessage && (
          <MessageBubble message={{ role: 'user', content: pendingUserMessage, timestamp: new Date().toISOString() }} />
        )}
        {(streamingMessage?.content || streamingMessage?.toolCalls?.length) && <MessageBubble message={streamingMessage} />}
        {showThinking && <ThinkingIndicator />}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}
