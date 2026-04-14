import { readFile, readdir, access } from 'fs/promises';
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

/**
 * sessionId에 해당하는 JSONL 트랜스크립트 파일 경로를 탐색한다.
 * Claude SDK는 실행 시 cwd를 기반으로 프로젝트 디렉터리를 결정하는데,
 * Node.js 프로세스의 cwd(/app)와 다를 수 있다. 따라서 모든 프로젝트
 * 디렉터리를 스캔하여 sessionId와 일치하는 파일을 찾는다.
 */
async function findTranscriptFilePath(sessionId: string): Promise<string | null> {
  const projectsDir = path.join(os.homedir(), '.claude', 'projects');
  let projectDirs: string[];
  try {
    projectDirs = await readdir(projectsDir);
  } catch {
    return null;
  }
  for (const dir of projectDirs) {
    const candidate = path.join(projectsDir, dir, `${sessionId}.jsonl`);
    try {
      await access(candidate);
      return candidate;
    } catch {
      continue;
    }
  }
  return null;
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

  // SDK 에이전트 JSONL 트랜스크립트 — 모든 프로젝트 디렉터리에서 탐색
  const filePath = await findTranscriptFilePath(sessionId);
  if (!filePath) return [];

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
