import { Router, Request, Response } from 'express';
import { executeAgent } from '../agent/agent-sdk.js';
import { internalAuth } from '../middleware/auth.js';
import { readSessionTranscript } from '../agent/transcript-reader.js';

const router = Router();

// Health check endpoint
router.get('/health', (req: Request, res: Response) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
  });
});

// SSE chat endpoint
router.post('/chat', internalAuth, async (req: Request, res: Response) => {
  const { message, sessionId, userId } = req.body;

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
    const events = executeAgent({
      message,
      sessionId,
      userId,
      maxTurns: Number(process.env.MAX_TURNS) || 10,
      abortSignal: abortController.signal,
    });

    for await (const event of events) {
      if (abortController.signal.aborted) break;

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
