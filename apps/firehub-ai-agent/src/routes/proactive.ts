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
  instruction?: string;
  static?: boolean;
  content?: string;
  children?: TemplateSection[];
}

interface Template {
  sections: TemplateSection[];
  output_format: string;
  style?: string;
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

export function buildSectionPrompt(sections: TemplateSection[], depth = 1): string {
  let prompt = '';
  const headerPrefix = '#'.repeat(depth + 1); // ## for depth 1, ### for depth 2

  for (const section of sections) {
    if (section.static) {
      prompt += `${headerPrefix} ${section.label}\n`;
      prompt += '(정적 섹션 — 이 섹션은 생성하지 마세요. 시스템이 자동으로 채웁니다.)\n\n';
      continue;
    }

    if (section.type === 'divider') {
      continue;
    }

    if (section.type === 'group') {
      prompt += `${headerPrefix} ${section.label}\n`;
      if (section.instruction) {
        prompt += `지시: ${section.instruction}\n`;
      }
      if (section.children && section.children.length > 0) {
        prompt += buildSectionPrompt(section.children, depth + 1);
      }
      prompt += '\n';
      continue;
    }

    prompt += `${headerPrefix} ${section.label}\n`;
    if (section.required !== false) {
      prompt += '(필수 섹션)\n';
    }
    if (section.instruction) {
      prompt += `지시: ${section.instruction}\n`;
    }
    const typeGuide = getSectionTypeGuide(section.type);
    if (typeGuide) {
      prompt += typeGuide + '\n';
    }
    prompt += '\n';
  }

  return prompt;
}

function buildProactiveSystemPrompt(template?: Template): string {
  let prompt =
    '당신은 프로액티브 AI 분석가입니다. 주어진 컨텍스트와 데이터를 분석하여 인사이트를 제공합니다.\n' +
    '응답은 반드시 한국어로 작성하세요.\n\n' +
    '필요한 데이터가 컨텍스트에 없으면 도구를 사용하여 직접 조회하세요.\n' +
    '데이터셋 데이터 조회: query_dataset_data, 데이터 스키마 조회: get_data_schema,\n' +
    '데이터셋 목록 조회: list_datasets, 데이터셋 상세 조회: get_dataset.\n\n';

  prompt +=
    '## 분석 원칙\n' +
    '- 데이터 나열이 아닌 인사이트 중심으로 서술하세요.\n' +
    '- "왜 이 수치가 변했는가"를 파악하고, 가능한 원인을 제시하세요.\n' +
    '- 컨텍스트에 previousExecutions(이전 실행 결과)가 있으면 비교하여 변화 추이를 언급하세요.\n' +
    '- 변화를 언급할 때는 절대값과 변화율(%)을 함께 제시하세요.\n' +
    '- 확신이 낮으면 "~로 보입니다", "확인이 필요합니다" 등으로 표현하세요.\n' +
    '- 권고사항은 "무엇을 해야 하는가"를 구체적으로 제시하세요.\n\n';

  if (template) {
    if (template.style) {
      prompt += `## 작성 스타일\n${template.style}\n\n`;
    }

    prompt += `출력 형식: ${template.output_format}\n\n`;
    prompt += '다음 섹션 구조에 따라 응답을 작성하세요. 각 섹션은 헤더(##, ###, ####)로 구분합니다:\n\n';

    prompt += buildSectionPrompt(template.sections);
  }

  return prompt;
}

function getSectionTypeGuide(type?: string): string | null {
  switch (type) {
    case 'text':
      return '마크다운 서술. 핵심 발견(key finding)을 먼저 쓰고 근거를 뒤에 배치하세요.';
    case 'cards':
      return (
        '카드 형식으로 출력합니다. 텍스트 설명 후 반드시 다음과 같이 JSON 코드 블록을 포함하세요:\n' +
        '```json\n[{"title": "...", "value": "...", "description": "..."}]\n```\n' +
        '가능하면 이전 값 대비 변화를 description에 포함하세요.'
      );
    case 'list':
      return '중요도/심각도 순으로 정렬하세요. 각 항목에 맥락(왜 중요한지) 한 줄을 추가하세요.';
    case 'table':
      return '마크다운 테이블 형식. 비교 항목이 있으면 변화율 컬럼을 추가하세요.';
    case 'comparison':
      return '"이번 기간 vs 이전 기간: +N% (절대값)" 패턴으로 기간 비교를 작성하세요.';
    case 'alert':
      return '심각도 순(CRITICAL → WARNING → INFO)으로 정렬. 각 알림에 권장 조치를 포함하세요.';
    case 'timeline':
      return '시간순으로 나열. 각 이벤트에 영향도 설명을 한 줄 추가하세요.';
    case 'chart':
      return '차트/그래프에 대한 해석을 서술하세요. 추세, 이상값, 패턴을 자연어로 설명하세요.';
    case 'recommendation':
      return '구체적 액션 + 기대 효과 + 우선순위를 기술하세요. 실행 가능한 단계로 작성하세요.';
    case 'group':
      return null;
    case 'divider':
      return null;
    default:
      return null;
  }
}

export function parseSections(text: string, template?: Template): OutputSection[] {
  if (!template) {
    return [{ key: 'content', label: '분석 결과', content: text.trim() }];
  }

  function findContentForLabel(label: string): string {
    const parts = text.split(/^#{2,4}\s+/m);
    const matchingPart = parts.find((part) => {
      const firstLine = part.split('\n')[0].trim();
      return firstLine === label;
    });
    if (!matchingPart) return '';
    const lines = matchingPart.split('\n');
    lines.shift();
    return lines.join('\n').trim();
  }

  function processSections(templateSections: TemplateSection[]): OutputSection[] {
    const result: OutputSection[] = [];
    for (const section of templateSections) {
      if (section.static || section.type === 'divider') continue;

      if (section.type === 'group') {
        if (section.children) {
          result.push(...processSections(section.children));
        }
        continue;
      }

      const content = findContentForLabel(section.label);
      if (!content) continue;

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
          } catch { /* keep data undefined */ }
        }
      }

      result.push(outputSection);
    }
    return result;
  }

  return processSections(template.sections);
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
