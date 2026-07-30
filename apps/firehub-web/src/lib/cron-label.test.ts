/**
 * cron-label 단위 테스트 — cron 표현식 → 사람이 읽기 쉬운 라벨.
 */
import { describe, expect, it } from 'vitest';

import { cronToLabel } from './cron-label';

describe('cronToLabel', () => {
  it('알려진 표현식은 한글 라벨로', () => {
    expect(cronToLabel('0 9 * * *')).toBe('매일 오전 9시');
    expect(cronToLabel('0 0 * * *')).toBe('매일 자정');
    expect(cronToLabel('0 9 * * 1-5')).toBe('평일 오전 9시');
    expect(cronToLabel('*/30 * * * *')).toBe('30분마다');
    expect(cronToLabel('0 * * * *')).toBe('매시간');
    expect(cronToLabel('0 0 1 * *')).toBe('매월 1일 자정');
  });

  /**
   * #347의 핵심 — 같은 스케줄이 5필드/6필드 두 표기로 저장돼 있어도 한 라벨로 보여야 한다.
   * 이전에는 6필드가 룩업 테이블에 없어 원시 cron이 그대로 노출됐다.
   */
  it('6필드 Spring cron도 같은 스케줄이면 같은 라벨', () => {
    expect(cronToLabel('0 0 9 * * *')).toBe(cronToLabel('0 9 * * *'));
    expect(cronToLabel('0 0 9 * * *')).toBe('매일 오전 9시');
    expect(cronToLabel('0 0 8 * * *')).toBe('매일 오전 8시');
    expect(cronToLabel('0 0 * * * *')).toBe('매시간');
  });

  it('룩업에 없는 정규 형태도 문장으로 변환', () => {
    expect(cronToLabel('15 4 * * 3')).toBe('매주 수요일 오전 4시 15분');
    expect(cronToLabel('0 0 4 * * 3')).toBe('매주 수요일 오전 4시');
    expect(cronToLabel('30 14 * * *')).toBe('매일 오후 2시 30분');
    expect(cronToLabel('0 12 * * *')).toBe('매일 정오');
    expect(cronToLabel('0 0 15 * *')).toBe('매월 15일 자정');
    expect(cronToLabel('*/10 * * * *')).toBe('10분마다');
    expect(cronToLabel('0 */6 * * *')).toBe('6시간마다');
    expect(cronToLabel('20 * * * *')).toBe('매시간 20분');
  });

  /**
   * 초 필드가 0이 아닌 6필드는 5필드로 환원할 수 없다. 초를 버리고 라벨을 붙이면
   * '매일 오전 9시'라는 틀린 값이 자신 있게 표시되므로, 폴백으로 넘겨야 한다.
   */
  it('초가 0이 아닌 6필드는 라벨을 만들지 않고 폴백', () => {
    expect(cronToLabel('30 0 9 * * *')).toBe('주기: 30 0 9 * * *');
  });

  it('해석 불가 표현은 폴백임이 드러나게 감싼다', () => {
    expect(cronToLabel('0 9 1 1 *')).toBe('주기: 0 9 1 1 *');
    expect(cronToLabel('not a cron')).toBe('주기: not a cron');
    expect(cronToLabel('0 9 * * 1,3,5')).toBe('주기: 0 9 * * 1,3,5');
  });
});
