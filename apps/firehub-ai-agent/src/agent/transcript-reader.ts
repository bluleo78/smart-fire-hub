import { readFile } from 'fs/promises';
import path from 'path';
import os from 'os';
import { getTranscriptPath } from './agent-cli.js';
import type { CliTranscript } from './agent-cli.js';

export interface HistoryToolCall {
  name: string;
  input: Record<string, unknown>;
  result?: string;
}

export interface HistoryMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: HistoryToolCall[];
  timestamp: string;
}

function getProjectId(): string {
  const cwd = process.cwd();
  // Replace all `/` with `-` (keeps leading dash, matching Claude's convention)
  return cwd.replace(/\//g, '-');
}

interface ParsedAssistant {
  textParts: string[];
  toolCalls: HistoryToolCall[];
  id: string;
  timestamp: string;
}

export async function readSessionTranscript(sessionId: string): Promise<HistoryMessage[]> {
  // CLI 에이전트 트랜스크립트 시도
  try {
    const data = await readFile(getTranscriptPath(sessionId), 'utf-8');
    const parsed = JSON.parse(data) as CliTranscript | HistoryMessage[];
    return Array.isArray(parsed) ? parsed : (parsed.messages ?? []);
  } catch {
    // 파일 없으면 SDK JSONL 경로로 폴백
  }

  // SDK 에이전트 JSONL 트랜스크립트
  const projectId = getProjectId();
  const filePath = path.join(os.homedir(), '.claude', 'projects', projectId, `${sessionId}.jsonl`);

  let raw: string;
  try {
    raw = await readFile(filePath, 'utf-8');
  } catch {
    return [];
  }

  const messages: HistoryMessage[] = [];
  // Track assistant messages by API message ID to merge split lines
  const assistantByMsgId = new Map<string, ParsedAssistant>();
  // Track insertion order of assistant message IDs
  let lastAssistantMsgId: string | null = null;
  // Track tool_use_id → toolCall object for attaching tool_result later
  const toolCallsByUseId = new Map<string, HistoryToolCall>();

  for (const line of raw.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;

    let entry: Record<string, unknown>;
    try {
      entry = JSON.parse(trimmed) as Record<string, unknown>;
    } catch {
      continue;
    }

    const type = entry.type as string | undefined;
    if (type !== 'user' && type !== 'assistant') continue;

    // Skip synthetic/meta messages
    if (entry.isMeta) continue;

    const message = entry.message as Record<string, unknown> | undefined;
    if (!message) continue;

    // Skip synthetic model responses
    if (message.model === '<synthetic>') continue;

    const contentBlocks = message.content as unknown[] | undefined;
    if (!Array.isArray(contentBlocks)) continue;

    const id = (entry.uuid as string) ?? `${Date.now()}-${Math.random()}`;
    const timestamp = (entry.timestamp as string) ?? new Date().toISOString();

    if (type === 'user') {
      // Flush any pending assistant message before processing user message
      if (lastAssistantMsgId) {
        const pending = assistantByMsgId.get(lastAssistantMsgId);
        if (pending) {
          const content = pending.textParts.join('');
          const toolCalls = pending.toolCalls.length > 0 ? pending.toolCalls : undefined;
          if (content || toolCalls) {
            messages.push({
              id: pending.id,
              role: 'assistant',
              content,
              toolCalls,
              timestamp: pending.timestamp,
            });
          }
        }
        assistantByMsgId.clear();
        lastAssistantMsgId = null;
      }

      // Extract tool_result and attach to corresponding toolCall, then skip
      const hasToolResult = contentBlocks.some(
        (block) => (block as Record<string, unknown>).type === 'tool_result',
      );
      if (hasToolResult) {
        for (const block of contentBlocks) {
          const b = block as Record<string, unknown>;
          if (b.type === 'tool_result') {
            const toolUseId = b.tool_use_id as string;
            const tc = toolCallsByUseId.get(toolUseId);
            if (tc) {
              const resultContent = b.content;
              if (typeof resultContent === 'string') {
                tc.result = resultContent;
              } else if (Array.isArray(resultContent)) {
                tc.result = resultContent
                  .filter((c: unknown) => (c as Record<string, unknown>).type === 'text')
                  .map((c: unknown) => (c as Record<string, unknown>).text as string)
                  .join('');
              }
            }
          }
        }
        continue;
      }

      const text = contentBlocks
        .filter((block) => (block as Record<string, unknown>).type === 'text')
        .map((block) => (block as Record<string, unknown>).text as string)
        .join('');

      if (!text) continue;

      messages.push({ id, role: 'user', content: text, timestamp });
    } else {
      // assistant — merge lines with the same API message ID
      const msgId = (message.id as string) ?? id;

      let parsed = assistantByMsgId.get(msgId);
      if (!parsed) {
        // If there's a different pending assistant message, flush it first
        if (lastAssistantMsgId && lastAssistantMsgId !== msgId) {
          const prev = assistantByMsgId.get(lastAssistantMsgId);
          if (prev) {
            const content = prev.textParts.join('');
            const toolCalls = prev.toolCalls.length > 0 ? prev.toolCalls : undefined;
            if (content || toolCalls) {
              messages.push({ id: prev.id, role: 'assistant', content, toolCalls, timestamp: prev.timestamp });
            }
          }
          assistantByMsgId.delete(lastAssistantMsgId);
        }

        parsed = { textParts: [], toolCalls: [], id, timestamp };
        assistantByMsgId.set(msgId, parsed);
      }

      lastAssistantMsgId = msgId;

      for (const block of contentBlocks) {
        const b = block as Record<string, unknown>;
        if (b.type === 'text') {
          parsed.textParts.push(b.text as string);
        } else if (b.type === 'tool_use') {
          const tc: HistoryToolCall = {
            name: b.name as string,
            input: (b.input as Record<string, unknown>) ?? {},
          };
          parsed.toolCalls.push(tc);
          const toolUseId = b.id as string;
          if (toolUseId) toolCallsByUseId.set(toolUseId, tc);
        }
      }
    }
  }

  // Flush final pending assistant message
  if (lastAssistantMsgId) {
    const pending = assistantByMsgId.get(lastAssistantMsgId);
    if (pending) {
      const content = pending.textParts.join('');
      const toolCalls = pending.toolCalls.length > 0 ? pending.toolCalls : undefined;
      if (content || toolCalls) {
        messages.push({ id: pending.id, role: 'assistant', content, toolCalls, timestamp: pending.timestamp });
      }
    }
  }

  return messages;
}
