/**
 * proactiveApi.getAllTemplateNames 단위 테스트 (WD-837·WD-842)
 *
 * 양식 이름 읽기는 size 200, page 0·1 은 순차, page 2 부터 4페이지씩 병렬, 최대 20페이지이며 짧은 페이지에서 멈춘다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const getMock = vi.fn();
vi.mock('./client', () => ({ client: { get: (...args: unknown[]) => getMock(...args) } }));

import { proactiveApi } from './proactive';

/** n 건의 양식 요약(이름만 의미 있음)을 만든다. */
const pageOf = (n: number, tag: string) => Array.from({ length: n }, (_, i) => ({ name: `${tag}-${i}` }));

/** page 별 응답을 정해 주는 구현을 건다. 값이 Error 면 reject, 숫자면 그 길이만큼 이름을 돌려준다. */
const mockPages = (byPage: Record<number, number | Error>) => {
  getMock.mockImplementation((_url: string, cfg: { params: { page: number } }) => {
    if (!cfg) return undefined;
    const v = byPage[cfg.params.page];
    if (v === undefined) return Promise.resolve({ data: [] });
    if (v instanceof Error) return Promise.reject(v);
    return Promise.resolve({ data: pageOf(v, `p${cfg.params.page}`) });
  });
};
const requestedPages = () => getMock.mock.calls.map((c) => (c[1] as { params: { page: number } }).params.page);

describe('proactiveApi.getAllTemplateNames (WD-837·WD-842)', () => {
  beforeEach(() => getMock.mockReset());

  it('첫 페이지가 짧으면 요청은 1번이다', async () => {
    getMock.mockResolvedValue({ data: pageOf(3, 'a') });
    const names = await proactiveApi.getAllTemplateNames();
    expect(names).toHaveLength(3);
    expect(getMock).toHaveBeenCalledTimes(1);
  });

  it('page 0·1 응답 뒤에야 page 2~5 를 동시에 요청하고, 짧은 페이지가 나오면 다음 묶음은 요청하지 않는다', async () => {
    const pending: Record<number, (v: unknown) => void> = {};
    getMock.mockImplementation((_url: string, cfg: { params: { page: number; size: number } }) => {
      // vitest 의 정리 단계가 인자 없이 구현을 부르기도 한다 — 그때는 아무것도 하지 않는다
      if (!cfg) return undefined;
      const { page, size } = cfg.params;
      expect(size).toBe(200);
      if (page === 0) return Promise.resolve({ data: pageOf(200, 'p0') });
      return new Promise((resolve) => {
        pending[page] = resolve;
      });
    });

    const promise = proactiveApi.getAllTemplateNames();
    // page 0 응답 뒤 page 1 만 나가 있고, page 2 요청은 아직 없다
    await vi.waitFor(() => expect(Object.keys(pending).map(Number)).toEqual([1]));
    pending[1]({ data: pageOf(200, 'p1') });
    // page 1 응답 뒤 page 2~5 요청이 모두 나간다
    await vi.waitFor(() => expect(Object.keys(pending).map(Number).sort()).toEqual([1, 2, 3, 4, 5]));
    pending[2]({ data: pageOf(200, 'p2') });
    pending[3]({ data: pageOf(50, 'p3') });
    pending[4]({ data: [] });
    pending[5]({ data: [] });

    const names = await promise;
    // 짧은 페이지(3) 뒤의 페이지(4·5)는 이름에 싣지 않는다
    expect(names).toHaveLength(200 + 200 + 200 + 50);
    expect(getMock).toHaveBeenCalledTimes(6);
    expect(requestedPages()).not.toContain(6);
  });

  it('양식이 201건이면 요청은 2건이다', async () => {
    mockPages({ 0: 200, 1: 1 });
    const names = await proactiveApi.getAllTemplateNames();
    expect(names).toHaveLength(201);
    expect(getMock).toHaveBeenCalledTimes(2);
  });

  it('page 1 이 실패하면 page 0 이름을 돌려준다', async () => {
    mockPages({ 0: 200, 1: new Error('boom') });
    const names = await proactiveApi.getAllTemplateNames();
    expect(names).toHaveLength(200);
    expect(getMock).toHaveBeenCalledTimes(2);
  });

  it('병렬 묶음에서 한 페이지가 실패하면 그 페이지만 빼고 묶음의 나머지 이름은 담고 더 읽지 않는다', async () => {
    mockPages({ 0: 200, 1: 200, 2: 200, 3: new Error('boom'), 4: 200, 5: 200 });
    const names = await proactiveApi.getAllTemplateNames();
    // page 3 만 빠진다 — page 0·1·2·4·5
    expect(names).toHaveLength(1000);
    expect(requestedPages()).not.toContain(6);
  });

  it('묶음 안에서 실패한 페이지 뒤 짧은 페이지까지 담고 더 읽지 않는다', async () => {
    mockPages({ 0: 200, 1: 200, 2: new Error('boom'), 3: 30 });
    const names = await proactiveApi.getAllTemplateNames();
    expect(names).toHaveLength(430);
    expect(requestedPages()).not.toContain(6);
  });

  it('짧은 페이지 뒤 페이지가 실패해도 짧은 페이지까지 돌려준다', async () => {
    mockPages({ 0: 200, 1: 200, 2: 30, 3: new Error('boom') });
    const names = await proactiveApi.getAllTemplateNames();
    expect(names).toHaveLength(430);
  });

  it('첫 페이지가 실패하면 reject 한다', async () => {
    mockPages({ 0: new Error('boom') });
    await expect(proactiveApi.getAllTemplateNames()).rejects.toThrow();
  });

  it('계속 가득 차도 20페이지(0~19)에서 멈춘다', async () => {
    getMock.mockImplementation(() => Promise.resolve({ data: pageOf(200, 'x') }));
    const names = await proactiveApi.getAllTemplateNames();
    expect(getMock).toHaveBeenCalledTimes(20);
    expect(names).toHaveLength(20 * 200);
    expect([...requestedPages()].sort((a, b) => a - b)).toEqual(Array.from({ length: 20 }, (_, i) => i));
  });
});
