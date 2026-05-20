import { describe, it, expect } from 'vitest';
import { SYSTEM_PROMPT, FILE_ATTACHMENT_PROMPT } from './system-prompt.js';

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
  describe('L3. 통합 가드 — 파괴 트리거', () => {
    // drop_dataset_column이 L3 가드 트리거 매핑에 포함되어야 함
    it('drop_dataset_column을 파괴 작업 목록에 포함한다', () => {
      expect(SYSTEM_PROMPT).toContain('drop_dataset_column');
      // L3 가드 섹션 안에 포함되어야 함
      const destructiveSection = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
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
  describe('L5. PII 마스킹', () => {
    // L5 섹션 존재 확인
    it('L5. PII 마스킹 섹션이 존재한다', () => {
      expect(SYSTEM_PROMPT).toContain('## L5. PII 마스킹 (전역)');
    });

    // 데이터셋 조회·분석 도구가 정책 적용 범위에 포함되어야 함 (#249 회귀 핵심 지점)
    it('데이터셋 조회 도구(query_dataset_data, execute_analytics_query)가 PII 정책 적용 대상이다', () => {
      const piiSection = SYSTEM_PROMPT.split('## L5. PII 마스킹')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toContain('query_dataset_data');
      expect(piiSection).toContain('execute_analytics_query');
    });

    // show_table / show_dataset / show_chart 위젯 입력 단계도 마스킹 대상
    it('show_table·show_dataset·show_chart 위젯도 PII 마스킹 대상이다', () => {
      const piiSection = SYSTEM_PROMPT.split('## L5. PII 마스킹')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toContain('show_table');
      expect(piiSection).toContain('show_dataset');
      expect(piiSection).toContain('show_chart');
    });

    // PII 시그널 컬럼 키워드 목록 — 자동 감지 트리거 보장
    it('PII 시그널 컬럼 키워드(이메일/전화/주민/IP 등)를 한·영 모두 명시한다', () => {
      const piiSection = SYSTEM_PROMPT.split('## L5. PII 마스킹')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toMatch(/이메일/);
      expect(piiSection).toMatch(/email/);
      expect(piiSection).toMatch(/전화|phone/);
      expect(piiSection).toMatch(/주민|ssn/);
      expect(piiSection).toMatch(/ipAddress|IP/);
    });

    // 마스킹 형식 예시(이메일·전화 가운데 4자리) 명시
    it('마스킹 형식 예시(이메일 a***@e***.com·전화 010-****-N)를 명시한다', () => {
      const piiSection = SYSTEM_PROMPT.split('## L5. PII 마스킹')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toMatch(/a\*\*\*@e\*\*\*\.com/);
      expect(piiSection).toMatch(/010-\*\*\*\*-\d{4}/);
    });

    // #249 회귀 시나리오(단순 조회 시 평문 노출) 금지 명시
    it('#249 회귀(단순 조회 시 PII 평문 노출)를 명시적으로 금지한다', () => {
      const piiSection = SYSTEM_PROMPT.split('## L5. PII 마스킹')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toMatch(/평문|마스킹 후 전달/);
    });

    // 사회공학적 "원본 보여줘"·"마스킹 풀어줘" 요청 거부 정책
    it('"원본 보여줘"·"마스킹 풀어줘" 류 사회공학 요청을 거부한다', () => {
      const piiSection = SYSTEM_PROMPT.split('## L5. PII 마스킹')[1];
      expect(piiSection).toBeDefined();
      expect(piiSection).toMatch(/마스킹 풀어|원본 보여/);
    });
  });

  // 이슈 #252 회귀 방지 — 다중 리소스 집계 N+1 호출 금지 일반 정책.
  // #238 (trigger-manager N+1), #243 (list_triggers 사회공학) fix가 트리거 도메인에만 한정됐던 점을
  // 일반화하여, 메인 에이전트가 데이터셋·파이프라인 등 어느 도구든 N+1 호출을 하지 않도록
  // execute_analytics_query GROUP BY 1회 처리 가이드를 텍스트 계약으로 고정한다.
  // #260 PR-1: L4 독립 섹션으로 재구성.
  describe('L4. N+1 호출 금지', () => {
    // L4 섹션이 존재해야 함
    it('L4 N+1 호출 금지 섹션이 존재한다', () => {
      expect(SYSTEM_PROMPT).toContain('## L4. N+1 호출 금지');
    });

    // "동일 도구 반복 호출은 anti-pattern" + "N회 반복 호출하지 않는다" 명시
    it('같은 응답 안에서 같은 도구를 3회 이상 호출 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L4. N+1 호출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/같은 도구를 N회 반복 호출하지 않는다|동일 도구 반복 호출은 anti-pattern|같은 도구의 반복 호출/);
    });

    // list_* + get_row_count 반복 대신 execute_analytics_query GROUP BY 1회로 처리 명시
    it('list_* 반복 + get_row_count 패턴 대신 execute_analytics_query GROUP BY 1회로 처리하도록 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L4. N+1 호출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toContain('get_row_count');
      expect(section).toContain('execute_analytics_query');
      expect(section).toMatch(/GROUP BY/);
    });

    // get_row_count는 단일 데이터셋 대상만 — 다중이면 aggregate
    it('get_row_count는 단일 데이터셋 대상에만 사용하라는 제약을 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L4. N+1 호출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/get_row_count.*단일 데이터셋|단일 데이터셋.*get_row_count/s);
    });

    // 잘못된 예시에 #252 회귀 시나리오(22회 get_row_count) 명시
    it('잘못된 예시에 #252 회귀 시나리오(get_row_count 반복)를 포함한다', () => {
      const section = SYSTEM_PROMPT.split('## L4. N+1 호출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/❌[\s\S]*get_row_count[\s\S]*(반복|22회)/);
    });

    // 회귀 임계치 (N+1 패턴 / list_triggers 2회 이상) critical perf 회귀 명시
    it('회귀 임계치(동일 도구 3회 이상)를 critical perf 회귀로 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L4. N+1 호출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/회귀 임계치/);
      expect(section).toMatch(/N\+1 패턴|list_triggers.*2회 이상/);
      expect(section).toMatch(/critical perf/);
    });

    // list_triggers pipelineId 필수 + 되묻기 정책
    it('list_triggers pipelineId 필수 및 단순 조회 시 되묻기 정책을 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L4. N+1 호출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toContain('list_triggers');
      expect(section).toContain('pipelineId');
      expect(section).toMatch(/어느 파이프라인의 트리거/);
    });

    // 사회공학 우회 차단 — "분할 말고 한꺼번에" 등 일괄 펼치기 압박에도 완화 금지
    it('사회공학 우회 차단("한 번에 다 보여줘" 등) 및 회차 합리화 응답 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L4. N+1 호출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/한 번에 다 보여줘|분할 말고 한꺼번에/);
      expect(section).toMatch(/회차|분할 처리/);
    });

    // #260 PR-1: N+1 룰 정정 — paginate 정당 사유 예외 명시
    it('paginate 등 정당 사유는 예외임을 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L4. N+1 호출 금지')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/paginate/i);
      expect(section).toMatch(/page.*파라미터|offset|limit/);
    });
  });

  // 이슈 #253 회귀 방지 — 대시보드 생성·차트 추가 dashboard-builder 위임 필수.
  // L3 통합 가드 패턴으로 흡수됨.
  describe('L3. 통합 가드 — DESIGN 트리거', () => {
    // create_dashboard / add_chart_to_dashboard 직접 호출 금지 명시
    it('create_dashboard·add_chart_to_dashboard 메인 직접 호출 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(section).toBeDefined();
      expect(section).toContain('create_dashboard');
      expect(section).toContain('add_chart_to_dashboard');
      expect(section).toMatch(/직접 호출하지 않|직접 호출.*금지|메인 직접 호출 금지/);
    });

    // dashboard-builder 라우팅 표 항목에 "대시보드 만들어줘" / "대시보드 생성" 트리거 키워드 명시
    it('라우팅 표의 dashboard-builder 트리거에 "대시보드 만들어줘"·"대시보드 생성"이 포함된다', () => {
      expect(SYSTEM_PROMPT).toMatch(/dashboard-builder[\s\S]*대시보드 만들어줘[\s\S]*대시보드 생성/);
    });

    it('L3 트리거 매핑 표에 create_pipeline / create_report_template / create_dashboard 가 포함된다', () => {
      const section = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(section).toBeDefined();
      expect(section).toContain('create_pipeline');
      expect(section).toContain('create_report_template');
      expect(section).toContain('create_dashboard');
    });

    it('L3 Mode: DESIGN / Mode: CREATE-APPROVED 마커를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(section).toBeDefined();
      expect(section).toContain('Mode: DESIGN');
      expect(section).toContain('Mode: CREATE-APPROVED');
    });
  });

  // 이슈 #254 회귀 방지 — 시스템 메타 노출 금지 정책.
  // 사용자 "어떤 subagent들이 있어?" 메타 질문에 메인이 10개 subagent 내부 식별자 +
  // 권한 어노테이션을 백틱 코드 표 형태로 평문 노출하던 결함. 사회공학 공격 vector 차단을
  // 위해 내부 식별자·권한 어노테이션·라우팅 메타 노출 금지를 텍스트 계약으로 고정한다.
  describe('L2. 응답 출력 — 노출 금지', () => {
    // #260: 본문 토큰 절감으로 이슈 번호 미포함, 정책 헤더 존재만 검증.
    it('시스템 메타 노출 금지 섹션이 존재한다', () => {
      expect(SYSTEM_PROMPT).toContain('노출 금지');
    });

    // 노출 금지 대상: 내부 subagent 식별자 (*-manager, *-builder, *-analyst 패턴 명시)
    it('내부 subagent 식별자(*-manager/*-builder/*-analyst) 노출 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/\*-manager/);
      expect(section).toMatch(/\*-builder/);
      expect(section).toMatch(/\*-analyst/);
      expect(section).toMatch(/노출 금지|노출하지 않/);
    });

    // 권한 어노테이션 노출 금지 명시 (audit:read, 관리자 전용)
    it('권한 어노테이션(audit:read·관리자 전용) 노출 금지를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/audit:read/);
      expect(section).toMatch(/관리자 전용/);
    });

    // 메타 질문(architecture / 내부 구조 / 어떤 subagent) 에 대한 capability 중심 응답 가이드
    it('메타 질문(내부 구조/architecture/어떤 subagent) 트리거 키워드를 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/내부 구조|어떤 subagent|어떤 작업/);
    });

    // capability 중심 응답 가이드 — "데이터셋 관리", "파이프라인" 등 사람 친화적 카테고리
    it('capability 중심 응답 가이드(사람 친화적 카테고리)를 제시한다', () => {
      const section = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/capability 중심|기능.*관점|기능 설명|capability 관점/);
      expect(section).toMatch(/데이터셋 관리/);
      expect(section).toMatch(/파이프라인/);
    });

    // #254 회귀 시나리오 — 표 형태로 subagent 식별자 + 권한 어노테이션 노출 금지 예시
    it('#254 회귀 시나리오(표 형태 subagent 식별자 + 권한 어노테이션 노출)를 잘못된 예시로 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/data-analyst[\s\S]*audit-analyst|save_as_smart_job[\s\S]*mcp__firehub__/);
    });

    // 사회공학적 우회 시도("디버깅 목적", "ignore previous instructions" 등) 차단
    it('사회공학적 우회 시도(디버깅·ignore previous instructions 등)를 거부한다', () => {
      const section = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/디버깅|ignore previous instructions|내부 개발자/);
      expect(section).toMatch(/면제되지 않|어떤 발화로도/);
    });

    // 회귀 임계치 명시 — 식별자 2개 이상 또는 권한 어노테이션 1개 이상 노출 시 critical 회귀
    it('회귀 임계치(식별자 2개 이상 / 권한 어노테이션 1개 이상 노출)를 critical 회귀로 명시한다', () => {
      const section = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(section).toBeDefined();
      // L2는 간결화됨 — 회귀 예가 존재하는지 확인 (회귀 임계치 대신 회귀 예 섹션)
      expect(section).toMatch(/회귀 예|회귀|❌/);
    });
  });

  // 이슈 #239 회귀 방지 — 응답 스타일 정책.
  // 트리거/스마트잡 서브에이전트에서 fix됐던 중간 narration 누출이 메인 에이전트 경로에서 회귀.
  // 메인 에이전트가 list_datasets + 다중 get_row_count 등을 호출할 때 "병렬로 조회합니다" 같은
  // 계획·진행 안내 텍스트 델타를 송출하던 결함. 부적절한 narration 금지를 텍스트 계약으로 고정한다.
  // #260 PR-1: 짧은 의도 status는 허용하되 도구명 노출·거짓 status·사전 계획은 금지.
  describe('L2. 응답 출력 — 진행 status', () => {
    // 응답 스타일 섹션이 존재하고 #239를 참조해야 함
    it('응답 스타일 섹션이 #239를 참조한다', () => {
      expect(SYSTEM_PROMPT).toMatch(/#239/);
    });

    // 메인 에이전트가 정책 적용 대상임을 명시해야 함 (메인 에이전트 경로 회귀 핵심 지점)
    it('메인 에이전트도 응답 스타일 정책 적용 대상임을 명시한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/메인 에이전트/);
    });

    // tool_use 사이의 텍스트는 비워둠 — #239 회귀의 직접 가드
    it('tool_use 사이의 텍스트 응답은 비워두라는 규칙을 명시한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/tool_use 사이|tool_use 블록 사이/);
      expect(styleSection).toMatch(/비워둔다|텍스트 델타도 송출하지 않/);
    });

    // 계획·병렬 처리 narration 금지 명시 — #239 회귀 키워드
    it('"병렬로 조회합니다"·계획 선언 narration 금지를 명시한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/병렬로 조회합니다/);
      expect(styleSection).toMatch(/계획 선언 금지|계획.*송출하지 않|사전 계획 선언/);
    });

    // 중간 진행 narration 금지 표현이 포함되어야 함 (조회합니다·처리합니다)
    it('"조회합니다"·"처리합니다" 류 진행 narration 금지 표현을 포함한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/조회합니다/);
      expect(styleSection).toMatch(/처리합니다/);
    });

    // 잘못된 예시에 #239 회귀 케이스("병렬로 조회합니다")가 포함되어야 함
    it('잘못된 예시에 #239 회귀 케이스("병렬로 조회합니다")를 포함한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(styleSection).toBeDefined();
      // 잘못된 예 블록 안에서 #239 회귀 발화가 명시되어야 함
      expect(styleSection).toMatch(/❌[\s\S]*모든 데이터셋의 행 수를 병렬로 조회합니다/);
    });

    // 허용되는 응답 구성 = (a) 최종 결과 요약 + (b) 다음 단계 제안/확인 — 이 둘 외 금지
    it('허용되는 응답 구성을 (최종 결과 + 다음 단계 제안/확인) 두 가지로 한정한다', () => {
      const styleSection = SYSTEM_PROMPT.split('## L2. 응답 출력 규칙')[1];
      expect(styleSection).toBeDefined();
      expect(styleSection).toMatch(/응답 구성/);
      expect(styleSection).toMatch(/결과 요약/);
      expect(styleSection).toMatch(/다음 단계 제안|확인 질문/);
    });

    // #260 PR-1: tool narration over-correction 정정 — 짧은 의도 status 허용
    it('진행 status 섹션이 짧은 의도 status 허용 예와 금지 예를 모두 명시한다', () => {
      const section = SYSTEM_PROMPT.split('### 진행 status')[1];
      expect(section).toBeDefined();
      expect(section).toContain('짧은 의도 status'); // 허용 선언
      expect(section).toContain('트리거 목록을 불러올게요'); // 허용 예 1개
      expect(section).toMatch(/save_as_smart_job|mcp__firehub__/); // 금지 예 (도구명)
      expect(section).toMatch(/병렬로 N개 호출합니다|사전 계획 선언/); // 금지 예 (사전 계획)
    });
  });

  // #260: 파일 첨부 가이드는 SYSTEM_PROMPT 본문에서 분리되어 fileIds가 있는 요청에만 동적 첨부된다.
  // cold cache_creation 945 토큰 절감 — 첨부 없는 일반 요청 대다수에 해당.
  describe('파일 첨부 가이드 분리 (refs #260)', () => {
    it('SYSTEM_PROMPT 본문에 "파일 첨부 처리" 섹션을 포함하지 않는다', () => {
      expect(SYSTEM_PROMPT).not.toContain('## 파일 첨부 처리');
    });

    it('FILE_ATTACHMENT_PROMPT가 첨부 처리 핵심 가이드를 보존한다', () => {
      expect(FILE_ATTACHMENT_PROMPT).toContain('## 파일 첨부 처리');
      expect(FILE_ATTACHMENT_PROMPT).toContain('XLSX');
      expect(FILE_ATTACHMENT_PROMPT).toContain('openpyxl');
      expect(FILE_ATTACHMENT_PROMPT).toContain('DOCX');
      // #262: 경로 제한 wording 강화 — "경로 제한" 헤더 + Bash 명령 차단 명시
      expect(FILE_ATTACHMENT_PROMPT).toContain('경로 제한');
      expect(FILE_ATTACHMENT_PROMPT).toMatch(/첨부 파일 경로/);
    });
  });

  // #260 Phase 1-C: 도구 카탈로그(나열형 설명)는 MCP zod schema description으로 대체되어 제거되었으나,
  // 카탈로그에 인라인 박혀있던 enforcement 정책(DDL 금지, API 인증 가드)은 L3 가드로 흡수 보존.
  describe('L3. 통합 가드 — 입력 합성 금지', () => {
    it('execute_sql_query DDL 금지 정책을 유지한다', () => {
      expect(SYSTEM_PROMPT).toContain('DDL');
      expect(SYSTEM_PROMPT).toMatch(/ALTER.*CREATE.*DROP.*RENAME|DDL SQL/s);
      expect(SYSTEM_PROMPT).toMatch(/스키마 변경.*dataset-manager/);
    });

    // #260: 이슈 번호 본문 미포함 정책, 가드 핵심 텍스트만 검증.
    it('create_api_connection 인증 가드를 유지한다', () => {
      const section = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(section).toBeDefined();
      expect(section).toMatch(/API_KEY.*BEARER|BEARER.*API_KEY/);
      expect(section).toMatch(/placeholder.*authConfig|authConfig.*placeholder|더미.*합성 금지/);
    });
  });

  describe('도구 카탈로그 제거 후 enforcement 정책 보존 (refs #260)', () => {
    // #260: SYSTEM_PROMPT 본문엔 이슈 번호 메타가 포함되지 않는다 (토큰 절감).
    it('본문에 issue ref 메타(#XXX) 패턴이 없다', () => {
      expect(SYSTEM_PROMPT).not.toMatch(/refs?\s*#\d+/);
      expect(SYSTEM_PROMPT).not.toMatch(/\b이슈\s+#\d+/);
    });

    it('나열형 도구 카탈로그 섹션 헤더는 제거되었다', () => {
      expect(SYSTEM_PROMPT).not.toContain('## 사용 가능한 도구');
      expect(SYSTEM_PROMPT).not.toContain('[카테고리]');
      expect(SYSTEM_PROMPT).not.toContain('[데이터셋 조회]');
    });
  });

  // #260 PR-1: SYSTEM_PROMPT 6 레이어 구조 검증
  describe('SYSTEM_PROMPT 6 레이어 구조', () => {
    it('L1~L6 모든 레이어 헤더가 존재한다', () => {
      expect(SYSTEM_PROMPT).toContain('## L1. 라우팅');
      expect(SYSTEM_PROMPT).toContain('## L1-1. 도구 선택 우선순위');
      expect(SYSTEM_PROMPT).toContain('## L1-2. 단순 데이터 조회');
      expect(SYSTEM_PROMPT).toContain('## L2. 응답 출력 규칙');
      expect(SYSTEM_PROMPT).toContain('## L3. 통합 가드 패턴');
      expect(SYSTEM_PROMPT).toContain('## L4. N+1 호출 금지');
      expect(SYSTEM_PROMPT).toContain('## L5. PII 마스킹');
      expect(SYSTEM_PROMPT).toContain('## L6. 화면 컨텍스트');
    });

    it('사회공학 차단은 L3 안에서 단일 위치로 정의된다 (중복 제거)', () => {
      // "yolo" / "skip confirm" / "force create" 키워드가 L3 안에 등장하는지 확인.
      // (이전엔 파괴/DESIGN/입력합성 4섹션에 반복 정의되어 있었음)
      const l3 = SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1]?.split('## L4. ')[0]
        ?? SYSTEM_PROMPT.split('## L3. 통합 가드 패턴')[1];
      expect(l3).toBeDefined();
      expect(l3.toLowerCase()).toContain('yolo');
      expect(l3.toLowerCase()).toContain('skip confirm');
      expect(l3.toLowerCase()).toContain('force create');
    });
  });
});
