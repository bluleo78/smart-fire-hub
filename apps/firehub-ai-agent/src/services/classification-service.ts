import axios from 'axios';

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

interface AnthropicMessage {
  id: string;
  content: Array<{ type: string; text?: string }>;
  usage: { input_tokens: number; output_tokens: number };
}

async function getModelAndApiKey(
  apiBaseUrl: string,
  internalToken: string,
  userId?: number,
): Promise<{ model: string; apiKey: string }> {
  const headers: Record<string, string> = {
    Authorization: `Internal ${internalToken}`,
    'Content-Type': 'application/json',
  };
  if (userId) {
    headers['X-On-Behalf-Of'] = String(userId);
  }

  let model = process.env.AI_DEFAULT_MODEL || 'claude-haiku-4-5-20251001';
  let apiKey = process.env.ANTHROPIC_API_KEY || '';

  try {
    const settingsResponse = await axios.get(`${apiBaseUrl}/settings`, {
      params: { prefix: 'ai.' },
      headers,
      timeout: 5000,
    });

    const data = settingsResponse.data;
    const settings: Record<string, string> = {};
    if (Array.isArray(data)) {
      for (const item of data) {
        if (item.key && item.value) {
          settings[item.key] = item.value;
        }
      }
    } else if (data && typeof data === 'object') {
      Object.assign(settings, data);
    }

    model = settings['ai.model'] || settings['ai.default_model'] || model;

    const apiKeyResponse = await axios.get(`${apiBaseUrl}/settings/ai-api-key`, {
      headers,
      timeout: 5000,
    });

    if (apiKeyResponse.data?.apiKey) {
      apiKey = apiKeyResponse.data.apiKey;
    }
  } catch {
    // Fallback to env vars
  }

  return { model, apiKey };
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

async function callAnthropicClassify(
  apiKey: string,
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

  const response = await axios.post<AnthropicMessage>(
    'https://api.anthropic.com/v1/messages',
    {
      model,
      max_tokens: 4096,
      system: systemPrompt,
      messages: [{ role: 'user', content: userMessage }],
    },
    {
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
        'content-type': 'application/json',
      },
      timeout: 30000,
    },
  );

  const promptTokens = response.data.usage.input_tokens;
  const completionTokens = response.data.usage.output_tokens;

  const contentBlock = response.data.content[0];
  if (!contentBlock || contentBlock.type !== 'text' || !contentBlock.text) {
    throw new Error('Unexpected response type from LLM');
  }

  const text = contentBlock.text.trim();

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

export async function classifyBatch(
  request: ClassifyRequest,
  apiBaseUrl: string,
  internalToken: string,
  userId?: number,
): Promise<ClassifyResponse> {
  const { rows, prompt, outputColumns } = request;

  const { model, apiKey } = await getModelAndApiKey(apiBaseUrl, internalToken, userId);

  if (!apiKey) {
    throw new Error(
      'AI API key is not configured. Please set it in admin settings or ANTHROPIC_API_KEY env var.',
    );
  }

  const timeoutPromise = new Promise<never>((_, reject) =>
    setTimeout(() => reject(new Error('Classification batch timeout (30s)')), 30000),
  );

  const { items, promptTokens, completionTokens } = await Promise.race([
    callAnthropicClassify(apiKey, model, rows, prompt, outputColumns),
    timeoutPromise,
  ]);

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
