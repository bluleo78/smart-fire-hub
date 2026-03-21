import { Router, Request, Response } from 'express';
import { execFile } from 'child_process';
import { promisify } from 'util';

const execFileAsync = promisify(execFile);
import { executeAgent } from '../agent/agent-sdk.js';
import { executeCliAgent } from '../agent/agent-cli.js';
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
  const {
    message,
    sessionId,
    userId,
    fileIds,
    model,
    maxTurns: reqMaxTurns,
    systemPrompt,
    temperature,
    maxTokens,
    apiKey,
    cliOauthToken,
    agentType = 'sdk',
  } = req.body;

  const hasMessage = message && typeof message === 'string';
  const hasFileIds = Array.isArray(fileIds) && fileIds.length > 0;

  if (!hasMessage && !hasFileIds) {
    res.status(400).json({ error: 'message or fileIds is required' });
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

  // 클라이언트 연결 끊김 감지 (abort 하지 않음 — Claude 작업은 계속 진행)
  let clientDisconnected = false;
  res.on('close', () => {
    if (!res.writableFinished) {
      clientDisconnected = true;
      console.log('[Agent] Client disconnected, Claude work continues');
    }
  });

  // 30초마다 ping 이벤트 전송 — Spring Boot 프록시 타임아웃 방지
  const PING_INTERVAL_MS = 30_000;
  const pingTimer = setInterval(() => {
    if (!clientDisconnected && !res.writableFinished) {
      res.write(
        `event: ping\ndata: ${JSON.stringify({ type: 'ping', timestamp: Date.now() })}\n\n`,
      );
    }
  }, PING_INTERVAL_MS);

  try {
    const agentOptions = {
      message: message || '',
      sessionId: sessionId || undefined,
      userId,
      fileIds: hasFileIds ? (fileIds as number[]) : undefined,
      model,
      maxTurns: reqMaxTurns ?? (Number(process.env.MAX_TURNS) || 10),
      systemPrompt,
      temperature,
      maxTokens,
      apiKey,
      cliOauthToken: typeof cliOauthToken === 'string' ? cliOauthToken : undefined,
    };

    const events =
      agentType === 'cli' || agentType === 'cli-api'
        ? executeCliAgent({ ...agentOptions, useSubscription: agentType === 'cli' })
        : executeAgent(agentOptions);

    for await (const event of events) {
      if (clientDisconnected) continue;

      const eventType = event.type;
      const eventData = JSON.stringify(event);
      res.write(`event: ${eventType}\n`);
      res.write(`data: ${eventData}\n\n`);
    }

    if (!clientDisconnected) {
      res.end();
    }
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Agent] Chat error:', errorMessage);

    if (clientDisconnected) return;

    if (!res.headersSent) {
      res.status(500).json({ error: 'Internal server error' });
    } else {
      res.write(`event: error\n`);
      res.write(
        `data: ${JSON.stringify({ type: 'error', message: 'Agent 처리 중 오류가 발생했습니다' })}\n\n`,
      );
      res.end();
    }
  } finally {
    clearInterval(pingTimer);
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

// Claude Code CLI 인증 상태 확인
router.get('/cli-auth', internalAuth, async (req: Request, res: Response) => {
  try {
    // DB에서 전달된 토큰이 있으면 환경변수에 설정하여 확인
    const cliOauthToken = req.query.cliOauthToken as string | undefined;
    const env = { ...process.env };
    if (cliOauthToken) {
      env.CLAUDE_CODE_OAUTH_TOKEN = cliOauthToken;
    }
    const { stdout } = await execFileAsync('claude', ['auth', 'status', '--json'], {
      timeout: 5000,
      env,
    });
    const parsed = JSON.parse(stdout) as Record<string, unknown>;
    res.json({
      loggedIn: parsed.loggedIn === true,
      email: typeof parsed.email === 'string' ? parsed.email : undefined,
      subscriptionType:
        typeof parsed.subscriptionType === 'string' ? parsed.subscriptionType : undefined,
      authMethod: typeof parsed.authMethod === 'string' ? parsed.authMethod : undefined,
    });
  } catch {
    res.json({ loggedIn: false });
  }
});

// Claude Code CLI 로그아웃
router.post('/cli-auth/logout', internalAuth, async (_req: Request, res: Response) => {
  try {
    await execFileAsync('claude', ['auth', 'logout'], { timeout: 5000 });
    res.json({ success: true });
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Agent] CLI auth logout error:', errorMessage);
    res.status(500).json({ error: errorMessage });
  }
});

export default router;
