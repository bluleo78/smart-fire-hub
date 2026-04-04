import type { OutputColumn, ClassifyResponse } from '../services/classification-service.js';

export type SSEEvent = {
  type: 'init' | 'text' | 'tool_use' | 'tool_result' | 'turn' | 'done' | 'error' | 'compaction' | 'ping';
  [key: string]: unknown;
};

export interface ChatProviderOptions {
  message: string;
  sessionId?: string;
  userId: number;
  fileIds?: number[];
  model?: string;
  maxTurns?: number;
  systemPrompt?: string;
  /** true이면 systemPrompt가 기본 SYSTEM_PROMPT를 완전히 대체한다 (프로액티브 등 전용 프롬프트용) */
  overrideSystemPrompt?: boolean;
  temperature?: number;
  maxTokens?: number;
  abortSignal?: AbortSignal;
}

export interface ChatProvider {
  readonly name: string;
  execute(options: ChatProviderOptions): AsyncGenerator<SSEEvent>;
}

export interface ClassifyProviderOptions {
  rows: Record<string, unknown>[];
  prompt: string;
  outputColumns: OutputColumn[];
  model?: string;
  apiKey?: string;
  userId?: number;
}

export interface ClassifyProvider {
  readonly name: string;
  classify(options: ClassifyProviderOptions): Promise<ClassifyResponse>;
}

export type AgentType = 'sdk' | 'cli' | 'cli-api';

export interface ProviderConfig {
  agentType: AgentType;
  model?: string;
  apiKey?: string;
  cliOauthToken?: string;
}
