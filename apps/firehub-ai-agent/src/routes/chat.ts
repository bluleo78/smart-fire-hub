import { Router, Request, Response } from 'express';
import { execFile } from 'child_process';
import { promisify } from 'util';
import { ProviderFactory } from '../providers/index.js';
import type { AgentType, ProviderConfig } from '../providers/index.js';
// providers/index.js 가 아니라 providers/types.js 에서 직접 가져온다 — chat.test.ts 가
// '../providers/index.js' 를 통째로 목킹하므로(ProviderFactory 만 정의), 여기서 그 경로로
// 가져오면 목이 정의하지 않은 값이라 undefined 가 되어 라우트가 깨진다.
import { isKnownAgentType } from '../providers/types.js';
import { internalAuth } from '../middleware/auth.js';
import { readSessionTranscript } from '../agent/transcript-reader.js';
import { checkSessionOwnership } from '../agent/session-owner.js';
import { isValidTenantId } from '../agent/tenant-paths.js';
import { AdminActionableError } from '../agent/admin-actionable-error.js';

const execFileAsync = promisify(execFile);

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
    tenantId,
    userId,
    fileIds,
    model,
    maxTurns: reqMaxTurns,
    systemPrompt,
    temperature,
    maxTokens,
    apiKey,
    oauthToken,
    agentType,
    baseUrl,
    providerId,
    reasoningEffort,
    navigationContext,
    screenContext,
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

  // 테넌트는 디스크 산출물 경로의 파생 입력이라 없으면 진행할 수 없다 — 전역 경로로 폴백하는
  // 대신 400 으로 거절한다(fail-closed). firehub-api 가 항상 실어 보낸다.
  //
  // 판정은 경로 파생과 **같은 술어**를 쓴다(`isValidTenantId`) — 복제해 두면 여기가 더 느슨할 때
  // 나쁜 값이 통과해 제너레이터가 돌기 시작한 뒤 tenantSegment 에서 터지고, 그 시점엔 200 + SSE
  // 헤더가 이미 나가 있어 클라이언트는 의도한 400 대신 밋밋한 error 이벤트를 본다.
  if (!isValidTenantId(tenantId)) {
    res.status(400).json({ error: 'tenantId is required and must be a positive integer' });
    return;
  }

  // agentType 은 **필수**다 — 예전엔 생략 시 'sdk' 로 기본값을 줬는데, 그러면 opencode 테넌트가
  // 이 필드를 빠뜨린 요청이 조용히 Claude SDK 경로로 떨어진다(agentType 이 없거나 오타여도
  // ProviderFactory.createChatProvider 가 결국 'sdk' 취급 없이 즉시 throw 하긴 하지만, SSE 헤더가
  // 이미 나간 뒤라 클라이언트는 400 대신 error 이벤트를 받는다 — 여기서 일찍 걸러 명확한 400 으로
  // 끝낸다). firehub-api 는 이제 이 필드를 항상 보낸다(설계서 "API 인터페이스" 절).
  if (!isKnownAgentType(agentType)) {
    res.status(400).json({
      error: 'agentType is required and must be one of: sdk, cli, cli-api, opencode',
    });
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
    const providerConfig: ProviderConfig = {
      agentType: agentType as AgentType,
      apiKey,
      oauthToken: typeof oauthToken === 'string' ? oauthToken : undefined,
      model,
      // opencode 전용 — ProviderFactory.createChatProvider 의 opencode 분기가 이 필드들로
      // OpenCodeChatProvider 를 구성하고, agent-opencode.ts 의 buildOpenCodeConfig 가 provider
      // 블록에 그대로 싣는다(옵션 3 폐기, 2026-09-19 이슈 #693).
      baseUrl: typeof baseUrl === 'string' ? baseUrl : undefined,
      providerId: typeof providerId === 'string' ? providerId : undefined,
      reasoningEffort: typeof reasoningEffort === 'string' ? reasoningEffort : undefined,
    };
    const provider = ProviderFactory.createChatProvider(providerConfig);
    const events = provider.execute({
      message: message || '',
      sessionId: sessionId || undefined,
      tenantId,
      userId,
      fileIds: hasFileIds ? (fileIds as number[]) : undefined,
      model,
      maxTurns: reqMaxTurns ?? (Number(process.env.MAX_TURNS) || 10),
      systemPrompt: [systemPrompt, navigationContext, screenContext]
        .filter(Boolean)
        .join('\n\n') || undefined,
      temperature,
      maxTokens,
      abortSignal: undefined,
    });

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

    // 관리자가 조치해야 풀리는 실패만 문구를 그대로 내보낸다. 나머지는 예전처럼 고정 문구로
    // 뭉갠다 — 내부 오류 원문이 사용자 화면에 새면 안 된다(이슈 #350/#313).
    //
    // 왜 예외를 뒀는가: #697(배포 측 ambient 인증 파일)·#698(baseUrl SSRF 재검증)은 자바 쪽에
    // 대응 검사가 없어 ai-agent 안에서만 판별된다. 고정 문구로 뭉개면 관리자는 무엇을 고쳐야
    // 할지 알 수 없고, 운영자가 DB 질의로 대상을 찾아다니는 수밖에 없었다. AdminActionableError
    // 의 message 는 경로·호스트·IP 를 담지 않도록 그 클래스가 규칙으로 못박고 있다.
    const isActionable = error instanceof AdminActionableError;
    if (isActionable && error.detail) {
      console.error('[Agent] Chat error detail:', error.detail);
    }
    const userMessage = isActionable ? error.message : 'Agent 처리 중 오류가 발생했습니다';

    if (clientDisconnected) return;

    if (!res.headersSent) {
      res.status(500).json({ error: isActionable ? userMessage : 'Internal server error' });
    } else {
      res.write(`event: error\n`);
      res.write(`data: ${JSON.stringify({ type: 'error', message: userMessage })}\n\n`);
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
/**
 * 세션 대화 이력.
 *
 * <p><b>테넌트는 쿼리스트링으로 받는다</b> — GET 이라 바디를 쓸 수 없다. firehub-api 가
 * `?tenantId=` 를 붙여 호출한다.
 *
 * <p><b>이 게이트는 심층방어다.</b> 1차 게이트는 firehub-api 의
 * `verifySessionOwnership` + `ai_session` RLS 이고, 여기서는 세션 귀속 표식이 **다른 테넌트를
 * 가리킬 때만** 404 로 막는다. 표식이 없는 세션(세그먼트 도입 전)은 통과시킨다 — 막으면 과거
 * 이력이 전부 사라지는 기능 회귀가 된다(`session-owner.ts` 참조). 존재를 알려 주지 않기 위해
 * 403 이 아니라 404 를 쓴다.
 */
router.get('/history/:sessionId', internalAuth, async (req: Request, res: Response) => {
  const sessionId = req.params.sessionId as string;
  const tenantId = Number(req.query.tenantId);
  if (!isValidTenantId(tenantId)) {
    res.status(400).json({ error: 'tenantId query parameter is required' });
    return;
  }
  try {
    if ((await checkSessionOwnership(tenantId, sessionId)) === 'other-tenant') {
      console.warn(`[Agent] History 거부 — 세션 ${sessionId} 은 테넌트 ${tenantId} 의 것이 아니다`);
      res.status(404).json({ error: 'Not found' });
      return;
    }
    const messages = await readSessionTranscript(tenantId, sessionId);
    res.json(messages);
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Agent] History error:', errorMessage);
    res.status(500).json({ error: 'Internal server error' });
  }
});

// API 키 유효성 검증 — Claude CLI로 실제 호출
// 셸을 경유하지 않고 execFileAsync에 직접 인수 배열을 전달하여 명령어 인젝션 방지
// API 키는 환경변수(ANTHROPIC_API_KEY)로 안전하게 전달
router.post('/api-key/verify', internalAuth, async (req: Request, res: Response) => {
  const { apiKey } = req.body;
  if (!apiKey || typeof apiKey !== 'string') {
    res.json({ valid: false });
    return;
  }
  try {
    const { stdout } = await execFileAsync(
      'claude',
      ['-p', 'hi', '--output-format', 'json', '--no-session-persistence', '--model', 'haiku', '--disable-slash-commands'],
      {
        timeout: 30000,
        // 현재 환경변수를 상속하되 API 키만 덮어쓴다 — 셸 인젝션 없이 안전하게 전달
        env: { ...process.env, ANTHROPIC_API_KEY: apiKey },
      },
    );
    const parsed = JSON.parse(stdout) as Record<string, unknown>;
    res.json({ valid: parsed.is_error !== true });
  } catch {
    res.json({ valid: false });
  }
});

// CLI OAuth 토큰 유효성 검증 — Claude CLI로 실제 호출
// 셸을 경유하지 않고 execFileAsync에 직접 인수 배열을 전달하여 명령어 인젝션 방지
// OAuth 토큰은 환경변수(CLAUDE_CODE_OAUTH_TOKEN)로 안전하게 전달
router.post('/cli-auth/verify', internalAuth, async (req: Request, res: Response) => {
  const { token } = req.body;
  if (!token || typeof token !== 'string') {
    res.json({ valid: false });
    return;
  }
  try {
    // 컨테이너에 ambient ANTHROPIC_API_KEY 가 있으면 agent-cli.ts:509 주석대로 claude CLI 는
    // OAuth 토큰보다 그 키를 우선한다 — 지우지 않으면 이 엔드포인트가 "아무 토큰이나 valid:true"
    // 로 검증하고, 그 호출은 플랫폼 계정에 과금된다. 이 브랜치가 PlatformAiController.verifyCliToken()
    // 을 통해 이 경로에 새 호출자(플랫폼 화면의 "인증 확인" 버튼)를 추가했으므로 여기서 반드시
    // 지운다(전체 브랜치 리뷰 I4 — Ruling #33 을 뒤집는다).
    const childEnv: NodeJS.ProcessEnv = { ...process.env, CLAUDE_CODE_OAUTH_TOKEN: token };
    delete childEnv.ANTHROPIC_API_KEY;
    const { stdout } = await execFileAsync(
      'claude',
      ['-p', 'hi', '--output-format', 'json', '--no-session-persistence', '--model', 'haiku', '--disable-slash-commands'],
      {
        timeout: 30000,
        // 현재 환경변수를 상속하되 OAuth 토큰만 덮어쓴다 — 셸 인젝션 없이 안전하게 전달
        env: childEnv,
      },
    );
    const parsed = JSON.parse(stdout) as Record<string, unknown>;
    res.json({ valid: parsed.is_error !== true });
  } catch {
    res.json({ valid: false });
  }
});

export default router;
