import { describe, it, expect, vi } from 'vitest';
import { ingestDataset, IngestDeps } from './ingest.js';
import { CORE_ONTOLOGY } from './ontology.js';

// 이름 문자열을 그대로 결정적 벡터로 만드는 mock embed. 동일 이름은 항상 동일 벡터 →
// 코사인 1.0으로 자기 자신과만 병합되고, 서로 다른 이름은 병합되지 않는다(순수 오케스트레이션 검증 목적).
const mockEmbed = vi.fn(async (texts: string[]) => texts.map((t) => {
  let h = 0;
  for (const ch of t) h = (h * 31 + ch.charCodeAt(0)) % 1000;
  return [h, 1000 - h, 1];
}));

describe('ingestDataset', () => {
  it('2단계(수집→전역 해소→적재)로 오케스트레이션하고 distinct canonical 카운트를 반환한다', async () => {
    const load = vi.fn().mockResolvedValue({ nodes: 2, relations: 1 });
    const deps: IngestDeps = {
      listChunks: vi.fn().mockResolvedValue([
        { chunkId: 10, content: 'c1' }, { chunkId: 11, content: 'c2' },
      ]),
      // 두 청크 모두 같은 이름의 엔티티(A, B)를 추출 → 청크 간 중복.
      extract: vi.fn().mockResolvedValue({
        entities: [{ type: 'Incident', name: 'A' }, { type: 'Cause', name: 'B' }],
        relations: [{ subject: 'A', type: 'CAUSED_BY', object: 'B' }],
      }),
      load,
      embed: mockEmbed,
    };
    const summary = await ingestDataset(deps, 7, CORE_ONTOLOGY);

    expect(deps.extract).toHaveBeenCalledTimes(2);
    // load는 청크별로 호출되며, remap된 그래프와 현재 온톨로지 schemaVersion을 받는다(5-4).
    expect(load).toHaveBeenCalledWith(expect.anything(), 10, CORE_ONTOLOGY.schemaVersion);
    expect(load).toHaveBeenCalledWith(expect.anything(), 11, CORE_ONTOLOGY.schemaVersion);
    expect(mockEmbed).toHaveBeenCalled();

    // 청크 간 동일 엔티티(A, B)가 중복 추출되었으므로 distinct canonical 엔티티는 2개, 관계는 1개.
    expect(summary).toEqual({ datasetId: 7, chunks: 2, entities: 2, relations: 1 });
  });

  it('추출 결과가 비어있는 청크가 있어도 계속 진행한다', async () => {
    const load = vi.fn().mockResolvedValue({ nodes: 0, relations: 0 });
    const deps: IngestDeps = {
      listChunks: vi.fn().mockResolvedValue([
        { chunkId: 1, content: 'empty' }, { chunkId: 2, content: 'c2' },
      ]),
      extract: vi.fn()
        .mockResolvedValueOnce({ entities: [], relations: [] })
        .mockResolvedValueOnce({ entities: [{ type: 'Incident', name: 'X' }], relations: [] }),
      load,
      embed: mockEmbed,
    };
    const summary = await ingestDataset(deps, 8, CORE_ONTOLOGY);
    expect(summary.entities).toBe(1);
    expect(summary.chunks).toBe(2);
  });
});
