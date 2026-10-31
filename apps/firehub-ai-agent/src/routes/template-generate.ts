import express, { Router, Request, Response } from 'express';
import { z } from 'zod/v4';
import { internalAuth } from '../middleware/auth.js';
import { ProviderFactory } from '../providers/index.js';
import { AGENT_AUTH_OR_QUOTA_FAILURE } from '../agent/ai-auth-failure.js';

/**
 * 스마트 작업 「목표 기반」의 리포트 양식 섹션 구성 생성(WD-455).
 *
 * firehub-api 의 `POST /api/v1/proactive/templates/generate` 가 부르는 내부 엔드포인트다. 사용자의 비즈니스 질문(목표)을 받아
 * 단발 completion(ProviderFactory.createCompletionProvider — 분류·GraphRAG 와 같은 경로)으로 섹션 구성(key·type·label·instruction)을
 * JSON 으로 만든다. 여기서는 "JSON 배열로 읽히는가"까지만 본다 — 섹션 규칙(key 형식·중복·타입·label) 검사는 저장 때와 같은 검사기를
 * 쓰도록 firehub-api(ReportTemplateSectionValidator, WD-298)가 맡는다.
 */
const router = Router();

/** ai-agent 쪽 호출 상한. firehub-api 의 상한(ReportTemplateAiClient.TIMEOUT)보다 작아야 원인이 담긴 응답이 먼저 올라간다. */
export const TEMPLATE_GENERATE_TIMEOUT_MS = 60_000;

/** 질문 길이 상한 — firehub-api 요청 DTO(GenerateReportTemplateRequest)와 같은 값. */
const MAX_QUESTION_LENGTH = 2000;

const requestSchema = z.object({
  question: z.string().trim().min(1).max(MAX_QUESTION_LENGTH),
  // 자격증명·모델은 firehub-api 가 관리자 설정에서 복호화해 바디로 주입한다(classify 와 같은 규약).
  model: z.string().optional(),
  apiKey: z.string().optional(),
  oauthToken: z.string().optional(),
  // agentType 은 필수다 — 생략을 'sdk' 로 기본 처리하면 opencode 자격증명이 Claude SDK 경로로 새는 6b1c6383 회귀가 재현된다(classify.ts 주석).
  agentType: z.enum(['sdk', 'cli', 'cli-api', 'opencode']),
  baseUrl: z.string().optional(),
  providerId: z.string().optional(),
  reasoningEffort: z.string().optional(),
});

/**
 * 섹션 구성 생성용 시스템 프롬프트.
 *
 * 타입 목록은 firehub-api ReportTemplateSectionValidator.SECTION_TYPES · firehub-web template-section-types.ts 와 같아야 한다
 * (앱 간 코드 공유가 없어 각자 둔다 — 한쪽을 바꾸면 함께 고친다). key 규칙도 같은 검사기의 KEY_PATTERN 과 맞춘다.
 */
export const TEMPLATE_GENERATE_SYSTEM_PROMPT = `당신은 데이터 분석 리포트의 목차(섹션 구성)를 설계하는 도우미입니다.
사용자가 준 비즈니스 질문(목표)에 답하는 정기 리포트의 섹션 구성을 만드세요.

규칙:
- 섹션은 3~8개로 만듭니다. 하위 섹션(children)은 쓰지 않습니다.
- 각 섹션은 다음 필드만 가집니다.
  - key: 영문 소문자로 시작하고 영문 소문자·숫자·밑줄(_)만 쓰는 고유 식별자 (예: summary, weekly_trend)
  - type: 아래 타입 중 하나
  - label: 화면에 보일 한국어 섹션 이름
  - instruction: 이 섹션을 채울 AI 에게 주는 한국어 지시문 (질문과 연결된 구체적 내용)
  - required: (선택) 반드시 채워야 하는 섹션이면 true
- 사용할 수 있는 type:
  - text: 서술형 텍스트(요약·분석·인사이트)
  - cards: 핵심 수치 카드(KPI·통계 요약)
  - list: 항목 나열(주요 이슈·변경사항)
  - table: 행/열 데이터(순위표·비교표)
  - comparison: 기간 비교(전주/전월 대비 변화)
  - alert: 경고/알림(이상치·임계값 초과)
  - timeline: 시간순 이벤트
  - chart: 차트/그래프 설명
  - recommendation: 분석 결과에 따른 권고 조치
- 첫 섹션은 핵심 요약(type text, required true)으로 하고, 마지막은 권고(type recommendation)로 하는 것을 권장합니다.

출력: 설명이나 코드 블록 없이 JSON 배열 하나만 출력하세요.
예: [{"key":"summary","type":"text","label":"요약","instruction":"...","required":true}]`;

/**
 * LLM 응답 텍스트에서 섹션 배열을 꺼낸다. 읽을 수 없으면 null.
 *
 * 모델이 지시와 달리 ```json 코드 블록으로 감싸거나 앞뒤에 설명을 붙이는 경우가 있어, 코드 블록을 벗기고 첫 '[' ~ 마지막 ']' 구간을
 * 다시 시도한다. `{"sections":[...]}` 형태로 감싸 와도 받아 준다. 원소가 객체가 아닌 배열·빈 배열은 실패로 본다.
 */
export function parseSectionsFromText(text: string): Record<string, unknown>[] | null {
  const candidates: string[] = [];
  const trimmed = text.trim();
  candidates.push(trimmed);
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fenced) candidates.push(fenced[1].trim());
  const start = trimmed.indexOf('[');
  const end = trimmed.lastIndexOf(']');
  if (start >= 0 && end > start) candidates.push(trimmed.slice(start, end + 1));

  for (const candidate of candidates) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(candidate);
    } catch {
      continue;
    }
    const list =
      Array.isArray(parsed)
        ? parsed
        : parsed && typeof parsed === 'object' && Array.isArray((parsed as { sections?: unknown }).sections)
          ? (parsed as { sections: unknown[] }).sections
          : null;
    if (!list || list.length === 0) continue;
    if (!list.every((item) => item !== null && typeof item === 'object' && !Array.isArray(item))) continue;
    return list as Record<string, unknown>[];
  }
  return null;
}

router.post('/template-sections/generate', express.json(), internalAuth, async (req: Request, res: Response) => {
  const parseResult = requestSchema.safeParse(req.body);
  if (!parseResult.success) {
    res.status(400).json({ error: 'Invalid request', details: parseResult.error.issues });
    return;
  }
  const { question, model, apiKey, oauthToken, agentType, baseUrl, providerId, reasoningEffort } = parseResult.data;

  let text: string;
  try {
    const provider = ProviderFactory.createCompletionProvider({
      agentType,
      apiKey,
      oauthToken,
      model,
      baseUrl,
      providerId,
      reasoningEffort,
    });
    // replace: claude_code 프리셋 없이 이 프롬프트만 쓴다 — 순수 JSON 출력이 목적이다.
    const result = await provider.complete(TEMPLATE_GENERATE_SYSTEM_PROMPT, `비즈니스 질문: ${question}`, {
      timeoutMs: TEMPLATE_GENERATE_TIMEOUT_MS,
      systemPromptMode: 'replace',
    });
    text = result.text;
  } catch (error: unknown) {
    // 원문은 로그에만 남기고, 응답에는 인증/쿼터 실패 여부를 가르는 코드만 싣는다(proactive 라우트와 같은 원칙).
    const message = error instanceof Error ? error.message : String(error);
    console.error('[TemplateGenerate] completion failed:', message);
    const code =
      error && typeof error === 'object' && (error as { code?: unknown }).code === AGENT_AUTH_OR_QUOTA_FAILURE
        ? AGENT_AUTH_OR_QUOTA_FAILURE
        : 'TEMPLATE_GENERATION_FAILED';
    res.status(502).json({ error: 'Template generation failed', code });
    return;
  }

  const sections = parseSectionsFromText(text);
  if (!sections) {
    console.error('[TemplateGenerate] unparseable output:', text.slice(0, 500));
    res.status(502).json({ error: 'Template generation returned no usable sections', code: 'TEMPLATE_OUTPUT_INVALID' });
    return;
  }
  res.json({ sections });
});

export default router;
