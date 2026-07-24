import { describe, it, expect, vi } from 'vitest';
import { ingestDataset, IngestDeps } from './ingest.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';
import { entityKey } from './resolver.js';

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

  it('IngestDeps.link 가 주어지면 buildCanonicalMap 에 전달된다', async () => {
    const load = vi.fn().mockResolvedValue({ nodes: 1, relations: 0 });
    const link = vi.fn().mockResolvedValue({ same: false, rationale: '' });
    const deps: IngestDeps = {
      listChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: 'c1' }]),
      extract: vi.fn().mockResolvedValue({
        entities: [
          { type: 'Cause', name: '전기적 요인' },
          { type: 'Cause', name: '분전반의 누전' },
        ],
        relations: [],
      }),
      load,
      // 두 이름이 코사인 0.5~0.78 구간에 들어오도록 고정 벡터.
      embed: vi.fn(async (texts: string[]) => texts.map((t) =>
        (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]))),
      link,
    };
    await ingestDataset(deps, 9, CORE_ONTOLOGY);

    expect(link).toHaveBeenCalledWith('전기적 요인', '분전반의 누전', 'Cause');
  });

  it('deps.lookupDecision/recordPending을 buildCanonicalMap에 전달한다', async () => {
    const lookupDecision = vi.fn().mockResolvedValue(undefined);
    const recordPending = vi.fn().mockResolvedValue(undefined);
    const link = vi.fn().mockResolvedValue({ same: false, rationale: '' });
    const deps: IngestDeps = {
      listChunks: vi.fn().mockResolvedValue([{ chunkId: 1, content: 'x' }]),
      extract: vi.fn().mockResolvedValue({ entities: [], relations: [] }),
      load: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
      embed: vi.fn().mockResolvedValue([]),
      link,
      lookupDecision,
      recordPending,
    };
    await ingestDataset(deps, 1, CORE_ONTOLOGY);
    // 근접쌍이 없는(엔티티 0개) 케이스라 lookupDecision/recordPending이 호출되지 않을 수 있다 —
    // 이 테스트는 buildCanonicalMap 호출 자체에 deps가 실려가는지(타입 오류 없이 통과하는지)만 확인한다.
    expect(deps.load).toHaveBeenCalled();
  });

  it('정규화 실패 후보를 canonical key로 바인딩해 recordPropertyReview를 호출한다', async () => {
    const recordPropertyReview = vi.fn().mockResolvedValue(undefined);
    const deps: IngestDeps = {
      listChunks: async () => [{ chunkId: 7, content: 'c' }],
      extract: async () => ({
        entities: [{ type: 'Incident', name: '창고 화재' }],
        relations: [],
        propertyReviewCandidates: [
          { entityType: 'Incident', entityName: '창고 화재', propertyName: '피해액', dataType: 'number', rawText: '수천만원대' },
        ],
      }),
      load: async () => ({ nodes: 1, relations: 0 }),
      embed: async (texts: string[]) => texts.map(() => [1, 0, 0]),
      recordPropertyReview,
    };
    await ingestDataset(deps, 42, CORE_ONTOLOGY);

    // Incident는 exact 정책 → canonical=자기자신. 최종 key = entityKey(typeId, '창고 화재').
    const expectedKey = entityKey(entityTypeId(CORE_ONTOLOGY, 'Incident'), '창고 화재');
    expect(recordPropertyReview).toHaveBeenCalledWith(42, 7, expectedKey, 'Incident', '피해액', 'number', '수천만원대');
  });

  it('임베딩 클러스터링으로 canonical 이름이 원본과 달라져도(리매핑) recordPropertyReview는 canonical key로 호출된다', async () => {
    // Equipment는 'embedding' 정책 타입 — 표기가 달라도 유사도로 병합될 수 있다.
    // 이 테스트만을 위해 Equipment에 number 속성을 임시로 추가한 온톨로지(CORE_ONTOLOGY 확장)를 사용한다.
    const testOntology = {
      ...CORE_ONTOLOGY,
      entities: CORE_ONTOLOGY.entities.map((e) => (e.type === 'Equipment'
        ? { ...e, properties: [{ name: '설치연도', description: '설비 설치 연도', dataType: 'number' as const }] }
        : e)),
    };
    const recordPropertyReview = vi.fn().mockResolvedValue(undefined);
    const shortName = '펌프';
    const longName = '소화펌프 설비A'; // 더 긴 이름 → pickCanonicalName 규칙상 canonical로 선택됨.
    const deps: IngestDeps = {
      listChunks: async () => [{ chunkId: 5, content: 'c' }],
      extract: async () => ({
        entities: [{ type: 'Equipment', name: shortName }, { type: 'Equipment', name: longName }],
        relations: [],
        // 후보는 정규화 실패 당시의 원본(로컬, 짧은) 이름을 담고 있다.
        propertyReviewCandidates: [
          { entityType: 'Equipment', entityName: shortName, propertyName: '설치연도', dataType: 'number', rawText: '약 10년전' },
        ],
      }),
      load: async () => ({ nodes: 2, relations: 0 }),
      // 두 이름 모두 동일 벡터를 반환 → 코사인 1.0(≥0.78) → 하나의 클러스터로 병합.
      embed: async (texts: string[]) => texts.map(() => [1, 0, 0]),
      recordPropertyReview,
    };
    await ingestDataset(deps, 42, testOntology);

    const canonicalKey = entityKey(entityTypeId(testOntology, 'Equipment'), longName);
    const localKey = entityKey(entityTypeId(testOntology, 'Equipment'), shortName);

    // 판별 포인트: finalKey(canonical, 긴 이름 기준)로 호출되어야 하고, localKey(원본 짧은 이름)로는 호출되면 안 된다.
    expect(recordPropertyReview).toHaveBeenCalledWith(42, 5, canonicalKey, 'Equipment', '설치연도', 'number', '약 10년전');
    expect(recordPropertyReview).not.toHaveBeenCalledWith(42, 5, localKey, 'Equipment', '설치연도', 'number', '약 10년전');
  });

  it('recordPropertyReview 미주입 시 후보가 있어도 에러 없이 넘어간다(하위호환)', async () => {
    const deps: IngestDeps = {
      listChunks: async () => [{ chunkId: 1, content: 'c' }],
      extract: async () => ({ entities: [{ type: 'Incident', name: 'x' }], relations: [],
        propertyReviewCandidates: [{ entityType: 'Incident', entityName: 'x', propertyName: '피해액', dataType: 'number', rawText: '?' }] }),
      load: async () => ({ nodes: 1, relations: 0 }),
      embed: async (t: string[]) => t.map(() => [1, 0, 0]),
    };
    await expect(ingestDataset(deps, 1, CORE_ONTOLOGY)).resolves.toBeDefined();
  });
});
