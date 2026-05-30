import { describe, it, expect } from 'vitest';
import {
  normalizeErrorType,
  createTracker,
  buildHaltMessage,
  FAILURE_WARN_SENTINEL,
} from './failure-streak.js';

describe('normalizeErrorType', () => {
  it('빈/공백 입력은 unknown', () => {
    expect(normalizeErrorType('')).toBe('unknown');
    expect(normalizeErrorType('   ')).toBe('unknown');
  });
  it('따옴표 식별자/숫자가 달라도 같은 종류로 묶인다(느슨)', () => {
    expect(normalizeErrorType('column "foo" does not exist')).toBe(
      normalizeErrorType('column "bar" does not exist'),
    );
  });
  it('서로 다른 오류 메시지는 다른 종류', () => {
    expect(normalizeErrorType('column "x" does not exist')).not.toBe(
      normalizeErrorType('relation "y" does not exist'),
    );
  });
  it('경고 sentinel 이후는 정규화에서 제외', () => {
    const raw = 'column "x" does not exist';
    expect(normalizeErrorType(raw + FAILURE_WARN_SENTINEL + '\n[시스템] ...')).toBe(
      normalizeErrorType(raw),
    );
  });
});

describe('createTracker', () => {
  it('동일 키 4회째 warn, 8회째 halt', () => {
    const t = createTracker();
    const err = 'column "x" does not exist';
    expect(t.record('execute_sql_query', err, true)).toMatchObject({ warn: false, halt: false });
    t.record('execute_sql_query', err, true);
    t.record('execute_sql_query', err, true);
    const r4 = t.record('execute_sql_query', err, true);
    expect(r4.warn).toBe(true);
    expect(r4.halt).toBe(false);
    t.record('execute_sql_query', err, true);
    t.record('execute_sql_query', err, true);
    t.record('execute_sql_query', err, true);
    expect(t.record('execute_sql_query', err, true).halt).toBe(true);
  });
  it('warn은 키당 1회만', () => {
    const t = createTracker();
    for (let i = 0; i < 3; i++) t.record('tool_a', 'boom', true);
    expect(t.record('tool_a', 'boom', true).warn).toBe(true);
    expect(t.record('tool_a', 'boom', true).warn).toBe(false);
  });
  it('같은 도구 성공 시 카운터 리셋', () => {
    const t = createTracker();
    t.record('tool_a', 'boom', true);
    t.record('tool_a', 'boom', true);
    t.record('tool_a', 'ok', false);
    expect(t.record('tool_a', 'boom', true).count).toBe(1);
  });
  it('입력만 달라도 같은 에러종류면 누적(느슨 키잉)', () => {
    const t = createTracker();
    for (let i = 0; i < 7; i++) t.record('update_pipeline', `step "s${i}" invalid: cycle detected`, true);
    expect(t.record('update_pipeline', 'step "s7" invalid: cycle detected', true).halt).toBe(true);
  });
  it('서로 다른 도구는 독립 카운트', () => {
    const t = createTracker();
    for (let i = 0; i < 8; i++) t.record('tool_a', 'e', true);
    const rb = t.record('tool_b', 'e', true);
    expect(rb.count).toBe(1);
    expect(rb.halt).toBe(false);
  });
  it('warnAt/haltAt 커스텀', () => {
    const t = createTracker({ warnAt: 2, haltAt: 3 });
    t.record('tool_a', 'e', true);
    expect(t.record('tool_a', 'e', true).warn).toBe(true);
    expect(t.record('tool_a', 'e', true).halt).toBe(true);
  });
});

describe('buildHaltMessage', () => {
  it('도구명 포함 + 긴 오류 절단', () => {
    const msg = buildHaltMessage('execute_sql_query', 'x'.repeat(500));
    expect(msg).toContain('execute_sql_query');
    expect(msg).toContain('…');
    expect(msg.length).toBeLessThan(300);
  });
});
