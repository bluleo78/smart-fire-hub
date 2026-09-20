import express, { Router, Request, Response } from 'express';
import { z } from 'zod/v4';
import { internalAuth } from '../middleware/auth.js';
import { ProviderFactory } from '../providers/index.js';

const router = Router();

// 2mb body limit for batch requests (up to 100 rows with long text)
const jsonParser = express.json({ limit: '2mb' });

const outputColumnSchema = z.object({
  name: z.string().min(1),
  type: z.enum(['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP']),
});

const classifyRequestSchema = z.object({
  rows: z.array(z.record(z.string(), z.unknown())).min(1, 'rows must not be empty'),
  prompt: z.string().min(1),
  outputColumns: z.array(outputColumnSchema).min(1, 'outputColumns must not be empty'),
  // 자격증명·모델은 firehub-api(AiAgentClient)가 관리자 설정에서 복호화해 바디로 주입한다 —
  // 채팅(/agent/chat)과 동일한 패턴. ai-agent 가 설정을 역조회하지 않는다.
  model: z.string().optional(),
  apiKey: z.string().optional(),
  oauthToken: z.string().optional(),
  // agentType 은 **필수**다(값 목록은 providers/types.ts 의 AgentType 이 단일 출처).
  // 생략을 허용하고 여기서 'sdk' 로 기본값을 주면, opencode 테넌트가 실수로 이 필드를 빠뜨린
  // 요청이 조용히 Claude SDK 경로로 떨어져 빈 apiKey 가 ambient ANTHROPIC_API_KEY 로 새는
  // 6b1c6383 과금 회귀를 재현한다. firehub-api 는 이제 이 필드를 항상 보내므로(설계서 "API
  // 인터페이스" 절) 누락은 버그 신호이고, 400 으로 거절해 그 자리에서 드러낸다.
  agentType: z.enum(['sdk', 'cli', 'cli-api', 'opencode']),
  // opencode 전용 — OpenAI 호환 provider 설정. 없으면 팩토리가 opencode 분기에서 크게 실패한다.
  baseUrl: z.string().optional(),
  providerId: z.string().optional(),
  // 현재 이 앱엔 사용처가 없다(ProviderConfig.reasoningEffort 참고) — 떨구지 않고 보존만 한다.
  reasoningEffort: z.string().optional(),
});

router.post('/classify', jsonParser, internalAuth, async (req: Request, res: Response) => {
  const parseResult = classifyRequestSchema.safeParse(req.body);
  if (!parseResult.success) {
    res.status(400).json({
      error: 'Invalid request',
      details: parseResult.error.issues,
    });
    return;
  }

  const { rows, prompt, outputColumns, model, apiKey, oauthToken, agentType, baseUrl, providerId, reasoningEffort } =
    parseResult.data;

  try {
    const provider = ProviderFactory.createClassifyProvider();
    const result = await provider.classify({
      rows,
      prompt,
      outputColumns,
      model,
      apiKey,
      oauthToken,
      agentType,
      baseUrl,
      providerId,
      reasoningEffort,
    });
    res.json(result);
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Classify] Error:', errorMessage);
    res.status(500).json({ error: 'Classification failed', details: errorMessage });
  }
});

export default router;
