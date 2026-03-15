import axios from 'axios';

export interface ClassifyRow {
  rowId: string;
  text: string;
}

export interface ClassifyResult {
  rowId: string;
  label: string;
  confidence: number;
  reason?: string;
  error?: string;
}

export interface ClassifyRequest {
  rows: ClassifyRow[];
  labels: string[];
  promptTemplate: string;
  promptVersion: string;
}

export interface ClassifyResponse {
  results: ClassifyResult[];
  cached: number;
  processed: number;
  model: string;
  usage: {
    promptTokens: number;
    completionTokens: number;
  };
}

interface LlmClassifyItem {
  rowId: string;
  label: string;
  confidence: number;
  reason?: string;
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
    // Fetch model from settings
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

    // Fetch decrypted API key from dedicated internal endpoint
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

function buildPrompt(rows: ClassifyRow[], labels: string[], promptTemplate: string): string {
  const labelsStr = labels.join(', ');
  const rowsText = rows.map((r, i) => `[${i + 1}] rowId="${r.rowId}": ${r.text}`).join('\n');

  let template = promptTemplate;
  if (!template.includes('{text}') && !template.includes('{labels}')) {
    template =
      'Classify the following text(s) into one of the allowed labels: {labels}.\n\nText(s):\n{text}';
  }

  return template.replace('{labels}', labelsStr).replace('{text}', rowsText);
}

async function callAnthropicBatch(
  apiKey: string,
  model: string,
  rows: ClassifyRow[],
  labels: string[],
  promptTemplate: string,
): Promise<{ items: LlmClassifyItem[]; promptTokens: number; completionTokens: number }> {
  const userPrompt = buildPrompt(rows, labels, promptTemplate);
  const labelsStr = JSON.stringify(labels);

  const systemPrompt = `You are a text classification assistant. Classify each text item into exactly one of the allowed labels.
Always respond with a valid JSON array where each element has:
- "rowId": the row identifier (string, must match input exactly)
- "label": one of the allowed labels ${labelsStr}
- "confidence": a number between 0.0 and 1.0 representing classification confidence
- "reason": a brief explanation (1-2 sentences)

Return ONLY the JSON array, no other text.`;

  const response = await axios.post<AnthropicMessage>(
    'https://api.anthropic.com/v1/messages',
    {
      model,
      max_tokens: 4096,
      system: systemPrompt,
      messages: [{ role: 'user', content: userPrompt }],
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

  const items: LlmClassifyItem[] = [];
  for (const item of parsed) {
    if (typeof item !== 'object' || item === null) continue;
    const obj = item as Record<string, unknown>;

    const rowId = String(obj.rowId ?? '');
    const rawLabel = String(obj.label ?? '');
    const confidence =
      typeof obj.confidence === 'number'
        ? obj.confidence
        : parseFloat(String(obj.confidence ?? '0'));
    const reason = obj.reason ? String(obj.reason) : undefined;

    // Validate label is in allowed list (case-insensitive fallback)
    const validLabel =
      labels.find((l) => l === rawLabel) ||
      labels.find((l) => l.toLowerCase() === rawLabel.toLowerCase()) ||
      labels[0];

    items.push({
      rowId,
      label: validLabel,
      confidence: Math.max(0, Math.min(1, isNaN(confidence) ? 0 : confidence)),
      reason,
    });
  }

  return { items, promptTokens, completionTokens };
}

export async function classifyBatch(
  request: ClassifyRequest,
  apiBaseUrl: string,
  internalToken: string,
  userId?: number,
): Promise<ClassifyResponse> {
  const { rows, labels, promptTemplate } = request;

  const { model, apiKey } = await getModelAndApiKey(apiBaseUrl, internalToken, userId);

  if (!apiKey) {
    throw new Error(
      'AI API key is not configured. Please set it in admin settings or ANTHROPIC_API_KEY env var.',
    );
  }

  const results: ClassifyResult[] = [];
  let totalPromptTokens = 0;
  let totalCompletionTokens = 0;

  // Process all rows as a single batch with 30s timeout
  const timeoutPromise = new Promise<never>((_, reject) =>
    setTimeout(() => reject(new Error('Classification batch timeout (30s)')), 30000),
  );

  try {
    const { items, promptTokens, completionTokens } = await Promise.race([
      callAnthropicBatch(apiKey, model, rows, labels, promptTemplate),
      timeoutPromise,
    ]);

    totalPromptTokens += promptTokens;
    totalCompletionTokens += completionTokens;

    // Build a map of rowId -> result
    const resultMap = new Map<string, LlmClassifyItem>();
    for (const item of items) {
      resultMap.set(item.rowId, item);
    }

    // Match results back to input rows
    for (const row of rows) {
      const item = resultMap.get(row.rowId);
      if (item) {
        results.push({
          rowId: row.rowId,
          label: item.label,
          confidence: item.confidence,
          reason: item.reason,
        });
      } else {
        results.push({
          rowId: row.rowId,
          label: labels[0],
          confidence: 0,
          error: 'Row not found in LLM response',
        });
      }
    }
  } catch (err) {
    const errorMsg = err instanceof Error ? err.message : String(err);
    for (const row of rows) {
      results.push({
        rowId: row.rowId,
        label: labels[0],
        confidence: 0,
        error: errorMsg,
      });
    }
  }

  return {
    results,
    cached: 0,
    processed: rows.length,
    model,
    usage: {
      promptTokens: totalPromptTokens,
      completionTokens: totalCompletionTokens,
    },
  };
}
