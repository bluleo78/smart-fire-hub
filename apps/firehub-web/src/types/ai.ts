export type AIMode = 'side' | 'floating' | 'fullscreen';

export interface AISession {
  id: number;
  sessionId: string;
  title: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AIAttachment {
  id: number;
  name: string;
  mimeType: string;
  fileSize: number;
  category: 'IMAGE' | 'PDF' | 'TEXT' | 'DATA';
  previewUrl?: string;
}

export interface AIMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  toolCalls?: AIToolCall[];
  timestamp: string;
  attachments?: AIAttachment[];
}

export interface AIToolCall {
  name: string;
  input: Record<string, unknown>;
  result?: string;
}

export interface AIStreamEvent {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'turn' | 'done' | 'error' | 'compaction';
  sessionId?: string;
  content?: string;
  toolName?: string;
  input?: Record<string, unknown>;
  result?: string;
  message?: string;
  inputTokens?: number;
  status?: 'started' | 'completed';
  preTokens?: number;
}
