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

/**
 * 배치 응답 상한을 행 수에 비례해 정한다(#686).
 *
 * 이전에는 50초 고정이었는데, 그 값은 작업량에서 나온 것이 아니라 상위 Spring(AiAgentClient)의
 * 60초에서 역산한 것이었다. 배치 하나는 LLM completion **한 번**이고 그 소요 시간은 출력 토큰 수
 * (≈ 행 수 × 출력 컬럼 수)에 비례하는데, 천장만 고정이니 배치를 키우면 반드시 넘긴다.
 * 운영 실측(출력 컬럼 10개, 요약 2~3문장 한국어): 10행 41초 → 행당 약 4.1초. 그런데 batchSize
 * 기본값이 20이라 **기본 설정조차 50초를 넘겼다**.
 *
 * PER_ROW 는 실측의 약 2배로 잡는다 — 프롬프트 형태에 따라 행당 비용이 달라지기 때문이다.
 *
 * CAP 이 있는 이유: batchSize 상한이 100이라 공식만 두면 830초까지 늘어난다. 그렇게 오래 붙들면
 * 호출자(pipeline-exec 스레드)가 그만큼 묶인다. CAP 을 넘길 배치는 batchSize 를 줄여야 하고,
 * 타임아웃 메시지가 그 점을 직접 말한다.
 *
 * **CAP 은 Spring AiAgentClient.TIMEOUT 보다 반드시 작아야 한다**(현재 330초). 그래야 ai-agent 가
 * 먼저 끊어 "몇 초 안에 못 끝냈다"는 진단 가능한 메시지를 남긴다 — Spring 이 먼저 끊으면 원인 없는
 * 타임아웃만 남는다. 저쪽은 일부러 **고정값**이다: 불변식이 공식 두 개의 동기화가 아니라 상수
 * 하나의 대소 관계에만 기대게 하려는 것이다.
 */
const CLASSIFY_TIMEOUT_BASE_MS = 30_000;
const CLASSIFY_TIMEOUT_PER_ROW_MS = 8_000;
const CLASSIFY_TIMEOUT_CAP_MS = 300_000;

export function classifyTimeoutMs(rowCount: number): number {
  return Math.min(
    CLASSIFY_TIMEOUT_CAP_MS,
    CLASSIFY_TIMEOUT_BASE_MS + CLASSIFY_TIMEOUT_PER_ROW_MS * Math.max(0, rowCount),
  );
}

/**
 * 출력 토큰 상한.
 *
 * 이전 구현은 4096 고정이었는데, 100행 × 출력 컬럼 다수인 배치에서 응답이 잘리면 JSON 파싱이 실패해
 * 배치 전체가 실패했다(부분 결과 없음). 여유 있게 올려 절단 자체를 줄인다.
 */
const CLASSIFY_MAX_OUTPUT_TOKENS = 16_384;

/**
 * 분류 completion 호출에 필요한 자격증명 부분집합.
 *
 * agentType/baseUrl/providerId/reasoningEffort 를 포함한 이유: opencode 테넌트는 apiKey 가
 * OpenAI 호환 키라 ProviderFactory.createCompletionProvider 가 agentType 을 보고
 * OpenAICompatCompletionProvider 로 분기해야 한다(Task 8). 이 타입에서 필드를 하나라도 떨구면
 * 라우트(classify.ts)가 아무리 잘 실어 보내도 이 함수를 거치며 사라진다 — Ruling #2 가
 * "baseUrl/providerId/reasoningEffort 가 팩토리까지 실제로 도달하는지 확인하라"고 요구하는
 * 지점이 바로 여기다. reasoningEffort 는 팩토리까지는 도달하지만 그 자리에서 멈춘다 —
 * OpenAICompatCompletionProvider·firehub-ai-agent 전체에 이 값을 실제로 쓰는 경로가 아직
 * 없다(설계서 §322, 별도 이슈).
 */
type ClassifyCredentials = Partial<
  Pick<
    ProviderConfig,
    'agentType' | 'apiKey' | 'oauthToken' | 'baseUrl' | 'providerId' | 'reasoningEffort'
  >
>;

async function callClassifyCompletion(
  credentials: ClassifyCredentials,
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
  const timeoutMs = classifyTimeoutMs(rows.length);
  let completion;
  try {
    completion = await provider.complete(systemPrompt, userMessage, {
      timeoutMs,
      maxOutputTokens: CLASSIFY_MAX_OUTPUT_TOKENS,
    });
  } catch (e) {
    // 공급자의 타임아웃 메시지는 범용이라(채팅·GraphRAG 와 공유) 왜 이 배치가 오래 걸렸는지 말해
    // 주지 않는다. 배치 규모와 해결책을 실어 실패가 스스로 원인을 설명하게 한다 — 운영에서 이
    // 실패를 받은 쪽이 다음에 무엇을 바꿔야 하는지 로그만 보고 알 수 있어야 한다.
    const message = e instanceof Error ? e.message : String(e);
    throw new Error(
      `${message} (배치 ${rows.length}행 × 출력 컬럼 ${outputColumns.length}개, 상한 ${timeoutMs}ms). ` +
        `스텝의 batchSize 를 줄이면 호출당 소요 시간이 줄어듭니다.`,
    );
  }

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
  credentials: ClassifyCredentials,
  model: string,
): Promise<ClassifyResponse> {
  const { rows, prompt, outputColumns } = request;

  // 자격증명이 비어 있어도 여기서 막지 않는다 — 단, 그 "빈 자격증명 폴백" 계약은 agentType 이
  // sdk/cli/cli-api 이거나 아예 없을 때만 유효하다(ClaudeSdkCompletionProvider 가 프로세스 환경
  // ANTHROPIC_API_KEY / CLAUDE_CODE_OAUTH_TOKEN 이나 로컬 CLI 키체인으로 폴백 — GraphRAG 경로와
  // 동일한 계약). **agentType==='opencode' 면 이 폴백은 금지된다** — opencode 의 apiKey 는 OpenAI
  // 호환 키라 Claude SDK 의 ambient 키로 새면 6b1c6383 과금 회귀가 재현된다. 그 경우
  // ProviderFactory.createCompletionProvider 가 OpenAICompatCompletionProvider 로 분기해 애초에
  // ambient 폴백 경로(buildCompletionEnv)를 타지 않는다.
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
    // 보낸 행 수(rows.length)가 아니라 **실제로 파싱된 결과 수**다(#694). 파싱 루프는 형태가
    // 어긋난 원소를 조용히 건너뛰므로, rows.length 를 싣던 예전 값은 10행을 보내고 8건이 돌아와도
    // "10건 처리"라고 보고했다.
    //
    // 이것만으로 누락이 막히지는 않는다 — 실제 판정은 id 집합을 쥔 호출부가 한다
    // (AiClassifyExecutor#verifySourceIds). 현재 Spring 쪽은 이 필드를 읽지도 않는다(집계는
    // 자기 BatchResult 로 한다). 그래도 고치는 이유는 응답이 스스로에 대해 거짓말을 하지 않게
    // 하기 위해서다 — 나중에 누가 이 값을 믿고 쓰면 그때는 결함이 된다.
    processed: items.length,
    model,
    usage: {
      promptTokens,
      completionTokens,
    },
  };
}
