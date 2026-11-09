/**
 * embeddingStatusRefetchInterval 단위 테스트(WD-600).
 *
 * 겨냥하는 계약: 현황 조회가 실패(403 등)하면 폴링을 멈춘다. 예전에는 data 가 없으면 무조건 3초 뒤 다시 불러
 * ai:settings 없는 사용자 화면에서 403 이 3초마다 반복됐다. 나머지 분기(첫 응답 전·진행 중·완료·미설정)는 그대로다.
 */
import { describe, expect, it } from 'vitest';

import type { EmbeddingStatus } from '../../api/embedding';
import { embeddingStatusRefetchInterval } from './useEmbedding';

const status = (over: Partial<EmbeddingStatus> = {}): EmbeddingStatus => ({
  configured: true,
  model: 'bge-m3',
  dimension: 1024,
  datasets: { total: 2, embedded: 2 },
  documentChunks: { total: 10, embedded: 10 },
  job: null,
  ...over,
});

describe('embeddingStatusRefetchInterval', () => {
  it('조회 실패면 데이터가 없어도 폴링을 멈춘다', () => {
    expect(embeddingStatusRefetchInterval({ status: 'error', data: undefined })).toBe(false);
  });

  it('이전 성공 데이터가 진행 중이어도 실패 상태면 멈춘다', () => {
    const running = status({ documentChunks: { total: 10, embedded: 3 } });
    expect(embeddingStatusRefetchInterval({ status: 'error', data: running })).toBe(false);
  });

  it('첫 응답 전(pending, 데이터 없음)에는 폴링을 이어 간다', () => {
    expect(embeddingStatusRefetchInterval({ status: 'pending', data: undefined })).toBe(3000);
  });

  it('진행 중이면 3초, 완료·미설정이면 멈춘다', () => {
    expect(
      embeddingStatusRefetchInterval({
        status: 'success',
        data: status({ documentChunks: { total: 10, embedded: 3 } }),
      }),
    ).toBe(3000);
    expect(embeddingStatusRefetchInterval({ status: 'success', data: status() })).toBe(false);
    expect(
      embeddingStatusRefetchInterval({ status: 'success', data: status({ configured: false }) }),
    ).toBe(false);
  });
});
