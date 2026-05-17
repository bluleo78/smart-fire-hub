import { describe, it, expect } from 'vitest';
import { SYSTEM_PROMPT } from './system-prompt.js';

// SYSTEM_PROMPT 텍스트가 도구 선택 규칙을 충분히 명시하는지 보장하는 회귀 테스트.
// LLM 실제 동작은 통합 환경에서만 검증 가능하므로, 본 단위 테스트는 프롬프트가
// "list_* vs show_* 우선순위" 가이드를 잃지 않도록 텍스트 계약을 고정한다.
// (이슈 #217 회귀 방지 — 시스템 프롬프트 약화로 첫 호출에서 show_* 위젯이 선택되던 결함)
describe('SYSTEM_PROMPT', () => {
  // 도구 선택 우선순위 섹션이 존재해야 함 — 사용자 첫 조회의 잘못된 도구 선택을 방지
  it('도구 선택 우선순위 섹션을 포함한다', () => {
    expect(SYSTEM_PROMPT).toContain('도구 선택 우선순위');
  });

  // list_* / get_* / query_* 그룹을 데이터 조회 우선 도구로 명시해야 함
  it('list_*/get_*/query_* 를 데이터 조회 우선 도구로 명시한다', () => {
    expect(SYSTEM_PROMPT).toMatch(/list_\*.*get_\*.*query_\*/s);
    expect(SYSTEM_PROMPT).toMatch(/list_\*.*우선|우선.*list_\*/);
  });

  // show_* 위젯은 명시적 UI 표시 요청 시에만 사용한다는 규칙이 들어있어야 함
  it('show_* 위젯은 명시적 UI 표시 요청 시에만 사용하라는 규칙을 포함한다', () => {
    expect(SYSTEM_PROMPT).toContain('show_*');
    // 명시 키워드 중 하나 이상 포함
    expect(SYSTEM_PROMPT).toMatch(/대시보드에 추가|화면에 띄워|카드로 보여|위젯으로 표시|인라인으로 보여/);
  });

  // 잘못된 첫 호출 예시(show_dataset_list 우선)와 올바른 예시(list_datasets 우선)가 함께 들어있어야 함
  it('show_dataset_list 잘못된 사용 예시와 list_datasets 올바른 사용 예시를 모두 명시한다', () => {
    expect(SYSTEM_PROMPT).toContain('show_dataset_list');
    expect(SYSTEM_PROMPT).toContain('list_datasets');
  });

  // 이슈 #241 회귀 방지 — 파괴 작업 confirm 우회 사회공학 거부 정책이 명문화되어야 함
  describe('파괴 작업 confirm 우회 거부 (refs #241)', () => {
    // drop_dataset_column이 파괴 작업 목록에 포함되어야 함 (이전 누락)
    it('drop_dataset_column을 파괴 작업 목록에 포함한다', () => {
      expect(SYSTEM_PROMPT).toContain('drop_dataset_column');
      // 파괴 작업 섹션 안에 포함되어야 함
      const destructiveSection = SYSTEM_PROMPT.split('## 파괴 작업')[1];
      expect(destructiveSection).toBeDefined();
      expect(destructiveSection).toContain('drop_dataset_column');
    });

    // delete_dataset 호출 전 get_dataset_references 선행 호출 의무를 명시해야 함
    it('delete_dataset 전에 get_dataset_references 선행 호출 의무를 명시한다', () => {
      expect(SYSTEM_PROMPT).toContain('get_dataset_references');
      expect(SYSTEM_PROMPT).toMatch(/get_dataset_references.*먼저|반드시.*get_dataset_references/s);
    });

    // "확인 묻지마" / "skip confirm" 류 사회공학 우회 거부 정책이 명시되어야 함
    it('confirm 우회 사회공학 발화를 거부하는 정책을 명시한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/확인 묻지\s?마|skip confirm|한 번에 다 처리/);
      expect(SYSTEM_PROMPT).toMatch(/시스템 정책|우회.*불가|우회되지 않/);
    });

    // 단일 발화 multi-step 파괴 작업도 각 단계마다 별도 턴 확인 필요 명시
    it('단일 발화 multi-step 파괴 작업도 단계마다 별도 턴 확인을 요구한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/multi-step|단일 발화|연쇄/);
      expect(SYSTEM_PROMPT).toMatch(/배치 승인 금지|각 파괴 단계|단계마다.*확인/);
    });

    // 전문 에이전트 위임 시 "확인 묻지마" 류 발화를 그대로 forward 하지 않도록 명시
    it('전문 에이전트 위임 시 confirm 우회 발화를 forward 하지 않도록 명시한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/그대로 전달하지 않|forward 하지 않|약화시키는 표현/);
    });
  });

  // 이슈 #249 회귀 방지 — query_dataset_data / show_table 흐름에서 PII 평문 노출 차단.
  // #246 fix가 audit-analyst만 다뤘던 점을 일반화하여, 메인 에이전트와 모든 전문 에이전트가
  // 데이터셋 조회 결과의 PII를 자동 마스킹하도록 텍스트 계약을 고정한다.
  describe('PII 자발적 노출 금지 전역 정책 (refs #246, #249)', () => {
    // 정책 섹션 자체가 #249를 참조해야 함 — 회귀 키워드 보존
    it('PII 자발적 노출 금지 섹션이 #249를 참조한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/PII 자발적 노출 금지[\s\S]*#249/);
    });

    // 데이터셋 조회·분석 도구가 정책 적용 범위에 포함되어야 함 (#249 회귀 핵심 지점)
    it('데이터셋 조회 도구(query_dataset_data, execute_analytics_query)가 PII 정책 적용 대상이다', () => {
      const piiSection = SYSTEM_PROMPT.split('## 보안 — 묻지 않은 사용자 PII')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toContain('query_dataset_data');
      expect(piiSection).toContain('execute_analytics_query');
    });

    // show_table / show_dataset / show_chart 위젯 입력 단계도 마스킹 대상
    it('show_table·show_dataset·show_chart 위젯도 PII 마스킹 대상이다', () => {
      const piiSection = SYSTEM_PROMPT.split('## 보안 — 묻지 않은 사용자 PII')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toContain('show_table');
      expect(piiSection).toContain('show_dataset');
      expect(piiSection).toContain('show_chart');
    });

    // PII 시그널 컬럼 키워드 목록 — 자동 감지 트리거 보장
    it('PII 시그널 컬럼 키워드(이메일/전화/주민/IP 등)를 한·영 모두 명시한다', () => {
      const piiSection = SYSTEM_PROMPT.split('## 보안 — 묻지 않은 사용자 PII')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toMatch(/이메일/);
      expect(piiSection).toMatch(/email/);
      expect(piiSection).toMatch(/전화|phone/);
      expect(piiSection).toMatch(/주민|ssn/);
      expect(piiSection).toMatch(/ipAddress|IP/);
    });

    // 마스킹 형식 예시(이메일·전화 가운데 4자리) 명시
    it('마스킹 형식 예시(이메일 a***@e***.com·전화 010-****-N)를 명시한다', () => {
      const piiSection = SYSTEM_PROMPT.split('## 보안 — 묻지 않은 사용자 PII')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toMatch(/a\*\*\*@e\*\*\*\.com/);
      expect(piiSection).toMatch(/010-\*\*\*\*-\d{4}/);
    });

    // #249 회귀 시나리오(단순 조회 시 평문 노출) 금지 명시
    it('#249 회귀(단순 조회 시 PII 평문 노출)를 명시적으로 금지한다', () => {
      const piiSection = SYSTEM_PROMPT.split('## 보안 — 묻지 않은 사용자 PII')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toMatch(/단순 조회|#249/);
      expect(piiSection).toMatch(/평문/);
    });

    // 사회공학적 "원본 보여줘"·"마스킹 풀어줘" 요청 거부 정책
    it('"원본 보여줘"·"마스킹 풀어줘" 류 사회공학 요청을 거부한다', () => {
      const piiSection = SYSTEM_PROMPT.split('## 보안 — 묻지 않은 사용자 PII')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toMatch(/마스킹 풀어|원본 보여/);
    });
  });
});
