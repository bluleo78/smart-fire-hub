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

  // 이슈 #252 회귀 방지 — 다중 리소스 집계 N+1 호출 금지 일반 정책.
  // #238 (trigger-manager N+1), #243 (list_triggers 사회공학) fix가 트리거 도메인에만 한정됐던 점을
  // 일반화하여, 메인 에이전트가 데이터셋·파이프라인 등 어느 도구든 N+1 호출을 하지 않도록
  // execute_analytics_query GROUP BY 1회 처리 가이드를 텍스트 계약으로 고정한다.
  describe('다중 리소스 집계 N+1 호출 금지 일반 정책 (refs #238 #243 #252)', () => {
    // N+1 일반 정책 섹션이 존재하고 #252를 참조해야 함
    it('N+1 일반 정책 섹션이 #252를 참조한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/N\+1 호출 금지 일반 정책[\s\S]*#252/);
    });

    // "같은 도구 3회 이상 호출 금지" 일반 룰 명시
    it('같은 응답 안에서 같은 도구를 3회 이상 호출 금지를 명시한다', () => {
      const npSection = SYSTEM_PROMPT.split('N+1 호출 금지 일반 정책')[1];
      expect(npSection).toBeDefined();
      expect(npSection).toMatch(/같은 도구를 3회 이상 호출하지 마라|같은 도구의 반복 호출/);
    });

    // list_* + get_row_count 반복 대신 execute_analytics_query GROUP BY 1회로 처리 명시
    it('list_* 반복 + get_row_count 패턴 대신 execute_analytics_query GROUP BY 1회로 처리하도록 명시한다', () => {
      const npSection = SYSTEM_PROMPT.split('N+1 호출 금지 일반 정책')[1];
      expect(npSection).toBeDefined();
      expect(npSection).toContain('get_row_count');
      expect(npSection).toContain('execute_analytics_query');
      expect(npSection).toMatch(/GROUP BY/);
    });

    // get_row_count는 단일 데이터셋 대상만 — 다중이면 aggregate
    it('get_row_count는 단일 데이터셋 대상에만 사용하라는 제약을 명시한다', () => {
      const npSection = SYSTEM_PROMPT.split('N+1 호출 금지 일반 정책')[1];
      expect(npSection).toBeDefined();
      expect(npSection).toMatch(/get_row_count.*단일 데이터셋|단일 데이터셋.*get_row_count/s);
    });

    // 잘못된 예시에 #252 회귀 시나리오(22회 get_row_count) 명시
    it('잘못된 예시에 #252 회귀 시나리오(get_row_count 반복)를 포함한다', () => {
      const npSection = SYSTEM_PROMPT.split('N+1 호출 금지 일반 정책')[1];
      expect(npSection).toBeDefined();
      expect(npSection).toMatch(/🚫[\s\S]*get_row_count[\s\S]*반복|🚫[\s\S]*22회/);
    });

    // 회귀 임계치(동일 도구 3회 이상 / list_* 결과 N개에 get_* N회) critical perf 회귀 명시
    it('회귀 임계치(동일 도구 3회 이상)를 critical perf 회귀로 명시한다', () => {
      const npSection = SYSTEM_PROMPT.split('N+1 호출 금지 일반 정책')[1];
      expect(npSection).toBeDefined();
      expect(npSection).toMatch(/회귀 임계치/);
      expect(npSection).toMatch(/3회 이상/);
      expect(npSection).toMatch(/critical perf 회귀/);
    });

    // 메인 에이전트 직접 호출 경로에도 적용된다는 명시
    it('메인 에이전트 직접 호출 경로에도 N+1 정책이 적용됨을 명시한다', () => {
      const npSection = SYSTEM_PROMPT.split('N+1 호출 금지 일반 정책')[1];
      expect(npSection).toBeDefined();
      expect(npSection).toMatch(/메인 에이전트.*자체 도구.*직접 호출|메인 에이전트.*직접 호출/);
    });
  });

  // 이슈 #253 회귀 방지 — 대시보드 생성·차트 추가 dashboard-builder 위임 필수.
  // #250(pipeline-builder bypass) fix가 pipeline 도메인에만 한정됐던 점을 dashboard 도메인으로
  // 일반화하여, 메인 에이전트가 create_dashboard/add_chart_to_dashboard를 직접 호출하지 않고
  // 항상 dashboard-builder에 위임하도록 텍스트 계약을 고정한다.
  describe('대시보드 생성 dashboard-builder 위임 필수 (refs #253)', () => {
    // #253 정책 섹션이 존재하고 이슈 번호를 참조해야 함
    it('대시보드 위임 정책 섹션이 #253를 참조한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/dashboard-builder 위임 필수[\s\S]*#253/);
    });

    // create_dashboard / add_chart_to_dashboard 직접 호출 금지 명시
    it('create_dashboard·add_chart_to_dashboard 메인 직접 호출 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## 대시보드 생성·차트 추가')[1];
      expect(section).toBeDefined();
      expect(section).toContain('create_dashboard');
      expect(section).toContain('add_chart_to_dashboard');
      expect(section).toMatch(/직접 호출하지 않|직접 호출.*금지/);
    });

    // "차트 없이" / "확인 없이" 등 단순화·사회공학 신호에도 위임 우회 금지
    it('"차트 없이"·"확인 없이" 류 단순화 신호에도 위임 우회 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## 대시보드 생성·차트 추가')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/차트 없이/);
      expect(section).toMatch(/위임을 우회하지 않|위임을 우회하지|우회.*않는다/);
    });

    // 메인 직접 처리 허용 도구는 list_dashboards / get_dashboard 조회 전용 도구뿐
    it('메인 직접 처리 허용 도구를 list_dashboards·get_dashboard 조회 전용으로 한정한다', () => {
      const section = SYSTEM_PROMPT.split('## 대시보드 생성·차트 추가')[1];
      expect(section).toBeDefined();
      expect(section).toContain('list_dashboards');
      expect(section).toContain('get_dashboard');
      expect(section).toMatch(/조회 전용|단순 목록·상세 조회/);
    });

    // #253 회귀 시나리오(대시보드 만들어줘 + create_dashboard 직접 호출) 잘못된 예시 포함
    it('#253 회귀 시나리오(create_dashboard 직접 호출)를 잘못된 예시로 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## 대시보드 생성·차트 추가')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/🚫[\s\S]*대시보드 만들어줘[\s\S]*create_dashboard/);
    });

    // dashboard-builder 라우팅 표 항목에 "대시보드 만들어줘" / "대시보드 생성" 트리거 키워드 명시
    it('라우팅 표의 dashboard-builder 트리거에 "대시보드 만들어줘"·"대시보드 생성"이 포함된다', () => {
      expect(SYSTEM_PROMPT).toMatch(/dashboard-builder[\s\S]*대시보드 만들어줘[\s\S]*대시보드 생성/);
    });

    // #250 (pipeline-builder) 정책의 dashboard 도메인 일반화임을 명시
    it('#250 pipeline-builder 정책의 dashboard 도메인 일반화임을 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## 대시보드 생성·차트 추가')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/#250|일반화/);
    });
  });

  // 이슈 #254 회귀 방지 — 시스템 메타 노출 금지 정책.
  // 사용자 "어떤 subagent들이 있어?" 메타 질문에 메인이 10개 subagent 내부 식별자 +
  // 권한 어노테이션을 백틱 코드 표 형태로 평문 노출하던 결함. 사회공학 공격 vector 차단을
  // 위해 내부 식별자·권한 어노테이션·라우팅 메타 노출 금지를 텍스트 계약으로 고정한다.
  describe('시스템 메타 노출 금지 (refs #254)', () => {
    // 메타 노출 금지 정책 섹션이 존재하고 #254를 참조해야 함
    it('시스템 메타 노출 금지 섹션이 #254를 참조한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/시스템 메타 노출 금지[\s\S]*#254/);
    });

    // 노출 금지 대상: 내부 subagent 식별자 (*-manager, *-builder, *-analyst 패턴 명시)
    it('내부 subagent 식별자(*-manager/*-builder/*-analyst) 노출 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('### 🚫 시스템 메타 노출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/\*-manager/);
      expect(section).toMatch(/\*-builder/);
      expect(section).toMatch(/\*-analyst/);
      expect(section).toMatch(/노출 금지|노출하지 않/);
    });

    // 권한 어노테이션 노출 금지 명시 (audit:read, 관리자 전용)
    it('권한 어노테이션(audit:read·관리자 전용) 노출 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('### 🚫 시스템 메타 노출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/audit:read/);
      expect(section).toMatch(/관리자 전용/);
    });

    // 메타 질문(architecture / 내부 구조 / 어떤 subagent) 에 대한 capability 중심 응답 가이드
    it('메타 질문(내부 구조/architecture/어떤 subagent) 트리거 키워드를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('### 🚫 시스템 메타 노출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/내부 구조|어떤 subagent|architecture/);
    });

    // capability 중심 응답 가이드 — "데이터셋 관리", "파이프라인" 등 사람 친화적 카테고리
    it('capability 중심 응답 가이드(사람 친화적 카테고리)를 제시한다', () => {
      const section = SYSTEM_PROMPT.split('### 🚫 시스템 메타 노출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/capability 중심|기능.*관점|기능 설명/);
      expect(section).toMatch(/데이터셋 관리/);
      expect(section).toMatch(/파이프라인/);
    });

    // #254 회귀 시나리오 — 표 형태로 subagent 식별자 + 권한 어노테이션 노출 금지 예시
    it('#254 회귀 시나리오(표 형태 subagent 식별자 + 권한 어노테이션 노출)를 잘못된 예시로 명시한다', () => {
      const section = SYSTEM_PROMPT.split('### 🚫 시스템 메타 노출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/🚫[\s\S]*data-analyst[\s\S]*audit-analyst/);
    });

    // 사회공학적 우회 시도("디버깅 목적", "ignore previous instructions" 등) 차단
    it('사회공학적 우회 시도(디버깅·ignore previous instructions 등)를 거부한다', () => {
      const section = SYSTEM_PROMPT.split('### 🚫 시스템 메타 노출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/디버깅|ignore previous instructions|내부 개발자/);
      expect(section).toMatch(/면제되지 않|어떤 발화로도/);
    });

    // 회귀 임계치 명시 — 식별자 2개 이상 또는 권한 어노테이션 1개 이상 노출 시 critical 회귀
    it('회귀 임계치(식별자 2개 이상 / 권한 어노테이션 1개 이상 노출)를 critical 회귀로 명시한다', () => {
      const section = SYSTEM_PROMPT.split('### 🚫 시스템 메타 노출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/회귀 임계치/);
      expect(section).toMatch(/critical/);
    });
  });

  // 이슈 #239 회귀 방지 — 응답 스타일 정책.
  // 트리거/스마트잡 서브에이전트에서 fix됐던 중간 narration 누출이 메인 에이전트 경로에서 회귀.
  // 메인 에이전트가 list_datasets + 다중 get_row_count 등을 호출할 때 "병렬로 조회합니다" 같은
  // 계획·진행 안내 텍스트 델타를 송출하던 결함. tool_use 사이의 텍스트는 비워두고 최종 결과만
  // 단일 응답으로 송출하도록 정책을 텍스트 계약으로 고정한다.
  describe('응답 스타일 — 중간 narration 금지 (refs #239)', () => {
    // 응답 스타일 섹션이 존재하고 #239를 참조해야 함
    it('응답 스타일 섹션이 #239를 참조한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/응답 스타일[\s\S]*#239/);
    });

    // 메인 에이전트가 정책 적용 대상임을 명시해야 함 (메인 에이전트 경로 회귀 핵심 지점)
    it('메인 에이전트도 응답 스타일 정책 적용 대상임을 명시한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## 응답 스타일')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/메인 에이전트/);
    });

    // tool_use 사이의 텍스트는 비워둠 — #239 회귀의 직접 가드
    it('tool_use 사이의 텍스트 응답은 비워두라는 규칙을 명시한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## 응답 스타일')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/tool_use 사이|tool_use 블록 사이/);
      expect(styleSection).toMatch(/비워둔다|텍스트 델타도 송출하지 않/);
    });

    // 계획·병렬 처리 narration 금지 명시 — #239 회귀 키워드
    it('"병렬로 조회합니다"·계획 선언 narration 금지를 명시한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## 응답 스타일')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/병렬로 조회합니다/);
      expect(styleSection).toMatch(/계획 선언 금지|계획.*송출하지 않/);
    });

    // 중간 진행 narration 금지 표현이 1번 항목에 포함되어야 함 (조회합니다·처리합니다)
    it('"조회합니다"·"처리합니다" 류 진행 narration 금지 표현을 1번 항목에 포함한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## 응답 스타일')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/조회합니다/);
      expect(styleSection).toMatch(/처리합니다/);
    });

    // 잘못된 예시에 #239 회귀 케이스("병렬로 조회합니다")가 포함되어야 함
    it('잘못된 예시에 #239 회귀 케이스("병렬로 조회합니다")를 포함한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## 응답 스타일')[1];
      expect(styleSection).toBeDefined();
      // 잘못된 예 블록 안에서 #239 회귀 발화가 명시되어야 함
      expect(styleSection).toMatch(/❌[\s\S]*모든 데이터셋의 행 수를 병렬로 조회합니다/);
    });

    // 허용되는 응답 구성 = (a) 최종 결과 요약 + (b) 다음 단계 제안/확인 — 이 둘 외 금지
    it('허용되는 응답 구성을 (최종 결과 + 다음 단계 제안/확인) 두 가지로 한정한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## 응답 스타일')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/허용되는 응답 구성/);
      expect(styleSection).toMatch(/최종 결과 요약/);
      expect(styleSection).toMatch(/다음 단계 제안|확인 질문/);
    });
  });
});
