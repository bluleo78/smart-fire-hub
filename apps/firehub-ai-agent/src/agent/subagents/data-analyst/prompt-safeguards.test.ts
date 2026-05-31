import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

/**
 * data-analyst 프롬프트 회귀 가드.
 *
 * 배경: data-analyst가 직원 명단 등 고정 참고 데이터를 SQL VALUES 절에 인라인하여
 * 매 쿼리·재시도마다 동일 페이로드가 tool_use/tool_result에 echo되는 토큰 낭비 패턴이
 * 발견됐다. rules.md에 "10행 이상 참고 데이터는 VALUES 인라인 금지, 데이터셋 JOIN 유도"
 * 규칙이 남아있는지(=프롬프트 수정 시 가드가 실수로 제거되지 않았는지) 정적으로 검증한다.
 */

// ESM에서 __dirname 대체
const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readRules(): string {
  return fs.readFileSync(path.join(__dirname, 'rules.md'), 'utf-8');
}

function readAnalyticsTools(): string {
  // data-analyst/ → subagents → agent → src, 그 후 mcp/tools/analytics-tools.ts
  return fs.readFileSync(
    path.join(__dirname, '..', '..', '..', 'mcp', 'tools', 'analytics-tools.ts'),
    'utf-8',
  );
}

describe('data-analyst prompt safeguards (#273)', () => {
  it('rules.md에 10행 이상 참고 데이터 VALUES 인라인 금지 규칙이 있어야 한다', () => {
    const rules = readRules();
    expect(rules).toContain('참고 데이터');
    expect(rules).toContain('VALUES');
    expect(rules).toContain('인라인');
    expect(rules).toMatch(/10\s*행\s*이상/);
  });

  it('rules.md가 데이터셋 JOIN 유도 + 생성은 dataset-manager 안내를 명시해야 한다', () => {
    const rules = readRules();
    expect(rules).toMatch(/JOIN/);
    expect(rules).toContain('dataset-manager');
    expect(rules).toMatch(/권장|안내/);
  });

  it('rules.md에 소량(10행 미만) 일회성 인라인 허용 예외가 있어야 한다', () => {
    const rules = readRules();
    expect(rules).toMatch(/10\s*행\s*미만/);
    expect(rules).toContain('허용');
  });

  it('execute_analytics_query description에 참고 데이터 JOIN 가이드가 있어야 한다', () => {
    const src = readAnalyticsTools();
    // tool description은 모델 프롬프트에 노출됨 — 동일 임계값(10행) + 데이터셋 JOIN 유도
    expect(src).toMatch(/10행 이상의 고정 참고 데이터/);
    expect(src).toMatch(/데이터셋으로 만들어 JOIN/);
  });
});
