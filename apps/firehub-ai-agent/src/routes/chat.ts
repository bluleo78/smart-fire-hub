import { Router, Request, Response } from 'express';
import { execFile, spawn } from 'child_process';
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
router.get('/cli-auth', internalAuth, async (_req: Request, res: Response) => {
  try {
    const { stdout } = await execFileAsync('claude', ['auth', 'status', '--json'], {
      timeout: 5000,
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

// Claude Code CLI 로그인 — 프로세스를 유지하여 코드 입력 대기
import type { ChildProcess } from 'child_process';
let pendingLoginProcess: ChildProcess | null = null;

// Step 1: 로그인 시작 → auth URL 반환 (프로세스 유지)
router.post('/cli-auth/login', internalAuth, (_req: Request, res: Response) => {
  // 이전 프로세스가 있으면 정리
  if (pendingLoginProcess) {
    pendingLoginProcess.kill('SIGTERM');
    pendingLoginProcess = null;
  }

  try {
    const child = spawn('claude', ['auth', 'login', '--claudeai'], {
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    pendingLoginProcess = child;

    let output = '';
    let responded = false;

    const collectOutput = (stream: NodeJS.ReadableStream | null) => {
      stream?.on('data', (chunk: Buffer) => { output += chunk.toString(); });
    };
    collectOutput(child.stdout);
    collectOutput(child.stderr);

    // URL이 출력되면 즉시 응답 (프로세스는 계속 실행)
    const check = setInterval(() => {
      const urlMatch = output.match(/(https:\/\/claude\.ai\/oauth\/authorize[^\s]+)/);
      if (urlMatch && !responded) {
        responded = true;
        clearInterval(check);
        clearTimeout(timeout);
        res.json({
          success: true,
          authUrl: urlMatch[1],
          message: '브라우저에서 인증 후 표시되는 코드를 입력하세요.',
        });
      }
    }, 200);

    const timeout = setTimeout(() => {
      if (!responded) {
        responded = true;
        clearInterval(check);
        child.kill('SIGTERM');
        pendingLoginProcess = null;
        res.json({ success: false, message: '인증 URL을 가져오지 못했습니다.' });
      }
    }, 10000);

    child.on('exit', () => {
      clearInterval(check);
      clearTimeout(timeout);
      pendingLoginProcess = null;
      if (!responded) {
        responded = true;
        res.json({ success: false, message: '로그인 프로세스가 종료되었습니다.' });
      }
    });
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Agent] CLI auth login error:', errorMessage);
    res.status(500).json({ error: errorMessage });
  }
});

// Step 2: 사용자가 platform 페이지에서 복사한 코드를 전달
router.post('/cli-auth/code', internalAuth, async (req: Request, res: Response) => {
  const { code } = req.body;

  if (!code || typeof code !== 'string') {
    res.status(400).json({ error: '인증 코드를 입력하세요.' });
    return;
  }

  if (!pendingLoginProcess || pendingLoginProcess.killed) {
    res.status(400).json({ error: '대기 중인 로그인 프로세스가 없습니다. 다시 로그인을 시작하세요.' });
    return;
  }

  try {
    // stdin에 코드 전달
    pendingLoginProcess.stdin?.write(code + '\n');

    // 프로세스가 종료될 때까지 최대 10초 대기
    await new Promise<void>((resolve) => {
      const exitHandler = () => { resolve(); };
      pendingLoginProcess?.on('exit', exitHandler);
      setTimeout(() => {
        pendingLoginProcess?.removeListener('exit', exitHandler);
        resolve();
      }, 10000);
    });

    // 인증 상태 확인
    try {
      const { stdout } = await execFileAsync('claude', ['auth', 'status', '--json'], { timeout: 5000 });
      const parsed = JSON.parse(stdout) as Record<string, unknown>;
      if (parsed.loggedIn === true) {
        res.json({ success: true, message: '로그인이 완료되었습니다.' });
      } else {
        res.json({ success: false, message: '인증 코드가 유효하지 않습니다. 다시 시도하세요.' });
      }
    } catch {
      res.json({ success: false, message: '인증 상태 확인에 실패했습니다.' });
    }
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Agent] CLI auth code error:', errorMessage);
    res.status(500).json({ error: errorMessage });
  } finally {
    pendingLoginProcess = null;
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
