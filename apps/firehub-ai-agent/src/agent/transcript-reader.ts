import { readFile } from 'fs/promises';
import path from 'path';
import os from 'os';

export interface HistoryMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
}

function getProjectId(): string {
  const cwd = process.cwd();
  // Replace all `/` with `-` (keeps leading dash, matching Claude's convention)
  return cwd.replace(/\//g, '-');
}

interface ParsedAssistant {
  textParts: string[];
  id: string;
  timestamp: string;
}

export async function readSessionTranscript(sessionId: string): Promise<HistoryMessage[]> {
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
          // Only include assistant messages that have text content
          if (content) {
            messages.push({ id: pending.id, role: 'assistant', content, timestamp: pending.timestamp });
          }
        }
        assistantByMsgId.clear();
        lastAssistantMsgId = null;
      }

      // Skip user messages that contain any tool_result block
      const hasToolResult = contentBlocks.some(
        (block) => (block as Record<string, unknown>).type === 'tool_result'
      );
      if (hasToolResult) continue;

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
            if (content) {
              messages.push({ id: prev.id, role: 'assistant', content, timestamp: prev.timestamp });
            }
          }
          assistantByMsgId.delete(lastAssistantMsgId);
        }

        parsed = { textParts: [], id, timestamp };
        assistantByMsgId.set(msgId, parsed);
      }

      lastAssistantMsgId = msgId;

      for (const block of contentBlocks) {
        const b = block as Record<string, unknown>;
        if (b.type === 'text') {
          parsed.textParts.push(b.text as string);
        }
      }
    }
  }

  // Flush final pending assistant message
  if (lastAssistantMsgId) {
    const pending = assistantByMsgId.get(lastAssistantMsgId);
    if (pending) {
      const content = pending.textParts.join('');
      if (content) {
        messages.push({ id: pending.id, role: 'assistant', content, timestamp: pending.timestamp });
      }
    }
  }

  return messages;
}
