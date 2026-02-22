import { Router, Request, Response } from 'express';
import { executeAgent } from '../agent/agent-sdk.js';
import { internalAuth } from '../middleware/auth.js';
import { readSessionTranscript } from '../agent/transcript-reader.js';
import { shouldCompact, generateSummary } from '../agent/compaction.js';

const router = Router();

// In-memory token usage per session (lost on restart, which is acceptable)
const sessionTokens = new Map<string, number>();

// Health check endpoint
router.get('/health', (req: Request, res: Response) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
  });
});

// SSE chat endpoint
router.post('/chat', internalAuth, async (req: Request, res: Response) => {
  const { message, sessionId, userId, model, maxTurns: reqMaxTurns, systemPrompt, temperature, maxTokens, sessionMaxTokens } = req.body;

  if (!message || typeof message !== 'string') {
    res.status(400).json({ error: 'message is required and must be a string' });
    return;
  }

  if (!userId || typeof userId !== 'number') {
    res.status(400).json({ error: 'userId is required and must be a number' });
    return;
  }

  // Set SSE headers
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no');
  res.setHeader('Transfer-Encoding', 'chunked');

  res.write(':ok\n\n');

  const abortController = new AbortController();

  res.on('close', () => {
    if (!res.writableFinished) {
      abortController.abort();
    }
  });

  try {
    let effectiveSessionId = sessionId || undefined;
    let effectiveMessage = message;

    // Check if session needs compaction
    const compactionThreshold = typeof sessionMaxTokens === 'number' && sessionMaxTokens > 0 ? sessionMaxTokens : undefined;
    if (effectiveSessionId && await shouldCompact(effectiveSessionId, sessionTokens, compactionThreshold)) {
      console.log(`[Compaction] Session ${effectiveSessionId} exceeds token threshold, compacting...`);
      try {
        const summary = await generateSummary(effectiveSessionId);
        if (summary) {
          // Notify frontend that compaction is happening
          res.write(`data: ${JSON.stringify({ type: 'text', content: '이전 대화를 요약하고 새 세션으로 전환합니다...\n\n' })}\n\n`);
          // Prepend summary to user message (system prompt keeps its default in agent-sdk.ts)
          effectiveMessage = `[이전 대화 요약]\n${summary}\n\n---\n\n${message}`;
          console.log(`[Compaction] Summary generated (${summary.length} chars), starting new session`);
        }
        sessionTokens.delete(effectiveSessionId);
        effectiveSessionId = undefined; // Force new session
      } catch (error) {
        console.error('[Compaction] Failed, falling back to resume:', error);
        // Fall back to normal resume if compaction fails
      }
    }

    const events = executeAgent({
      message: effectiveMessage,
      sessionId: effectiveSessionId,
      userId,
      model,
      maxTurns: reqMaxTurns ?? (Number(process.env.MAX_TURNS) || 10),
      systemPrompt,
      temperature,
      maxTokens,
      abortSignal: abortController.signal,
    });

    for await (const event of events) {
      if (abortController.signal.aborted) break;

      // Track token usage for compaction decisions
      if (event.type === 'done' && typeof event.inputTokens === 'number' && event.inputTokens > 0) {
        const sid = event.sessionId as string;
        if (sid) {
          sessionTokens.set(sid, event.inputTokens);
          console.log(`[Compaction] Session ${sid} token count: ${event.inputTokens}`);
        }
      }

      const eventType = event.type;
      const eventData = JSON.stringify(event);
      res.write(`event: ${eventType}\n`);
      res.write(`data: ${eventData}\n\n`);
    }

    res.end();
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Agent] Chat error:', errorMessage);

    if (!res.headersSent) {
      res.status(500).json({ error: 'Internal server error' });
    } else {
      res.write(`event: error\n`);
      res.write(`data: ${JSON.stringify({ type: 'error', message: 'Agent 처리 중 오류가 발생했습니다' })}\n\n`);
      res.end();
    }
  }
});

// Session listing placeholder
router.get('/sessions', internalAuth, (_req: Request, res: Response) => {
  res.json({
    message: 'Session listing is managed by firehub-api',
    sessions: [],
  });
});

// Session history endpoint
router.get('/history/:sessionId', internalAuth, async (req: Request, res: Response) => {
  try {
    const messages = await readSessionTranscript(req.params.sessionId as string);
    res.json(messages);
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Agent] History error:', errorMessage);
    res.status(500).json({ error: 'Internal server error' });
  }
});

export default router;
