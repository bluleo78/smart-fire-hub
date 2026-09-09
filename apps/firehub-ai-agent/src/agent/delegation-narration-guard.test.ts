import { describe, it, expect } from 'vitest';
import {
  mentionsSubagentIdentifier,
  redactSubagentIdentifiers,
  isNoopHostToolCall,
  createDelegationNarrationState,
  classifyMainText,
  noteMainToolUse,
  noteMainToolResult,
  noteSubagentText,
  containsRoutingVocabulary,
  redactRoutingVocabulary,
  createRoutingVocabRedactor,
} from './delegation-narration-guard.js';

// #578: 위임 narration 가드의 판별 함수 — inspector trace(2026-09-09T23-15 trig-002/009/010/012/013)에서
// 실제로 관찰된 문구를 그대로 회귀 케이스로 고정한다.
const NAMES = ['trigger-manager', 'pipeline-builder', 'data-analyst', 'dataset-manager'];

describe('mentionsSubagentIdentifier (#578)', () => {
  it('한글 조사가 바로 붙은 코드명("trigger-manager에게")을 잡는다', () => {
    expect(mentionsSubagentIdentifier('trigger-manager에게 위임합니다.', NAMES)).toBe(true);
    expect(mentionsSubagentIdentifier('외부 호출용 API 트리거를 만들도록 trigger-manager에게 설계를 요청할게요.', NAMES)).toBe(true);
  });

  it('코드명이 없는 일반 응답·허용 status 는 잡지 않는다', () => {
    expect(mentionsSubagentIdentifier('트리거 목록을 불러올게요', NAMES)).toBe(false);
    expect(mentionsSubagentIdentifier("'주간 검증' 트리거가 등록되었습니다 (ID: 49).", NAMES)).toBe(false);
    expect(mentionsSubagentIdentifier('', NAMES)).toBe(false);
  });

  it('다른 토큰의 부분 문자열("data-analysts")은 이름 매칭으로 잡지 않는다', () => {
    expect(mentionsSubagentIdentifier('data-analysts 팀', NAMES)).toBe(false);
  });

  it('이름 목록이 없어도 *-manager/*-builder/*-analyst 접미사 패턴으로 하한선 판별한다', () => {
    expect(mentionsSubagentIdentifier('smart-job-manager에게 맡길게요')).toBe(true);
    expect(mentionsSubagentIdentifier('report-writer 가 처리합니다')).toBe(true);
    expect(mentionsSubagentIdentifier('매니저에게 문의하세요')).toBe(false);
  });
});

describe('redactSubagentIdentifiers (#578)', () => {
  it('코드명만 중립 표현으로 바꾸고 나머지 문장은 유지한다', () => {
    expect(redactSubagentIdentifiers('trigger-manager에게 위임합니다.', NAMES)).toBe('전문 에이전트에게 위임합니다.');
  });
});

describe('isNoopHostToolCall (#578)', () => {
  it('Bash("echo noop") / Bash("true") 를 no-op 으로 판별한다', () => {
    expect(isNoopHostToolCall('Bash', { command: 'echo noop' })).toBe(true);
    expect(isNoopHostToolCall('Bash', { command: 'true' })).toBe(true);
    expect(isNoopHostToolCall('Bash', { command: ':' })).toBe(true);
  });

  it('첨부 파일 처리 등 실제 명령은 no-op 이 아니다', () => {
    expect(isNoopHostToolCall('Bash', { command: 'python3 -c "import openpyxl"' })).toBe(false);
    expect(isNoopHostToolCall('Bash', { command: 'echo $HOME/chat-files/1.csv | head' })).toBe(false);
    expect(isNoopHostToolCall('mcp__firehub__list_triggers', { pipelineId: 15 })).toBe(false);
    expect(isNoopHostToolCall('Bash', {})).toBe(false);
  });
});

describe('classifyMainText / 상태 전이 (#578)', () => {
  it('비동기 Agent 위임 직후의 메인 텍스트는 억제되고, subagent 텍스트 도착으로 구간이 끝난다', () => {
    const state = createDelegationNarrationState();
    noteMainToolUse(state, 'Agent', { subagent_type: 'trigger-manager', prompt: '...' });
    expect(classifyMainText(state, '트리거 변경 작업을 진행 중입니다. 완료되면 결과를 전달드릴게요.', NAMES)).toEqual({
      suppress: true,
      reason: 'awaiting-delegation',
    });
    expect(state.lastSuppressedMainText).toContain('진행 중입니다');
    noteSubagentText(state);
    expect(classifyMainText(state, '후속 안내', NAMES)).toEqual({ suppress: false });
  });

  it('동기 위임(run_in_background:false)은 위임 직후 구간을 만들지 않는다(#573 relay 경로가 담당)', () => {
    const state = createDelegationNarrationState();
    noteMainToolUse(state, 'Agent', { subagent_type: 'data-analyst', run_in_background: false });
    expect(state.awaitingAsyncDelegation).toBe(false);
  });

  it('메인이 다른 도구를 발행하면 구간이 끝난다(#429 원칙)', () => {
    const state = createDelegationNarrationState();
    noteMainToolUse(state, 'SendMessage', { to: 'a1', message: '계속' });
    expect(state.awaitingAsyncDelegation).toBe(true);
    noteMainToolUse(state, 'mcp__firehub__get_pipeline', { id: 15 });
    expect(state.awaitingAsyncDelegation).toBe(false);
  });

  it('위임 tool_result 가 is_error 면 구간이 끝나 메인이 직접 설명할 수 있다', () => {
    const state = createDelegationNarrationState();
    noteMainToolUse(state, 'Agent', { subagent_type: 'trigger-manager' });
    noteMainToolResult(state, false); // "Async agent launched" — 유지
    expect(state.awaitingAsyncDelegation).toBe(true);
    noteMainToolResult(state, true);
    expect(state.awaitingAsyncDelegation).toBe(false);
  });

  it('위임 구간 밖이라도 코드명을 포함한 메인 텍스트는 억제한다', () => {
    const state = createDelegationNarrationState();
    expect(classifyMainText(state, '트리거 32번이 속한 파이프라인 정보를 확인 후 trigger-manager에게 위임하겠습니다.', NAMES)).toEqual({
      suppress: true,
      reason: 'subagent-identifier',
    });
  });
});

// #581: 도구 호출이 전혀 없는 되묻기 턴의 라우팅 어휘 누출 — inspector crosscheck-578-trig-009 실측 문구를
// 회귀 케이스로 고정한다. 억제가 아니라 치환이어야 한다(빈 응답·relay 누락 방지).
describe('라우팅 어휘 치환 (#581)', () => {
  const LEAK = '트리거 삭제(파괴 작업)는 위임하되, 먼저 어느 파이프라인 소속인지 알려주시겠어요? (파이프라인 ID 또는 이름)';

  it('#578 가드는 코드명 없는 되묻기 턴의 "위임하되"를 잡지 못한다(사각 확인)', () => {
    const state = createDelegationNarrationState();
    expect(classifyMainText(state, LEAK, NAMES)).toEqual({ suppress: false });
  });

  it('containsRoutingVocabulary / redactRoutingVocabulary — 어휘만 바꾸고 문장은 유지한다', () => {
    expect(containsRoutingVocabulary(LEAK)).toBe(true);
    expect(containsRoutingVocabulary('트리거 32번이 어느 파이프라인에 속해 있는지 알려주시겠어요?')).toBe(false);
    expect(redactRoutingVocabulary(LEAK)).toBe(
      '트리거 삭제(파괴 작업)는 처리하되, 먼저 어느 파이프라인 소속인지 알려주시겠어요? (파이프라인 ID 또는 이름)',
    );
    expect(redactRoutingVocabulary('라우팅 규칙상 위임 대상입니다')).toBe('연결 규칙상 처리 대상입니다');
  });

  it('스트림 치환기 — 델타 경계에 걸친 "위"+"임하되"를 보류했다가 이어 붙여 치환한다', () => {
    const r = createRoutingVocabRedactor('트리거 32번 삭제해줘');
    expect(r.enabled).toBe(true);
    const out: string[] = [];
    for (const d of ['트리거 삭제는 위', '임하되, 먼저 ', '어느 파이프라인인지 알려주세요']) out.push(r.push(d));
    out.push(r.flush());
    expect(out.join('')).toBe('트리거 삭제는 처리하되, 먼저 어느 파이프라인인지 알려주세요');
    // 첫 델타는 꼬리 '위' 만 보류하고 나머지는 즉시 방출된다(체감 지연 없음)
    expect(out[0]).toBe('트리거 삭제는 ');
  });

  it('스트림 치환기 — 어휘가 아닌 "위"로 끝나는 델타("범위")는 다음 델타에서 그대로 풀린다', () => {
    const r = createRoutingVocabRedactor('');
    expect(r.push('조회 범위')).toBe('조회 범');
    expect(r.push('를 알려주세요')).toBe('위를 알려주세요');
    expect(r.flush()).toBe('');
  });

  it('스트림 치환기 — 스트림이 꼬리로 끝나면 flush 가 남은 글자를 돌려준다', () => {
    const r = createRoutingVocabRedactor('');
    expect(r.push('상위')).toBe('상');
    expect(r.flush()).toBe('위');
  });

  it('사용자가 어휘를 직접 쓴 데이터 문맥이면 치환을 끈다(오탐 방지)', () => {
    const r = createRoutingVocabRedactor('업무 위임 테이블 만들어줘');
    expect(r.enabled).toBe(false);
    expect(r.push('업무 위임 테이블을 만들까요?')).toBe('업무 위임 테이블을 만들까요?');
    expect(r.redact('위임 컬럼 포함')).toBe('위임 컬럼 포함');
  });
});
