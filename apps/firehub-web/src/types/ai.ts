export type AIMode = 'side' | 'floating' | 'fullscreen' | 'native';

export type CanvasWidth = 'full' | 'half' | 'third';
export type CanvasHeight = 'full' | 'half' | 'third';

export interface CanvasLayout {
  width: CanvasWidth;
  height: CanvasHeight;
  page?: 'new' | 'current';
  pageLabel?: string;
  replace?: string;
}

export interface CanvasWidget {
  id: string;
  toolName: string;
  input: Record<string, unknown>;
  layout: CanvasLayout;
  timestamp: string;
}

export interface CanvasPage {
  id: string;
  label: string;
  widgets: CanvasWidget[];
}

export type AgentType = 'sdk' | 'cli' | 'cli-api';

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

export type ContentBlock =
  | { type: 'text' }
  | { type: 'tool_use'; toolCallIndex: number };

export interface AIMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  toolCalls?: AIToolCall[];
  contentBlocks?: ContentBlock[];
  timestamp: string;
  attachments?: AIAttachment[];
}

export interface AIToolCall {
  id?: string;
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
