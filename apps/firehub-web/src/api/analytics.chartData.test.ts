/**
 * analyticsApi.getChartData 단위 테스트 (WD-693)
 *
 * 성공(200)인데 차트 데이터가 없는 응답(null·빈 본문)은 오류(EmptyResponseError)로 바꿔 위젯이 스피너에 멈추지 않게 한다.
 * 원본을 볼 수 없는 위젯의 `denied: true` 응답은 유효한 본문이라 오류가 아니다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { EmptyResponseError, isEmptyResponseError } from '../lib/api-error';

const getMock = vi.fn();
vi.mock('./client', () => ({ client: { get: (...args: unknown[]) => getMock(...args) } }));

import { analyticsApi } from './analytics';

describe('analyticsApi.getChartData — 빈 성공 응답 (WD-693)', () => {
  beforeEach(() => getMock.mockReset());

  it.each([
    ['null 본문', null],
    ['빈 문자열 본문', ''],
    ['chart 없는 객체', { queryResult: null }],
  ])('%s 이면 EmptyResponseError 로 실패한다', async (_label, data) => {
    getMock.mockResolvedValue({ status: 200, data });
    const error = await analyticsApi.getChartData(1).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(EmptyResponseError);
    expect(isEmptyResponseError(error)).toBe(true);
  });

  it('denied 응답은 유효한 본문이라 그대로 돌려준다', async () => {
    const data = { chart: { id: 1, config: {} }, queryResult: { rows: [] }, denied: true };
    getMock.mockResolvedValue({ status: 200, data });
    await expect(analyticsApi.getChartData(1)).resolves.toMatchObject({ data: { denied: true } });
  });

  it('chart 가 있는 응답은 config 를 정규화해 돌려준다', async () => {
    getMock.mockResolvedValue({
      status: 200,
      data: { chart: { id: 1, config: { xAxis: 'm', yAxis: ['v'] } }, queryResult: { rows: [] } },
    });
    const r = await analyticsApi.getChartData(1);
    expect(r.data.chart.config).toMatchObject({ xAxis: 'm' });
  });
});
