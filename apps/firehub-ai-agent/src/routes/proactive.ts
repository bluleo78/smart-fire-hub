import express, { Router, Request, Response } from 'express';
import fs from 'fs/promises';
import { ProviderFactory } from '../providers/index.js';
import type { AgentType, ProviderConfig } from '../providers/index.js';
import { internalAuth } from '../middleware/auth.js';

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
  agentType?: string;
  cliOauthToken?: string;
}

interface OutputSection {
  key: string;
  label: string;
  content: string;
  data?: unknown;
}

/** AI 에이전트가 반환하는 프로액티브 실행 결과 */
interface ProactiveResponse {
  htmlContent: string;           // HTML 리포트 전문 (report-writer가 생성한 report.html)
  summary: string;               // 요약 텍스트 (report-writer가 생성한 summary.md)
  sections: OutputSection[];     // 하위 호환용 (deprecated, htmlContent가 없을 때 사용)
  rawText: string;
  usage: {
    inputTokens: number;
    outputTokens: number;
  };
}

const MAX_AGENT_TURNS = 15;

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

function buildProactiveSystemPrompt(template: Template | undefined, reportDir: string): string {
  let prompt =
    '당신은 프로액티브 AI 분석가입니다. 응답은 반드시 한국어로 작성하세요.\n\n' +
    '## 작업 절차\n\n' +
    '1. **분석**: 컨텍스트 데이터를 분석하세요. 필요하면 도구로 추가 데이터를 수집하세요.\n' +
    '   - 데이터셋 조회: query_dataset_data, get_data_schema, list_datasets, get_dataset\n' +
    '   - 웹 검색: WebSearch\n' +
    '2. **리포트 작성 위임**: 분석이 완료되면 **report-writer** 에이전트에게 리포트 작성을 위임하세요.\n\n' +
    '## report-writer 위임 방법\n\n' +
    'Agent 도구로 report-writer를 호출하세요. 프롬프트에 다음을 **모두** 포함하세요:\n\n' +
    '1. **분석 결과**: 수집/분석한 데이터와 인사이트\n' +
    '2. **리포트 양식**: 아래 제공되는 섹션 구조와 지시문\n' +
    `3. **파일 저장 디렉토리**: ${reportDir}\n` +
    `   - HTML 리포트: ${reportDir}/report.html (웹 뷰어용)\n` +
    `   - 마크다운 리포트: ${reportDir}/report.md (PDF/이메일용, HTML과 동일 내용)\n` +
    `   - 요약 텍스트: ${reportDir}/summary.md (채팅 알림용, 3~5줄)\n\n` +
    '**중요**: generate_report, show_chart 등 UI 도구를 호출하지 마세요. 리포트 생성은 반드시 report-writer에게 위임하세요.\n\n';

  if (template) {
    prompt += '## 리포트 양식 (report-writer에게 전달할 것)\n\n';
    if (template.style) {
      prompt += `작성 스타일: ${template.style}\n\n`;
    }
    prompt += '섹션 구조:\n';
    for (const section of template.sections) {
      if (section.static || section.type === 'divider') continue;
      if (section.type === 'group') {
        prompt += `\n### ${section.label}\n`;
        if (section.instruction) prompt += `  지시: ${section.instruction}\n`;
        if (section.children) {
          for (const child of section.children) {
            prompt += `  - ${child.label} (key: ${child.key}, type: ${child.type || 'text'})`;
            if (child.instruction) prompt += `: ${child.instruction}`;
            prompt += '\n';
          }
        }
        continue;
      }
      prompt += `- ${section.label} (key: ${section.key}, type: ${section.type || 'text'})`;
      if (section.instruction) prompt += `: ${section.instruction}`;
      prompt += '\n';
    }
  } else {
    prompt += '## 리포트 양식\n\n';
    prompt += '양식 없음. 자유 형식으로 분석 결과 리포트를 작성하도록 위임하세요.\n';
  }

  prompt += `\n리포트 저장 디렉토리: ${reportDir}\n`;

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
    // 헤더(##, ###, ####)와 그 레벨을 찾아서 매칭
    const headerRegex = /^(#{2,4})\s+(.+)$/gm;
    let matchStart = -1;
    let matchLevel = 0;
    let match: RegExpExecArray | null;

    while ((match = headerRegex.exec(text)) !== null) {
      const level = match[1].length;
      const headerLabel = match[2].trim();

      if (matchStart === -1) {
        // 라벨 매칭 (정확 일치 또는 포함 매칭)
        if (headerLabel === label || headerLabel.replace(/[^\w가-힣\s]/g, '').trim() === label) {
          matchStart = match.index + match[0].length;
          matchLevel = level;
        }
      } else {
        // 같은 레벨 이상의 다음 헤더를 만나면 종료
        if (level <= matchLevel) {
          return text.substring(matchStart, match.index).trim();
        }
      }
    }

    // 마지막 섹션인 경우
    if (matchStart !== -1) {
      return text.substring(matchStart).trim();
    }
    return '';
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

  const agentType = (body.agentType || 'sdk') as AgentType;
  const apiKey = body.apiKey || process.env.ANTHROPIC_API_KEY || '';

  // SDK/cli-api 모드에서는 API 키 필수, CLI 모드에서는 불필요 (구독 인증 사용)
  if (agentType !== 'cli' && !apiKey) {
    res.status(500).json({ error: 'ANTHROPIC_API_KEY is not configured' });
    return;
  }

  const model = body.model || 'claude-haiku-4-5-20251001';
  const userId = body.userId ?? (Number(req.headers['x-on-behalf-of']) || 0);
  // report-writer가 HTML 리포트 + 요약을 저장할 임시 디렉토리
  const reportDir = `/tmp/proactive-report-${Date.now()}-${userId}`;
  const systemPrompt = buildProactiveSystemPrompt(body.template, reportDir);
  const initialUserMessage = `${body.prompt}\n\n컨텍스트:\n${JSON.stringify(body.context)}`;

  const providerConfig: ProviderConfig = {
    agentType,
    apiKey: apiKey || undefined,
    cliOauthToken: body.cliOauthToken || undefined,
    model: model,
  };
  const provider = ProviderFactory.createChatProvider(providerConfig);

  const events = provider.execute({
    message: initialUserMessage,
    userId,
    model,
    systemPrompt: systemPrompt,
    overrideSystemPrompt: true,
    maxTurns: MAX_AGENT_TURNS,
  });

  let rawText = '';
  let totalInputTokens = 0;
  let totalOutputTokens = 0;

  try {
    for await (const event of events) {
      if (event.type === 'text') {
        rawText += event.content;
      } else if (event.type === 'done' || event.type === 'error') {
        totalInputTokens = (event.inputTokens as number) || 0;
        totalOutputTokens = (event.outputTokens as number) || 0;
        if (event.type === 'error') {
          throw new Error((event.message as string) || 'Agent execution failed');
        }
      }
    }

    // report-writer가 디렉토리에 3개 파일을 생성:
    //   report.html — 웹 뷰어용 HTML 리포트
    //   report.md   — PDF/이메일용 마크다운 리포트 (HTML과 동일 내용)
    //   summary.md  — 채팅 알림용 요약
    const [htmlResult, mdResult, summaryResult] = await Promise.allSettled([
      fs.readFile(`${reportDir}/report.html`, 'utf-8'),
      fs.readFile(`${reportDir}/report.md`, 'utf-8'),
      fs.readFile(`${reportDir}/summary.md`, 'utf-8'),
    ]);

    const htmlContent = htmlResult.status === 'fulfilled' ? htmlResult.value : '';
    const mdContent = mdResult.status === 'fulfilled' ? mdResult.value : '';
    const summary = summaryResult.status === 'fulfilled' ? summaryResult.value : '';
    const fromFile = htmlResult.status === 'fulfilled' || mdResult.status === 'fulfilled';

    if (htmlContent) console.log(`[Proactive] HTML report: ${htmlContent.length} bytes`);
    if (mdContent) console.log(`[Proactive] MD report: ${mdContent.length} bytes`);
    if (summary) console.log(`[Proactive] Summary: ${summary.length} bytes`);
    if (!fromFile) console.warn(`[Proactive] No report files found in ${reportDir}, falling back to rawText`);

    // sections: PDF/이메일에서 사용. report.md → parseSections, 없으면 rawText 폴백
    const sections = mdContent
      ? parseSections(mdContent, body.template)
      : fromFile
        ? [{ key: 'content', label: body.template?.sections?.[0]?.label || '분석 결과', content: summary }]
        : parseSections(rawText, body.template);
    console.log(`[Proactive] sections=${sections.length}, htmlContent=${htmlContent.length}B`);

    const result: ProactiveResponse = {
      htmlContent,
      summary,
      sections,
      rawText: htmlContent || rawText,
      usage: {
        inputTokens: totalInputTokens,
        outputTokens: totalOutputTokens,
      },
    };

    res.json(result);
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Proactive] Error:', errorMessage);
    res.status(500).json({ error: 'Agent execution failed', details: errorMessage });
  } finally {
    // 임시 디렉토리 정리 (report.html + report.md + summary.md)
    fs.rm(reportDir, { recursive: true }).catch(() => {});
  }
});

export default router;
