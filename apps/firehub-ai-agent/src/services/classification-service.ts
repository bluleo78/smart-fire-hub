import { ProviderFactory } from '../providers/provider-factory.js';
import type { ProviderConfig } from '../providers/types.js';

export interface OutputColumn {
  name: string;
  type: 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'TIMESTAMP';
}

export interface ClassifyRequest {
  rows: Record<string, unknown>[];
  prompt: string;
  outputColumns: OutputColumn[];
}

export interface ClassifyRowResult {
  source_id: number;
  [key: string]: unknown;
}

export interface ClassifyResponse {
  results: ClassifyRowResult[];
  processed: number;
  model: string;
  usage: {
    promptTokens: number;
    completionTokens: number;
  };
}

function coerceValue(value: unknown, type: OutputColumn['type']): unknown {
  if (value === null || value === undefined) return null;

  switch (type) {
    case 'TEXT':
      return String(value);
    case 'INTEGER': {
      const n = parseInt(String(value), 10);
      return isNaN(n) ? null : n;
    }
    case 'DECIMAL': {
      const f = parseFloat(String(value));
      return isNaN(f) ? null : f;
    }
    case 'BOOLEAN':
      if (typeof value === 'boolean') return value;
      if (value === 'true' || value === 1) return true;
      if (value === 'false' || value === 0) return false;
      return null;
    case 'DATE':
    case 'TIMESTAMP':
      return String(value);
    default:
      return value;
  }
}

/** 배치 응답 상한(ms). 상위 Spring(AiAgentClient)의 60초 타임아웃보다 짧게 잡아 원인을 이쪽에서 남긴다. */
const CLASSIFY_TIMEOUT_MS = 50_000;

/**
 * 출력 토큰 상한.
 *
 * 이전 구현은 4096 고정이었는데, 100행 × 출력 컬럼 다수인 배치에서 응답이 잘리면 JSON 파싱이 실패해
 * 배치 전체가 실패했다(부분 결과 없음). 여유 있게 올려 절단 자체를 줄인다.
 */
const CLASSIFY_MAX_OUTPUT_TOKENS = 16_384;

async function callClassifyCompletion(
  credentials: Pick<ProviderConfig, 'apiKey' | 'oauthToken'>,
  model: string,
  rows: Record<string, unknown>[],
  prompt: string,
  outputColumns: OutputColumn[],
): Promise<{ items: ClassifyRowResult[]; promptTokens: number; completionTokens: number }> {
  const outputSchema = outputColumns.map((c) => `"${c.name}" (${c.type})`).join(', ');
  const columnNames = outputColumns.map((c) => `"${c.name}"`).join(', ');

  const systemPrompt = `You are a data processing assistant. Process each input row according to the user's instructions and return structured results.

Always respond with a valid JSON array where each element has:
- "source_id": the integer value from the row's "id" field (REQUIRED)
${outputColumns.map((c) => `- "${c.name}": ${c.type} value`).join('\n')}

Output schema: source_id (INTEGER), ${outputSchema}

Rules:
- Return ONLY the JSON array, no other text
- Each result must have source_id matching the input row's id
- For INTEGER fields: return integer numbers only
- For DECIMAL fields: return decimal numbers only
- For BOOLEAN fields: return true or false only
- For TEXT/DATE/TIMESTAMP fields: return string values
- Process every input row — the output array must have the same number of elements as the input
- Output columns: ${columnNames}`;

  const userMessage = `${prompt}\n\nInput rows (JSON):\n${JSON.stringify(rows, null, 2)}`;

  // 채팅·GraphRAG 와 동일한 CompletionProvider 경로를 사용한다.
  // (이전에는 axios 로 api.anthropic.com 을 x-api-key 로 직접 호출했는데, prod 는 ai.api_key 가
  //  비어 있고 ai.cli_oauth_token(OAuth)만 설정되어 있어 인증 자체가 불가능했다.)
  const provider = ProviderFactory.createCompletionProvider({ ...credentials, model });
  const completion = await provider.complete(systemPrompt, userMessage, {
    timeoutMs: CLASSIFY_TIMEOUT_MS,
    maxOutputTokens: CLASSIFY_MAX_OUTPUT_TOKENS,
  });

  const promptTokens = completion.usage?.inputTokens ?? 0;
  const completionTokens = completion.usage?.outputTokens ?? 0;

  const text = completion.text.trim();
  if (!text) {
    throw new Error('LLM returned an empty response');
  }

  // Extract JSON array from response (handle markdown code blocks)
  let jsonText = text;
  const jsonMatch = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (jsonMatch) {
    jsonText = jsonMatch[1].trim();
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(jsonText);
  } catch {
    throw new Error(`Failed to parse LLM response as JSON: ${text.substring(0, 200)}`);
  }

  if (!Array.isArray(parsed)) {
    throw new Error(`LLM response is not an array: ${jsonText.substring(0, 200)}`);
  }

  const items: ClassifyRowResult[] = [];
  for (const item of parsed) {
    if (typeof item !== 'object' || item === null) continue;
    const obj = item as Record<string, unknown>;

    const sourceId =
      typeof obj.source_id === 'number'
        ? obj.source_id
        : parseInt(String(obj.source_id ?? '0'), 10);

    const result: ClassifyRowResult = { source_id: isNaN(sourceId) ? 0 : sourceId };

    for (const col of outputColumns) {
      result[col.name] = coerceValue(obj[col.name], col.type);
    }

    items.push(result);
  }

  return { items, promptTokens, completionTokens };
}

/**
 * 행 배치를 LLM 으로 분류한다.
 *
 * 자격증명·모델은 호출자(Spring AiAgentClient → routes/classify.ts)가 요청 바디로 전달한다.
 * 이전에는 이 함수가 firehub-api 의 {@code /settings/ai-api-key} 를 역호출해 스스로 키를 가져왔는데,
 * 그 엔드포인트는 ADMIN 전용 권한을 요구해 비-ADMIN 사용자의 파이프라인이 조용히 실패했고
 * (조회 실패를 catch 로 삼켜 "키 미설정" 메시지로 둔갑) OAuth 토큰은 아예 전달되지 않았다.
 */
export async function classifyBatch(
  request: ClassifyRequest,
  credentials: Pick<ProviderConfig, 'apiKey' | 'oauthToken'>,
  model: string,
): Promise<ClassifyResponse> {
  const { rows, prompt, outputColumns } = request;

  // 자격증명이 비어 있어도 여기서 막지 않는다 — CompletionProvider 가 프로세스 환경
  // (ANTHROPIC_API_KEY / CLAUDE_CODE_OAUTH_TOKEN)이나 로컬 CLI 키체인으로 폴백하며,
  // 이는 GraphRAG 경로와 동일한 계약이다. 진짜로 인증이 없으면 SDK 가 subtype/원인을 담아 실패한다.
  //
  // 타임아웃은 CompletionProvider 안에서 abort 로 처리한다.
  // (이전에는 axios timeout 과 Promise.race 가 이중으로 걸려 race 가 이겨도 HTTP 요청이 취소되지 않았다.)
  const { items, promptTokens, completionTokens } = await callClassifyCompletion(
    credentials,
    model,
    rows,
    prompt,
    outputColumns,
  );

  return {
    results: items,
    processed: rows.length,
    model,
    usage: {
      promptTokens,
      completionTokens,
    },
  };
}
