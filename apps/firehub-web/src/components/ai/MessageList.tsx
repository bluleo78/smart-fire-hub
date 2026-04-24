import { useEffect, useRef } from 'react';

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

export function MessageList({ messages, pendingUserMessage, streamingMessage, isStreaming, isThinking }: MessageListProps) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, pendingUserMessage, streamingMessage, isStreaming, isThinking]);

  const showThinking = isStreaming && isThinking;

  return (
    <div className="h-full overflow-y-auto">
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
