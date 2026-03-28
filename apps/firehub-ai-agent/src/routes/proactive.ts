import express, { Router, Request, Response } from 'express';
import axios from 'axios';
import { z } from 'zod/v4';
import { internalAuth } from '../middleware/auth.js';
import { FireHubApiClient } from '../mcp/api-client.js';
import { buildAllMcpTools } from '../mcp/firehub-mcp-server.js';

const router = Router();

interface TemplateSection {
  key: string;
  label: string;
  required?: boolean;
  type?: string;
}

interface Template {
  sections: TemplateSection[];
  output_format: string;
}

interface ProactiveRequest {
  prompt: string;
  template?: Template;
  context: Record<string, unknown>;
  model?: string;
  apiKey?: string;
  userId?: number;
}

interface OutputSection {
  key: string;
  label: string;
  content: string;
  data?: unknown;
}

interface ProactiveResponse {
  sections: OutputSection[];
  rawText: string;
  usage: {
    inputTokens: number;
    outputTokens: number;
  };
}

interface AnthropicTextBlock {
  type: 'text';
  text: string;
}

interface AnthropicToolUseBlock {
  type: 'tool_use';
  id: string;
  name: string;
  input: Record<string, unknown>;
}

type AnthropicContentBlock = AnthropicTextBlock | AnthropicToolUseBlock;

interface AnthropicToolResultBlock {
  type: 'tool_result';
  tool_use_id: string;
  content: string;
}

interface AnthropicResponse {
  content: AnthropicContentBlock[];
  stop_reason: string;
  usage: {
    input_tokens: number;
    output_tokens: number;
  };
}

interface AnthropicToolDefinition {
  name: string;
  description: string;
  input_schema: Record<string, unknown>;
}

const MAX_TOOL_TURNS = 10;

interface McpTool {
  name: string;
  description: string;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  inputSchema: Record<string, any>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  handler: (args: any, extra: unknown) => Promise<{ content: Array<{ type: string; text?: string }> }>;
}

function buildToolDefinitions(tools: McpTool[]): AnthropicToolDefinition[] {
  return tools.map((t) => {
    const schema = z.toJSONSchema(z.object(t.inputSchema)) as Record<string, unknown>;
    // Remove $schema field not accepted by Anthropic API
    delete schema['$schema'];
    return {
      name: t.name,
      description: t.description,
      input_schema: schema,
    };
  });
}

async function executeMcpTool(
  tools: McpTool[],
  name: string,
  input: Record<string, unknown>,
): Promise<string> {
  const tool = tools.find((t) => t.name === name);
  if (!tool) {
    return JSON.stringify({ error: `Tool '${name}' not found` });
  }
  try {
    const result = await tool.handler(input as never, {});
    return result.content.map((c) => (c.type === 'text' ? c.text : '')).join('');
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return JSON.stringify({ error: message });
  }
}

function buildProactiveSystemPrompt(template?: Template): string {
  let prompt =
    '당신은 프로액티브 AI 분석가입니다. 주어진 컨텍스트와 데이터를 분석하여 인사이트를 제공합니다.\n' +
    '응답은 반드시 한국어로 작성하세요.\n\n' +
    '필요한 데이터가 컨텍스트에 없으면 도구를 사용하여 직접 조회하세요.\n' +
    '데이터셋 데이터 조회: query_dataset_data, 데이터 스키마 조회: get_data_schema,\n' +
    '데이터셋 목록 조회: list_datasets, 데이터셋 상세 조회: get_dataset.\n\n';

  if (template) {
    prompt += `출력 형식: ${template.output_format}\n\n`;
    prompt += '다음 섹션 구조에 따라 응답을 작성하세요. 각 섹션은 ## 헤더로 구분합니다:\n\n';

    for (const section of template.sections) {
      prompt += `## ${section.label}\n`;
      if (section.required !== false) {
        prompt += `(필수 섹션)\n`;
      }
      if (section.type === 'cards') {
        prompt += `이 섹션은 카드 형식으로 출력합니다. 텍스트 설명 후 반드시 다음과 같이 JSON 코드 블록을 포함하세요:\n`;
        prompt += '```json\n[{"title": "...", "value": "...", "description": "..."}]\n```\n';
      }
      prompt += '\n';
    }
  }

  return prompt;
}

function parseSections(text: string, template?: Template): OutputSection[] {
  if (!template) {
    return [
      {
        key: 'content',
        label: '분석 결과',
        content: text.trim(),
      },
    ];
  }

  const sections: OutputSection[] = [];
  const parts = text.split(/^##\s+/m);

  for (const section of template.sections) {
    const matchingPart = parts.find((part) => {
      const firstLine = part.split('\n')[0].trim();
      return firstLine === section.label;
    });

    if (!matchingPart) {
      continue;
    }

    const lines = matchingPart.split('\n');
    lines.shift();
    const content = lines.join('\n').trim();

    const outputSection: OutputSection = {
      key: section.key,
      label: section.label,
      content,
    };

    if (section.type === 'cards') {
      const jsonMatch = content.match(/```json\s*([\s\S]*?)```/);
      if (jsonMatch) {
        try {
          outputSection.data = JSON.parse(jsonMatch[1].trim());
        } catch {
          // Keep data undefined if parsing fails
        }
      }
    }

    sections.push(outputSection);
  }

  return sections;
}

router.post('/proactive', express.json(), internalAuth, async (req: Request, res: Response) => {
  const body = req.body as ProactiveRequest;

  if (!body.prompt || !body.context) {
    res.status(400).json({ error: 'prompt and context are required' });
    return;
  }

  const apiKey = body.apiKey || process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    res.status(500).json({ error: 'ANTHROPIC_API_KEY is not configured' });
    return;
  }

  const model = body.model || 'claude-haiku-4-5-20251001';
  const systemPrompt = buildProactiveSystemPrompt(body.template);
  const initialUserMessage = `${body.prompt}\n\n컨텍스트:\n${JSON.stringify(body.context, null, 2)}`;

  // Build MCP tools for this request
  const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
  const userId = body.userId ?? (Number(req.headers['x-on-behalf-of']) || 0);
  const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, userId);
  const mcpTools: McpTool[] = buildAllMcpTools(apiClient);
  const toolDefinitions = buildToolDefinitions(mcpTools);

  type MessageParam =
    | { role: 'user'; content: string | AnthropicToolResultBlock[] }
    | { role: 'assistant'; content: AnthropicContentBlock[] };

  const messages: MessageParam[] = [{ role: 'user', content: initialUserMessage }];
  let totalInputTokens = 0;
  let totalOutputTokens = 0;
  let rawText = '';

  try {
    for (let turn = 0; turn < MAX_TOOL_TURNS; turn++) {
      const response = await axios.post<AnthropicResponse>(
        'https://api.anthropic.com/v1/messages',
        {
          model,
          max_tokens: 4096,
          system: systemPrompt,
          messages,
          tools: toolDefinitions,
        },
        {
          headers: {
            'x-api-key': apiKey,
            'anthropic-version': '2023-06-01',
            'content-type': 'application/json',
          },
        },
      );

      totalInputTokens += response.data.usage.input_tokens;
      totalOutputTokens += response.data.usage.output_tokens;

      if (response.data.stop_reason === 'end_turn') {
        rawText = response.data.content
          .filter((block): block is AnthropicTextBlock => block.type === 'text')
          .map((block) => block.text)
          .join('');
        break;
      }

      if (response.data.stop_reason === 'tool_use') {
        messages.push({ role: 'assistant', content: response.data.content });

        const toolUseBlocks = response.data.content.filter(
          (block): block is AnthropicToolUseBlock => block.type === 'tool_use',
        );
        const toolResults: AnthropicToolResultBlock[] = await Promise.all(
          toolUseBlocks.map(async (block) => ({
            type: 'tool_result' as const,
            tool_use_id: block.id,
            content: await executeMcpTool(mcpTools, block.name, block.input),
          })),
        );
        messages.push({ role: 'user', content: toolResults });
      } else {
        // Unexpected stop reason — extract text and stop
        rawText = response.data.content
          .filter((block): block is AnthropicTextBlock => block.type === 'text')
          .map((block) => block.text)
          .join('');
        break;
      }
    }

    const sections = parseSections(rawText, body.template);

    const result: ProactiveResponse = {
      sections,
      rawText,
      usage: {
        inputTokens: totalInputTokens,
        outputTokens: totalOutputTokens,
      },
    };

    res.json(result);
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Proactive] Error:', errorMessage);
    res.status(500).json({ error: 'Claude API call failed', details: errorMessage });
  }
});

export default router;
