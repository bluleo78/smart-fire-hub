import { describe, it, expect, vi } from 'vitest';
import { ingestDataset } from './ingest.js';

describe('ingestDataset', () => {
  it('청크별 추출→해소→적재를 오케스트레이션하고 합계를 반환한다', async () => {
    const load = vi.fn().mockResolvedValue({ nodes: 2, relations: 1 });
    const deps = {
      listChunks: vi.fn().mockResolvedValue([
        { chunkId: 10, content: 'c1' }, { chunkId: 11, content: 'c2' },
      ]),
      extract: vi.fn().mockResolvedValue({
        entities: [{ type: 'Incident', name: 'A' }, { type: 'Cause', name: 'B' }],
        relations: [{ subject: 'A', type: 'CAUSED_BY', object: 'B' }],
      }),
      load,
    };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const summary = await ingestDataset(deps as any, 7);
    expect(deps.extract).toHaveBeenCalledTimes(2);
    expect(load).toHaveBeenCalledWith(expect.anything(), 10);
    expect(load).toHaveBeenCalledWith(expect.anything(), 11);
    expect(summary).toEqual({ datasetId: 7, chunks: 2, entities: 4, relations: 2 });
  });
});
