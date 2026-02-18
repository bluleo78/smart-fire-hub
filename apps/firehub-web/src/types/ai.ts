export type AIMode = 'side' | 'floating' | 'fullscreen';

export interface AISession {
  id: number;
  sessionId: string;
  title: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AIMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: AIToolCall[];
  timestamp: string;
}

export interface AIToolCall {
  name: string;
  input: Record<string, unknown>;
  result?: string;
}

export interface AIStreamEvent {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'done' | 'error';
  sessionId?: string;
  content?: string;
  toolName?: string;
  input?: Record<string, unknown>;
  result?: string;
  message?: string;
}
