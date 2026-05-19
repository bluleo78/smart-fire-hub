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
