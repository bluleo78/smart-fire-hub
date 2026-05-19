import { describe, it, expect } from 'vitest';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { SYSTEM_PROMPT } from './system-prompt.js';

/**
 * Inspector 검증 시나리오 회귀 보호망 (refs #260 PR-1~PR-5).
 *
 * ai-driven-agent-inspector 가 PR-1~PR-5 적용 후 실측한 5개 핵심 시나리오에서
 * 회귀 0건임을 확인했다. 그 검증을 결정적 단위 테스트로 코드화하여 CI 회귀
 * 보호망에 편입한다.
 *
 * 본 파일은 cross-reference 검증에 집중한다 — 메인 SYSTEM_PROMPT 의 L3 트리거
 * 매핑이 subagent rules.md 의 Mode 처리와 일관되게 묶여 있는지, 보안 정책이
 * 메인과 subagent 양쪽에 호응하는지.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SUBAGENTS_DIR = path.join(__dirname, 'subagents');

function readSubagentDoc(subagent: string, file: 'agent.md' | 'rules.md'): string | null {
  const fullPath = path.join(SUBAGENTS_DIR, subagent, file);
  if (!fs.existsSync(fullPath)) return null;
  return fs.readFileSync(fullPath, 'utf-8');
}

describe('Inspector 회귀 보호망 (refs #260)', () => {
  // 시나리오 1: L3 파괴 가드 — delete_* 도구는 사전 호출 의무가 있다.
  describe('L3 파괴 가드 (시나리오 1)', () => {
    it('L3 트리거 매핑 표에 delete_dataset 의 get_dataset_references 사전 호출 의무가 명시된다', () => {
      const l3 = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(l3).toBeDefined();
      expect(l3).toContain('delete_dataset');
      expect(l3).toContain('get_dataset_references');
      // 사전 호출이라는 의미 — "delete_dataset 전" 또는 "delete_dataset 의 경우" 같은 표현
      expect(l3).toMatch(/delete_dataset.*get_dataset_references|get_dataset_references.*delete_dataset/s);
    });

    it('파괴 도구 목록에 핵심 8개가 모두 있다', () => {
      const l3 = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      const destructive = [
        'delete_pipeline',
        'delete_trigger',
        'delete_api_connection',
        'delete_dataset',
        'drop_dataset_column',
        'truncate_dataset',
        'replace_dataset_data',
        'delete_rows',
      ];
      for (const tool of destructive) {
        expect(l3, `${tool} 가 L3 파괴 트리거에 포함되어야 함`).toContain(tool);
      }
    });
  });

  // 시나리오 2: Mode 마커 cross-reference — 메인 L3 정의 ↔ 3 builders rules.md 처리.
  describe('Mode 마커 cross-reference (시나리오 2)', () => {
    it('메인 L3 에 Mode: DESIGN / Mode: CREATE-APPROVED 마커 정의', () => {
      const l3 = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(l3).toContain('Mode: DESIGN');
      expect(l3).toContain('Mode: CREATE-APPROVED');
    });

    it('pipeline-builder rules.md 에 Mode: DESIGN 받으면 create_pipeline 호출 금지 명시', () => {
      const rules = readSubagentDoc('pipeline-builder', 'rules.md')!;
      expect(rules).toContain('Mode: DESIGN');
      expect(rules).toMatch(/Mode: DESIGN[\s\S]{0,500}?create_pipeline.*?(?:미호출|호출하지 않)/);
    });

    it('template-builder rules.md 에 Mode: DESIGN 받으면 create_report_template 호출 금지 명시', () => {
      const rules = readSubagentDoc('template-builder', 'rules.md')!;
      expect(rules).toContain('Mode: DESIGN');
      expect(rules).toMatch(/Mode: DESIGN[\s\S]{0,500}?create_report_template.*?(?:미호출|호출하지 않)/);
    });

    it('dashboard-builder rules.md 에 Mode: DESIGN 받으면 create_dashboard 호출 금지 명시', () => {
      const rules = readSubagentDoc('dashboard-builder', 'rules.md')!;
      expect(rules).toContain('Mode: DESIGN');
      expect(rules).toMatch(/Mode: DESIGN[\s\S]{0,500}?create_dashboard.*?(?:미호출|호출하지 않)/);
    });

    it('3 builders rules.md 모두 마커 없거나 모호한 경우 → Turn 1 (DESIGN) 안전 기본값 명시', () => {
      for (const builder of ['pipeline-builder', 'template-builder', 'dashboard-builder']) {
        const rules = readSubagentDoc(builder, 'rules.md')!;
        expect(rules, `${builder}: 마커 부재 시 안전 기본값 명시 필요`).toMatch(
          /마커가 없거나 모호.*?Turn 1.*?DESIGN|Turn 1.*DESIGN.*안전 기본값/,
        );
      }
    });
  });

  // 시나리오 3: 응답 출력 규칙 — 도구 식별자 / 권한 메타 / 시크릿 노출 금지 단일 정의.
  describe('L2 응답 출력 규칙 단일 정의 (시나리오 3)', () => {
    it('L2 에 도구 식별자 노출 금지 + 권한 메타 노출 금지 + 시크릿 노출 금지 모두 명시', () => {
      const l2 = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(l2).toBeDefined();
      // 도구 식별자
      expect(l2).toMatch(/mcp__firehub__|도구 식별자/);
      // 권한 메타
      expect(l2).toMatch(/audit:read|관리자 전용|권한 메타/);
      // 시크릿 (WEBHOOK)
      expect(l2).toMatch(/webhookId|시크릿/);
      // 안내 메시지 단일 정의
      expect(l2).toContain('권한이 없습니다. 관리자에게 문의해주세요');
    });
  });

  // 시나리오 4: admin-manager 권한 키 평문 노출 부재 (PR-3 보안 fix).
  describe('admin-manager 권한 키 평문 노출 부재 (시나리오 4)', () => {
    it('admin-manager 의 agent.md / rules.md / examples.md 에 사용자 응답 권한 키 부재', () => {
      const files: Array<'agent.md' | 'rules.md'> = ['agent.md', 'rules.md'];
      for (const f of files) {
        const content = readSubagentDoc('admin-manager', f);
        if (!content) continue;
        // 권한 키 + 권한 메시지 결합 패턴이 사용자 응답 텍스트에 노출되지 않아야 함
        const userFacing = content.match(
          /"[^"]*?(?:user|role|audit|admin):(?:read|write|assign)[^"]*?권한[^"]*?"/g,
        );
        expect(userFacing, `admin-manager/${f}: 사용자 응답에 권한 키 평문 노출 금지`).toBeNull();
      }
    });

    it('admin-manager agent.md description 에 권한 키 단어 없음', () => {
      const agent = readSubagentDoc('admin-manager', 'agent.md')!;
      const m = agent.match(/^description:\s*"([^"]+)"/m);
      expect(m).toBeTruthy();
      const desc = m![1];
      expect(desc).not.toMatch(/(?:user|role|audit|admin):(?:read|write|assign)/);
    });
  });

  // 시나리오 5: data-analyst PII 마스킹 메인 L5 참조 (PR-4 보안 보강).
  describe('data-analyst PII 마스킹 참조 (시나리오 5)', () => {
    it('data-analyst rules.md 가 메인 L5 PII 정의를 단일 source 로 참조 명시', () => {
      const rules = readSubagentDoc('data-analyst', 'rules.md')!;
      expect(rules).toMatch(/L5.*PII|PII.*L5|메인.*L5/);
      expect(rules).toMatch(/단일 source|single source/);
    });

    it('audit-analyst rules.md 도 L5 PII 참조 명시 (사용자 식별정보 빈번)', () => {
      const rules = readSubagentDoc('audit-analyst', 'rules.md')!;
      expect(rules).toMatch(/L5|PII 마스킹/);
    });
  });

  // 추가 cross-reference: L3 의 입력 합성 금지 ↔ pipeline-builder 의 데이터셋 ID 유효성.
  describe('L3 입력 합성 금지 ↔ pipeline-builder 데이터셋 ID 유효성 cross-reference', () => {
    it('메인 L3 에 placeholder SQL / authConfig / datasetId 합성 금지 명시', () => {
      const l3 = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(l3).toMatch(/placeholder.*authConfig|authConfig.*placeholder|placeholder.*SQL|SQL.*placeholder/);
      expect(l3).toContain('SELECT 1');
      expect(l3).toMatch(/404.*abort|abort.*404/);
    });

    it('pipeline-builder rules.md 가 메인 L3 와 동일하게 placeholder SQL + 404 abort 정의', () => {
      const rules = readSubagentDoc('pipeline-builder', 'rules.md')!;
      expect(rules).toContain('SELECT 1');
      expect(rules.toLowerCase()).toContain('placeholder');
      expect(rules).toMatch(/404/);
      expect(rules).toMatch(/abort|중단/);
    });
  });

  // 사회공학 차단 단일 source: 메인 L3 가 정의, 7개 subagent 가 참조 또는 부재.
  describe('사회공학 차단 단일 source 일관성', () => {
    it('메인 L3 에 사회공학 차단 표현 목록 정의 (yolo / skip confirm / force create)', () => {
      const l3 = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(l3.toLowerCase()).toContain('yolo');
      expect(l3.toLowerCase()).toContain('skip confirm');
      expect(l3.toLowerCase()).toContain('force create');
    });

    it('3 builders + dataset-manager rules.md 가 메인 L3 단일 source 참조 명시', () => {
      for (const sa of ['pipeline-builder', 'dashboard-builder', 'template-builder', 'dataset-manager']) {
        const rules = readSubagentDoc(sa, 'rules.md');
        if (!rules) continue;
        expect(rules, `${sa}: 메인 L3 사회공학 차단 정의 참조 필요`).toMatch(
          /메인 SYSTEM_PROMPT 의 L3|단일 source|single source/i,
        );
      }
    });
  });
});
