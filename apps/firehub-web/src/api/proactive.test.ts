/**
 * proactiveApi.getAllTemplateNames 단위 테스트 (WD-837)
 *
 * 양식 이름 읽기는 size 200, 첫 페이지 뒤 4페이지씩 병렬, 최대 20페이지이며 짧은 페이지에서 멈춘다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const getMock = vi.fn();
vi.mock('./client', () => ({ client: { get: (...args: unknown[]) => getMock(...args) } }));

import { proactiveApi } from './proactive';

/** n 건의 양식 요약(이름만 의미 있음)을 만든다. */
const pageOf = (n: number, tag: string) => Array.from({ length: n }, (_, i) => ({ name: `${tag}-${i}` }));

describe('proactiveApi.getAllTemplateNames (WD-837)', () => {
  beforeEach(() => getMock.mockReset());

  it('첫 페이지가 짧으면 요청은 1번이다', async () => {
    getMock.mockResolvedValue({ data: pageOf(3, 'a') });
    const names = await proactiveApi.getAllTemplateNames();
    expect(names).toHaveLength(3);
    expect(getMock).toHaveBeenCalledTimes(1);
  });

  it('첫 페이지 뒤 4페이지를 동시에 요청하고, 짧은 페이지가 나오면 다음 묶음은 요청하지 않는다', async () => {
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
    // page 0 응답 뒤 page 1~4 요청이 모두 나간 다음에야 응답한다 — 순차였다면 page 1 만 나가 있다
    await vi.waitFor(() => expect(Object.keys(pending).map(Number).sort()).toEqual([1, 2, 3, 4]));
    pending[1]({ data: pageOf(200, 'p1') });
    pending[2]({ data: pageOf(50, 'p2') });
    pending[3]({ data: [] });
    pending[4]({ data: [] });

    const names = await promise;
    // 짧은 페이지(2) 뒤의 페이지(3·4)는 이름에 싣지 않는다
    expect(names).toHaveLength(200 + 200 + 50);
    expect(getMock).toHaveBeenCalledTimes(5);
    const pages = getMock.mock.calls.map((c) => (c[1] as { params: { page: number } }).params.page);
    expect(pages).not.toContain(5);
  });

  it('계속 가득 차도 20페이지(0~19)에서 멈춘다', async () => {
    getMock.mockImplementation(() => Promise.resolve({ data: pageOf(200, 'x') }));
    const names = await proactiveApi.getAllTemplateNames();
    expect(getMock).toHaveBeenCalledTimes(20);
    expect(names).toHaveLength(20 * 200);
  });
});
