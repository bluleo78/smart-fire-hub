import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * 11개 subagent rules.md/agent.md 의 4 레이어 구조 일관성 검증 (refs #260 PR-5).
 *
 * 각 subagent 는 rules.md (또는 rules.md 가 없으면 agent.md) 최상단에 4 레이어 구조를
 * 명시한 HTML 주석을 보유해야 한다. 메인 SYSTEM_PROMPT 와 호응 관계가 일관되게
 * 명문화되어, 누군가 새 subagent 를 추가하거나 기존 subagent 를 수정할 때 구조를
 * 잃어버리지 않도록 한다.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const SUBAGENTS = [
  'admin-manager',
  'api-connection-manager',
  'audit-analyst',
  'dashboard-builder',
  'data-analyst',
  'dataset-manager',
  'pipeline-builder',
  'report-writer',
  'smart-job-manager',
  'template-builder',
  'trigger-manager',
];

function readPrimary(subagent: string): string {
  const rulesPath = path.join(__dirname, subagent, 'rules.md');
  const agentPath = path.join(__dirname, subagent, 'agent.md');
  if (fs.existsSync(rulesPath)) return fs.readFileSync(rulesPath, 'utf-8');
  return fs.readFileSync(agentPath, 'utf-8');
}

describe('11개 subagent 4 레이어 구조 일관성 (refs #260 PR-5)', () => {
  it('모든 subagent 가 4 레이어 구조 주석을 보유한다', () => {
    for (const sa of SUBAGENTS) {
      const content = readPrimary(sa);
      expect(content, `${sa} primary doc 에 4 레이어 구조 주석이 있어야 함`).toMatch(
        /<!--[\s\S]*?4 레이어 구조[\s\S]*?-->/,
      );
    }
  });

  it('모든 subagent 의 헤더 주석이 메인 SYSTEM_PROMPT 와 호응함을 명시한다', () => {
    for (const sa of SUBAGENTS) {
      const content = readPrimary(sa);
      const headerComment = content.match(/<!--[\s\S]*?-->/)?.[0] ?? '';
      expect(headerComment, `${sa} 헤더 주석에 "메인 SYSTEM_PROMPT" 언급 필요`).toContain(
        '메인 SYSTEM_PROMPT',
      );
    }
  });

  it('builders (pipeline/dashboard/template) 은 L3 통합 가드 + Mode 마커 명시', () => {
    for (const builder of ['pipeline-builder', 'dashboard-builder', 'template-builder']) {
      const rules = readPrimary(builder);
      expect(rules, `${builder} rules.md 에 Mode 마커 처리 명시`).toContain('Mode: DESIGN');
      expect(rules, `${builder} rules.md 에 Mode 마커 처리 명시`).toContain('Mode: CREATE-APPROVED');
    }
  });

  it('data-analyst / audit-analyst 는 L5 PII 마스킹 참조 명시', () => {
    for (const analyst of ['data-analyst', 'audit-analyst']) {
      const rules = readPrimary(analyst);
      expect(rules, `${analyst} 에 메인 L5 PII 참조 필요`).toMatch(/L5|PII 마스킹/);
    }
  });

  it('admin-manager / audit-analyst 는 권한 키 평문 노출이 없다 (메인 L2 정책)', () => {
    for (const sa of ['admin-manager', 'audit-analyst']) {
      const content = readPrimary(sa);
      // 권한 키 패턴: "X:read 권한이 필요", "관리자 전용" 형태의 사용자 응답 텍스트
      const userFacingPermKey = content.match(/"[^"]*(?:user|role|audit|admin):(?:read|write|assign)[^"]*권한[^"]*"/g);
      expect(userFacingPermKey, `${sa}: 사용자 응답에 권한 키 평문 노출 금지`).toBeNull();
    }
  });
});

/**
 * data-analyst show_chart 도구 보유 회귀 가드.
 *
 * data-analyst 본문(Phase 5)은 인라인 차트 시각화에 show_chart 사용을 지시한다.
 * 그런데 frontmatter tools 화이트리스트에 show_chart 가 빠져 있으면 에이전트가
 * 물리적으로 show_chart 를 호출하지 못하고 create_chart(저장만, 렌더링 없음)로
 * 폴백하여 사용자에게 차트가 표시되지 않는다. tools 목록과 본문 지시의 정합성을 고정한다.
 */
describe('data-analyst show_chart 도구 보유 (인라인 차트 렌더링)', () => {
  const agentMd = fs.readFileSync(path.join(__dirname, 'data-analyst/agent.md'), 'utf-8');
  // frontmatter 영역(첫 --- ~ 두 번째 ---)만 추출하여 tools 화이트리스트 검사
  const frontmatter = agentMd.match(/^---\n([\s\S]*?)\n---/)?.[1] ?? '';

  it('frontmatter tools 에 show_chart 가 포함된다 (본문 지시와 정합)', () => {
    expect(frontmatter).toContain('mcp__firehub__show_chart');
  });

  it('본문이 show_chart 를 인라인 시각화 기본값으로 명시한다', () => {
    expect(agentMd).toMatch(/show_chart[\s\S]*?(기본값|채팅에 직접 렌더링)/);
  });

  it('create_chart 가 단독으로 렌더링되지 않음(저장 전용)을 경고한다', () => {
    expect(agentMd).toMatch(/create_chart[\s\S]*?(렌더링하지 않|저장만|저장 전용)/);
  });
});

/**
 * data-analyst Phase 2 사전 조건 회귀 가드 (refs #267).
 *
 * SQL 분석 직전 list_datasets → get_data_schema(datasetIds) 의무 충족이
 * rules.md §1.5 / examples.md 의 잘못된 패턴 섹션·신규 예시 4 (SQL 에러 자체 정정) 로
 * 명문화되어 있어야 한다. 텍스트가 사라지면 retry loop 회귀 위험이 다시 열린다.
 */
describe('data-analyst Phase 2 사전 조건 (refs #267)', () => {
  const rules = fs.readFileSync(path.join(__dirname, 'data-analyst/rules.md'), 'utf-8');
  const examples = fs.readFileSync(path.join(__dirname, 'data-analyst/examples.md'), 'utf-8');

  it('rules.md 에 §1.5 Phase 2 사전 조건 섹션이 존재한다', () => {
    expect(rules).toContain('## 1.5. Phase 2 사전 조건');
    // 사전 조건 본문에 datasetIds 키워드 (Phase 1 → Phase 2 게이팅 핵심)
    expect(rules).toContain('datasetIds');
  });

  it('rules.md SQLState 표에 42703 / 42P01 / 42601 분기가 포함된다', () => {
    expect(rules).toContain('42703');
    expect(rules).toContain('42P01');
    expect(rules).toContain('42601');
  });

  it('examples.md 잘못된 패턴 섹션이 존재한다 (InputValidationError 케이스)', () => {
    expect(examples).toContain('## ❌ 잘못된 패턴');
    // SDK Zod 차단 케이스 — 빈 호출 회귀 가드
    expect(examples).toContain('InputValidationError');
  });

  it('examples.md 신규 예시 4 SQL 에러 자체 정정 헤더가 존재한다', () => {
    expect(examples).toContain('예시 4: SQL 에러 자체 정정');
    // 에러 자체 정정 예시 본문에 SQLState 42703 (UNDEFINED_COLUMN) 케이스 포함
    expect(examples).toContain('SQLState: 42703');
  });

  it('examples.md 섹션 순서: 예시 4 → 잘못된 패턴 → 예시 5 보존', () => {
    const idx4 = examples.indexOf('## 예시 4: SQL 에러 자체 정정');
    const idxBad = examples.indexOf('## ❌ 잘못된 패턴');
    const idx5 = examples.indexOf('## 예시 5:');
    expect(idx4).toBeGreaterThan(0);
    expect(idxBad).toBeGreaterThan(idx4);
    expect(idx5).toBeGreaterThan(idxBad);
  });
});
