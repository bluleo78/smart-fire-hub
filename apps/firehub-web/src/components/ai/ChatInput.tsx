import { Send, StopCircle } from 'lucide-react';
import type { KeyboardEvent } from 'react';
import { useRef,useState } from 'react';

import { Button } from '../ui/button';
import { Textarea } from '../ui/textarea';

interface ChatInputProps {
  onSend: (message: string) => void;
  onStop?: () => void;
  isStreaming?: boolean;
  disabled?: boolean;
  placeholder?: string;
}

export function ChatInput({ onSend, onStop, isStreaming = false, disabled = false, placeholder = '메시지를 입력하세요...' }: ChatInputProps) {
  const [message, setMessage] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleSend = () => {
    if (message.trim() && !isStreaming) {
      onSend(message.trim());
      setMessage('');
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto';
      }
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleInput = (e: React.FormEvent<HTMLTextAreaElement>) => {
    const target = e.currentTarget;
    target.style.height = 'auto';
    target.style.height = `${Math.min(target.scrollHeight, 150)}px`;
  };

  return (
    <div className="flex gap-2">
      <Textarea
        ref={textareaRef}
        value={message}
        onChange={(e) => setMessage(e.target.value)}
        onKeyDown={handleKeyDown}
        onInput={handleInput}
        placeholder={placeholder}
        disabled={disabled || isStreaming}
        className="min-h-[44px] max-h-[150px] resize-none text-sm"
        rows={1}
      />
      {isStreaming && onStop ? (
        <Button onClick={onStop} variant="destructive" size="icon" className="shrink-0 h-[44px] w-[44px]">
          <StopCircle className="h-4 w-4" />
        </Button>
      ) : (
        <Button onClick={handleSend} disabled={disabled || !message.trim()} size="icon" className="shrink-0 h-[44px] w-[44px]">
          <Send className="h-4 w-4" />
        </Button>
      )}
    </div>
  );
}
