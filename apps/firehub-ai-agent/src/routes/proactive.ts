import express, { Router, Request, Response } from 'express';
import fs from 'fs/promises';
import { ProviderFactory } from '../providers/index.js';
import type { AgentType, ProviderConfig } from '../providers/index.js';
// providers/index.js 가 아니라 providers/types.js 에서 직접 가져온다 — proactive.test.ts 가
// '../providers/index.js' 를 통째로 목킹하므로(ProviderFactory 만 정의) 그 경로로 가져오면
// 목이 정의하지 않은 값이라 undefined 가 되어 라우트가 깨진다.
import { isKnownAgentType } from '../providers/types.js';
import { parseProactiveExecutionId, parseSharePurpose } from '../mcp/api-client.js';
import { DEFAULT_DB_DIALECT, parseDbDialect, type DbDialect } from '../agent/db-dialect.js';
import { TIBERO_SQL_GUIDE } from '../agent/tibero-sql-guide.js';
import { internalAuth } from '../middleware/auth.js';
import { isValidTenantId, proactiveReportDir } from '../agent/tenant-paths.js';
import {
  AGENT_AUTH_OR_QUOTA_FAILURE,
  isAiCredentialFailure,
  leadingProviderErrorSignature,
} from '../agent/ai-auth-failure.js';

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
  /** 실행 테넌트. 에이전트의 디스크 산출물 경로 파생 입력 — firehub-api 가 실어 보낸다. */
  tenantId?: number;
  agentType?: string;
  oauthToken?: string;
  /** opencode 전용 — OpenAI 호환 provider 베이스 URL. */
  baseUrl?: string;
  /** opencode 전용 — provider 식별자(payload.providerId). */
  providerId?: string;
  /** opencode 전용 — 추론 강도. 현재 이 앱엔 사용처가 없다(ProviderConfig.reasoningEffort 참고). */
  reasoningEffort?: string;
  /** S3: api(ProactiveAiClient)가 'share' 를 싣는다 — 리포트는 발송되므로 MCP 호출에 공유 목적을 건다. */
  aiPurpose?: string;
  /** WD-198: api(ProactiveAiClient)가 싣는 실행 id — 이 실행의 MCP 호출에 X-Proactive-Execution-Id 로 되돌려 보낸다. */
  proactiveExecutionId?: number;
  /** WD-79: 테넌트 저장소 방언("POSTGRESQL" | "TIBERO") — parseDbDialect 로 해석한다. */
  dbDialect?: string;
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

/**
 * opencode 전용 리포트 작성 지침 (WD-62).
 *
 * <p>opencode 는 task 위임과 호스트 Write 가 막혀 report-writer 하위 에이전트를 쓸 수 없다. 그래서
 * report-writer(src/agent/subagents/report-writer/agent.md)의 핵심 작성 규칙을 요약해 메인 프롬프트에 싣고,
 * 세 파일은 reportDir 전용 MCP 도구(write_report_file)로 저장하게 한다. 위임·Agent 도구는 일절 언급하지
 * 않는다 — 막힌 위임을 언급하면 약한 모델이 `<call:Agent ...>` 를 텍스트로 흉내 낸다(2026-06-24 인시던트).
 * 저장 경로도 보여 주지 않는다 — 도구는 파일명만 받는다.
 */
const OPENCODE_REPORT_GUIDE =
  '당신은 프로액티브 AI 분석가입니다. 응답은 반드시 한국어로 작성하세요.\n\n' +
  '## 작업 절차\n\n' +
  '1. **분석**: 컨텍스트 데이터를 분석하세요. 필요하면 firehub 도구로 추가 데이터를 수집하세요.\n' +
  '   - 데이터셋 조회: firehub_list_datasets, firehub_get_dataset, firehub_get_data_schema(datasetIds 지정), firehub_query_dataset_data\n' +
  '   - 합계·평균·건수·증감률 등 요약 수치는 원시 행을 암산하지 말고 **firehub_execute_analytics_query** 로 집계 SQL(SUM/AVG/COUNT, GROUP BY)을 실행해 받은 값을 그대로 인용하세요.\n' +
  '2. **리포트 작성·저장**: 분석이 끝나면 직접 리포트를 작성해 **firehub_write_report_file** 도구로 세 파일을 저장하세요.\n' +
  '   - fileName=report.html — 웹 뷰어용 HTML 리포트\n' +
  '   - fileName=report.md — PDF/이메일용 마크다운 리포트 (HTML과 동일 내용)\n' +
  '   - fileName=summary.md — 채팅 알림용 요약 (3~5줄)\n' +
  '   - 파일마다 한 번씩, 세 파일을 **모두** 저장해야 실행이 완료됩니다. 경로는 지정하지 않습니다.\n' +
  '3. 저장 후 최종 응답으로 summary.md 와 같은 요약(3~5줄)을 출력하세요. 저장 완료 안내 같은 문구만 남기지 마세요.\n\n' +
  '**중요**: show_chart·show_table·generate_report 등 화면 표시 도구를 호출하지 마세요. 리포트는 위 세 파일로만 전달됩니다.\n\n' +
  '## report.html 작성 규칙\n\n' +
  '- `<!DOCTYPE html>` 로 시작하는 자기완결형 HTML: `<html lang="ko">`, `<head>`(meta charset, title, `<style>` 인라인), `<body>` 안 `<div class="report-container">`.\n' +
  '- 외부 리소스(CDN, 이미지 URL, 외부 스크립트) 참조 금지.\n' +
  '- **CSS 2.1 만 사용**(PDF 변환기 제약): flex·grid 금지, `<table>`·float·`margin: 0 auto` 로 배치. KPI 카드는 `<table>` 기반 그리드.\n' +
  "- 폰트 `font-family: 'Nanum Gothic', 'Malgun Gothic', sans-serif;` 본문 14px·제목 24px·소제목 18px. 색: #228be6 #40c057 #fab005 #fa5252 #868e96.\n" +
  '- 차트는 인라인 SVG 로 직접 그립니다(viewBox, 축 라벨·범례·데이터 라벨 포함).\n' +
  '- 섹션마다 `<section>` + `<h2>`(양식 label 을 그대로, 이모지·번호 금지), 섹션 사이 `<hr class="section-divider">`.\n\n' +
  '## report.md 작성 규칙\n\n' +
  '- HTML 과 동일한 내용. 섹션마다 `## 섹션라벨`(label 그대로) 헤더, 하위는 `###` 이하.\n' +
  '- 표·목록·강조를 활용하세요. `cards` 타입 섹션은 설명 뒤에 JSON 코드 블록을 넣으세요:\n' +
  '```json\n[{"title": "...", "value": "...", "description": "..."}]\n```\n\n' +
  '## summary.md 작성 규칙\n\n' +
  '- 마크다운 3~5줄, 첫 줄부터 내용(인사말 금지): **주요 발견** 1~2줄 + **권고사항** 1~2줄.\n\n' +
  '## 작성 원칙\n\n' +
  '- 데이터 나열이 아닌 인사이트 중심으로, 변화는 절대값과 변화율(%)을 함께 제시하세요.\n' +
  '- 확신이 낮으면 "~로 보입니다"로 표현하고, 조회하지 않은 수치는 지어내지 마세요.\n' +
  '- 비밀번호·토큰·개인식별정보는 리포트에 넣지 말고 마스킹하거나 집계로 대체하세요.\n' +
  '- 저장 전에 HTML 의 `<section>` 수가 양식 섹션 수와 같은지 확인하세요.\n\n';

/** 실행 경로별로 달라지는 프로액티브 프롬프트 조각 — 양식 목록을 감싸는 머리·꼬리 문구. */
interface ProactivePromptParts {
  header: string;
  templateHeading: string;
  noTemplate: string;
  footer: string;
}

/** sdk/cli: report-writer 하위 에이전트에게 reportDir 저장을 위임한다(기존 동작 — 문구를 바꾸지 않는다). */
function delegationPromptParts(reportDir: string): ProactivePromptParts {
  return {
    header:
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
      '**중요**: generate_report, show_chart 등 UI 도구를 호출하지 마세요. 리포트 생성은 반드시 report-writer에게 위임하세요.\n\n',
    templateHeading: '## 리포트 양식 (report-writer에게 전달할 것)\n\n',
    noTemplate: '## 리포트 양식\n\n양식 없음. 자유 형식으로 분석 결과 리포트를 작성하도록 위임하세요.\n',
    footer: `\n리포트 저장 디렉토리: ${reportDir}\n`,
  };
}

/**
 * opencode: 위임 대신 직접 작성·write_report_file 저장(WD-62). 위임 문구와 저장 경로를 일절 넣지 않는다 —
 * 경로가 보이면 파일명만 받는 도구에 경로를 넣으려 한다.
 */
const OPENCODE_PROMPT_PARTS: ProactivePromptParts = {
  header: OPENCODE_REPORT_GUIDE,
  templateHeading: '## 리포트 양식\n\n',
  noTemplate: '## 리포트 양식\n\n양식 없음. 자유 형식으로 분석 결과 리포트를 작성하세요.\n',
  footer: '',
};

/** 양식의 섹션 구조를 프롬프트용 목록으로 만든다(정적·구분선 섹션 제외, 그룹은 하위 섹션을 들여 씀). */
function buildTemplateSectionList(template: Template): string {
  let list = template.style ? `작성 스타일: ${template.style}\n\n` : '';
  list += '섹션 구조:\n';
  for (const section of template.sections) {
    if (section.static || section.type === 'divider') continue;
    if (section.type === 'group') {
      list += `\n### ${section.label}\n`;
      if (section.instruction) list += `  지시: ${section.instruction}\n`;
      for (const child of section.children ?? []) {
        list += `  - ${child.label} (key: ${child.key}, type: ${child.type || 'text'})`;
        if (child.instruction) list += `: ${child.instruction}`;
        list += '\n';
      }
      continue;
    }
    list += `- ${section.label} (key: ${section.key}, type: ${section.type || 'text'})`;
    if (section.instruction) list += `: ${section.instruction}`;
    list += '\n';
  }
  return list;
}

/**
 * 프로액티브 실행의 시스템 프롬프트를 만든다.
 *
 * <p>sdk/cli 는 report-writer 하위 에이전트에게 reportDir 저장을 위임하고(기존 동작), opencode 는 위임이
 * 불가능하므로 {@link OPENCODE_REPORT_GUIDE} 로 직접 작성·write_report_file 저장을 지시한다(WD-62).
 *
 * <p>dialect 가 TIBERO 면 끝(footer 뒤)에 {@link TIBERO_SQL_GUIDE} 를 붙인다(WD-79). PG 결과는 변경 전과 같다.
 */
export function buildProactiveSystemPrompt(
  template: Template | undefined,
  reportDir: string,
  agentType: AgentType,
  dialect: DbDialect = DEFAULT_DB_DIALECT,
): string {
  const parts = agentType === 'opencode' ? OPENCODE_PROMPT_PARTS : delegationPromptParts(reportDir);
  const body = template ? parts.templateHeading + buildTemplateSectionList(template) : parts.noTemplate;
  const prompt = parts.header + body + parts.footer;
  // WD-79: Proactive 는 overrideSystemPrompt 라 메인 프롬프트(Tibero SQL 규칙 포함)가 들어가지 않는다 — Tibero
  // 테넌트면 리포트 분석 SQL 도 Tibero 문법으로 쓰도록 끝에 공용 규칙을 붙인다. PG 는 변경 전과 바이트 동일.
  return dialect === 'TIBERO' ? `${prompt}\n${TIBERO_SQL_GUIDE}` : prompt;
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

/**
 * rawText가 리포트가 아니라 에이전트 실패 메시지(인증 만료·크레딧 소진·요청 한도)인지 판정한다.
 *
 * <p>왜 아직 필요한가: SDK·CLI 경로는 이제 이런 실패를 텍스트가 아니라 코드가 붙은 `error` 이벤트로
 * 알린다(#711). 그래도 구조 신호 없이 실패 문구가 텍스트로만 흘러 `done` 으로 끝나는 경우가 남을 수
 * 있어(예: 공급자·CLI 버전 차이) 이 게이트를 방어선으로 둔다 — 놓치면 오류 문자열이 리포트 본문으로
 * 둔갑해 COMPLETED 로 기록되고 CHAT/EMAIL 로 발송된다(이슈 #350).
 *
 * <p>서명 표는 공급자 오류 정책(ai-auth-failure.ts)과 공유하고, 여기서는 **맨 앞** 검사만 한다:
 * FireHub 리포트는 파이프라인/API 연결 장애를 *서술*하므로 본문에 `API Error: 401` 같은 문자열이
 * 정상적으로 등장할 수 있다. 에이전트 실패 메시지는 출력 첫 글자부터 시작하므로 startsWith 가 오탐 없는
 * 경계다.
 */
export function detectAgentFailure(rawText: string): string | null {
  return leadingProviderErrorSignature(rawText);
}

/**
 * 리포트를 만들지 못한 실행의 502 응답. firehub-api ProactiveAiClient 는 본문의 `code` 로 사용자 안내를
 * 고른다(AGENT_AUTH_OR_QUOTA_FAILURE → "AI 인증 정보 확인"). 오류 원문은 응답에 싣지 않는다.
 */
function respondNoUsableReport(res: Response, code: string): void {
  res.status(502).json({ error: 'Agent produced no usable report', code });
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

  // agentType 은 **필수**다 — 예전엔 생략 시 'sdk' 로 기본값을 줬는데, 그러면 opencode
  // 테넌트가 이 필드를 빠뜨린 요청이 조용히 Claude SDK 경로로 떨어진다. firehub-api 는 이제 이
  // 필드를 항상 보낸다(설계서 "API 인터페이스" 절) — 누락은 버그 신호이므로 400 으로 거절한다.
  if (!isKnownAgentType(body.agentType)) {
    res.status(400).json({
      error: 'agentType is required and must be one of: sdk, cli, cli-api, opencode',
    });
    return;
  }
  const agentType: AgentType = body.agentType;

  // 자격증명은 요청 바디가 준 것만 쓴다(#708). AI 자격증명은 테넌트 전용이라 firehub-api 가 항상
  // 싣는다 — 예전의 ambient ANTHROPIC_API_KEY 폴백(sdk/cli-api)은 요청과 무관한 컨테이너 계정으로
  // 조용히 과금되는 경로였으므로 걷어냈다. 비어 있으면 아래 createChatProvider 가 유형별로 크게
  // 실패한다(sdk: 키·토큰 둘 다 없음, cli: 토큰 없음, cli-api: 키 없음).
  const apiKey = body.apiKey || '';

  const model = body.model || 'claude-haiku-4-5';
  const userId = body.userId ?? (Number(req.headers['x-on-behalf-of']) || 0);
  // 챗과 같은 이유로 fail-closed — 전역 경로 폴백을 두지 않는다. 판정은 경로 파생과 같은
  // 술어를 쓴다(복제하면 여기가 느슨해져 400 대신 500 이 된다).
  const tenantId = body.tenantId;
  if (!isValidTenantId(tenantId)) {
    res.status(400).json({ error: 'tenantId is required and must be a positive integer' });
    return;
  }
  // 리포트 세 파일(HTML·마크다운·요약)이 저장될 임시 디렉토리 — sdk/cli 는 report-writer 가, opencode 는 write_report_file 도구가 쓴다
  // 리포트 산출 디렉터리도 테넌트를 담는다(코드리뷰 지적). 같은 `tmpdir` 의 첨부 다운로드에는
  // 세그먼트를 넣었는데 여기만 빼면, 한 테넌트의 에이전트가 Glob/Read 로 `proactive-report-*` 를
  // 훑어 남의 리포트 본문을 읽을 수 있다 — tenant-paths.ts 가 없애려던 비대칭 그 자체다.
  const reportDir = proactiveReportDir(tenantId, userId, Date.now());
  // WD-79: 테넌트 저장소 방언 — 같은 값을 프롬프트(아래)와 provider(MCP 도구 노출·서브에이전트 사본)에 함께 쓴다.
  const dbDialect = parseDbDialect(body.dbDialect);
  const systemPrompt = buildProactiveSystemPrompt(body.template, reportDir, agentType, dbDialect);
  const initialUserMessage = `${body.prompt}\n\n컨텍스트:\n${JSON.stringify(body.context)}`;

  let rawText = '';
  let totalInputTokens = 0;
  let totalOutputTokens = 0;

  try {
    const providerConfig: ProviderConfig = {
      agentType,
      apiKey: apiKey || undefined,
      oauthToken: body.oauthToken || undefined,
      model: model,
      // opencode 전용 — ProviderFactory.createChatProvider 의 opencode 분기가 이 필드들로
      // OpenCodeChatProvider 를 구성한다(옵션 3 폐기, 2026-09-19 이슈 #693).
      baseUrl: body.baseUrl || undefined,
      providerId: body.providerId || undefined,
      reasoningEffort: body.reasoningEffort || undefined,
    };
    // createChatProvider 는 try 안에서 만든다 — sdk/cli-api 는 자격증명이 없으면 여기서 동기적으로
    // throw 한다(예: "API key or OAuth token required"). try 밖에 있으면 async 핸들러 안의 동기
    // throw 가 처리되지 않은 Promise 거부가 되어 요청이 응답 없이 멈춘다(express 4 는 async
    // 핸들러의 예외를 자동으로 잡지 않는다). 예전엔 ambient 폴백이 항상 apiKey 를 채워 이 경로가
    // 드러나지 않았을 뿐이다 — 그 폴백을 opencode 에서 걷어낸 지금은 실제로 밟을 수 있는 경로다.
    // 자격증명 유효성 검증 자체는 라우트가 중복하지 않고 createChatProvider 에 맡긴다.
    const provider = ProviderFactory.createChatProvider(providerConfig);

    const events = provider.execute({
      message: initialUserMessage,
      tenantId,
      userId,
      model,
      systemPrompt: systemPrompt,
      overrideSystemPrompt: true,
      maxTurns: MAX_AGENT_TURNS,
      // 'share' 외의 값은 버린다 — 특히 'none'(AI 판정 제외)이 바디로 들어와도 LLM 실행에 실리면 안 된다.
      aiPurpose: parseSharePurpose(body.aiPurpose),
      // WD-198: 양의 정수만 싣는다 — 그 외 값은 헤더 없이(실행에 묶이지 않은 호출로) 간다.
      proactiveExecutionId: parseProactiveExecutionId(body.proactiveExecutionId),
      // WD-79: 테넌트 저장소 방언 — provider 가 MCP 도구 노출·설명과 서브에이전트(report-writer 등) 사본 선택에 반영한다.
      dbDialect,
      // WD-62: opencode 실행기가 이 값을 MCP 프로세스 env 로 넘겨 write_report_file 을 이 디렉터리 전용으로 연다.
      reportDir,
    });

    for await (const event of events) {
      if (event.type === 'text') {
        rawText += event.content;
      } else if (event.type === 'done' || event.type === 'error') {
        totalInputTokens = (event.inputTokens as number) || 0;
        totalOutputTokens = (event.outputTokens as number) || 0;
        if (event.type === 'error') {
          // #711: 인증·결제·요청 한도 실패는 에이전트가 이벤트에 기계 판독 코드를 싣는다. 예전엔 이 실패가
          // 텍스트로 새어 아래 detectAgentFailure 가 502 + 같은 코드를 붙였다 — firehub-api
          // ProactiveAiClient 가 응답 본문의 이 코드로 "AI 인증 정보 확인" 안내를 고르므로, 텍스트로
          // 새지 않게 된 지금은 이벤트의 코드로 같은 응답을 유지한다. 원문은 로그에만 남긴다.
          if (typeof event.code === 'string' && event.code) {
            console.error(`[Proactive] Agent failure (code=${event.code}): ${String(event.message ?? '')}`);
            respondNoUsableReport(res, event.code);
            return;
          }
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

    // 리포트 파일이 하나도 없는데 rawText마저 비었거나 에이전트 실패 메시지라면,
    // 이 실행은 분석을 전혀 수행하지 못한 것이다. 200으로 응답하면 백엔드가 COMPLETED로
    // 기록하고 오류 원문을 리포트 본문 삼아 CHAT/EMAIL로 발송한다 (이슈 #350).
    // 리포트 파일이 하나라도 생성됐다면 정상 실행이므로 이 게이트를 타지 않는다.
    if (!fromFile) {
      const failureSignature = detectAgentFailure(rawText);
      if (failureSignature || !rawText.trim()) {
        // 원문(에러 메시지·request_id 포함)은 서버 로그에만 남기고 응답 본문에는 싣지 않는다.
        console.error(
          `[Proactive] Agent produced no usable report (signature=${failureSignature ?? 'empty-output'}). raw: ${rawText.slice(0, 500)}`,
        );
        respondNoUsableReport(res, failureSignature ? AGENT_AUTH_OR_QUOTA_FAILURE : 'AGENT_EMPTY_OUTPUT');
        return;
      }
    }

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
    // 자격증명 없음(createChatProvider 가 동기적으로 던짐)·인증 실패는 이벤트 경로와 같은 502 + 코드로
    // 응답한다 — firehub-api 가 이 코드로 "AI 인증 정보 확인" 안내를 고른다. 그 밖의 예외는 500.
    if (isAiCredentialFailure(error)) {
      respondNoUsableReport(res, error.code);
      return;
    }
    res.status(500).json({ error: 'Agent execution failed', details: errorMessage });
  } finally {
    // 임시 디렉토리 정리 (report.html + report.md + summary.md)
    fs.rm(reportDir, { recursive: true }).catch(() => {});
  }
});

export default router;
