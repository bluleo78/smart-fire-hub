import { stat } from 'fs/promises';
import path from 'path';
import os from 'os';
import { readSessionTranscript } from './transcript-reader.js';
import {
  DEFAULT_COMPACTION_THRESHOLD,
  BYTES_PER_TOKEN,
  COMPACTION_RECENT_MESSAGES,
  COMPACTION_CONTENT_MAX_LENGTH,
  COMPACTION_SUMMARY_MAX_TOKENS,
  COMPACTION_MODEL,
} from '../constants.js';

function getSessionFilePath(sessionId: string): string {
  const projectId = process.cwd().replace(/\//g, '-');
  return path.join(os.homedir(), '.claude', 'projects', projectId, `${sessionId}.jsonl`);
}

export async function shouldCompact(
  sessionId: string,
  tokenStore: Map<string, number>,
  threshold?: number,
): Promise<boolean> {
  const tokenThreshold = threshold ?? DEFAULT_COMPACTION_THRESHOLD;
  const fileSizeThreshold = tokenThreshold * BYTES_PER_TOKEN;

  // 1. Check in-memory token store first (fast path)
  const storedTokens = tokenStore.get(sessionId);
  if (storedTokens !== undefined) {
    return storedTokens > tokenThreshold;
  }

  // 2. Fallback: check JSONL file size as proxy for token count
  try {
    const filePath = getSessionFilePath(sessionId);
    const stats = await stat(filePath);
    if (stats.size > fileSizeThreshold) {
      console.log(
        `[Compaction] Session file ${sessionId} is ${(stats.size / 1024).toFixed(0)}KB (threshold: ${(fileSizeThreshold / 1024).toFixed(0)}KB, tokenThreshold: ${tokenThreshold})`,
      );
      return true;
    }
  } catch {
    // File not found = new session, no compaction needed
  }

  return false;
}

export async function generateSummary(sessionId: string): Promise<string> {
  const messages = await readSessionTranscript(sessionId);
  if (messages.length === 0) return '';

  // Build transcript (truncate to last N messages to keep summary prompt small)
  const recent = messages.slice(-COMPACTION_RECENT_MESSAGES);
  const transcript = recent
    .map((m) => `[${m.role}]: ${m.content.slice(0, COMPACTION_CONTENT_MAX_LENGTH)}`)
    .join('\n\n');

  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    console.warn('[Compaction] ANTHROPIC_API_KEY not set, skipping summary generation');
    return buildFallbackSummary(messages);
  }

  try {
    const response = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model: COMPACTION_MODEL,
        max_tokens: COMPACTION_SUMMARY_MAX_TOKENS,
        messages: [
          {
            role: 'user',
            content: `다음은 AI 어시스턴트와 사용자의 대화 기록입니다. 간결하게 요약하세요.

요약에 반드시 포함할 내용:
- 사용자가 다루고 있던 데이터셋 이름과 ID
- 수행한 주요 작업 (생성, 수정, 삭제, 조회 등)
- 마지막 요청의 결과와 현재 상태
- 진행 중이던 작업이 있다면 그 내용

요약은 3~5문장으로 작성하세요.

---
${transcript}
---

요약:`,
          },
        ],
      }),
    });

    if (!response.ok) {
      console.error(`[Compaction] Anthropic API error: ${response.status}`);
      return buildFallbackSummary(messages);
    }

    const data = (await response.json()) as { content: Array<{ type: string; text?: string }> };
    const textBlock = data.content?.find((b) => b.type === 'text');
    return textBlock?.text ?? buildFallbackSummary(messages);
  } catch (error) {
    console.error('[Compaction] Summary generation failed:', error);
    return buildFallbackSummary(messages);
  }
}

function buildFallbackSummary(messages: Array<{ role: string; content: string }>): string {
  // Template-based fallback: extract last user message and last assistant response
  const lastUser = [...messages].reverse().find((m) => m.role === 'user');
  const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
  const parts: string[] = [];
  if (lastUser) parts.push(`마지막 사용자 요청: ${lastUser.content.slice(0, 300)}`);
  if (lastAssistant) parts.push(`마지막 응답: ${lastAssistant.content.slice(0, 300)}`);
  return parts.join('\n') || '이전 대화 내용이 있습니다.';
}
