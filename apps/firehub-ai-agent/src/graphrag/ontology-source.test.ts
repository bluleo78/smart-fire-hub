import { describe, it, expect, vi } from 'vitest';
import { resolveDatasetOntology } from './ontology-source.js';

// 백엔드 OntologyResponse 최소 형태 — deserializeOntology 가 받아들이는 모양.
const BOUND_RESPONSE = {
  domain: '건축물 안전점검',
  schemaVersion: 3,
  entities: [
    { id: 10, type: 'Inspection', description: '점검', naming: '표기 그대로', resolution: 'embedding', properties: [] },
  ],
  relations: [],
};

describe('resolveDatasetOntology', () => {
  it('바인딩이 있으면 바인딩된 온톨로지를 쓴다', async () => {
    const apiClient = {
      getDatasetOntology: vi.fn().mockResolvedValue({ datasetId: 7, ontologyId: 42 }),
      getOntologyById: vi.fn().mockResolvedValue(BOUND_RESPONSE),
    };
    const result = await resolveDatasetOntology(apiClient as never, 7);
    expect(result.ontologyId).toBe(42);
    expect(result.ontology.domain).toBe('건축물 안전점검');
    // getOntologyById 가 ontologyId(42)로 호출됐는지 확인 — datasetId(7)로 잘못 호출해도
    // mock 이 인자와 무관하게 같은 값을 돌려주므로, 인자 없는 단언만으로는 이 실수를 못 잡는다.
    expect(apiClient.getOntologyById).toHaveBeenCalledWith(42);
  });

  it('미바인딩 데이터셋은 명확한 에러로 거부한다', async () => {
    const apiClient = {
      getDatasetOntology: vi.fn().mockResolvedValue({ datasetId: 7, ontologyId: null }),
      getOntologyById: vi.fn(),
    };

    await expect(resolveDatasetOntology(apiClient as never, 7)).rejects.toThrow(/온톨로지에 연결되어 있지 않습니다/);
    expect(apiClient.getOntologyById).not.toHaveBeenCalled();
  });

  it('바인딩 조회 자체가 실패하면 폴백하지 않고 그대로 전파한다', async () => {
    const apiClient = {
      getDatasetOntology: vi.fn().mockRejectedValue(new Error('boom')),
      getOntologyById: vi.fn(),
    };

    await expect(resolveDatasetOntology(apiClient as never, 7)).rejects.toThrow('boom');
  });

  it('바인딩은 있는데 온톨로지 본체 조회가 실패하면 폴백하지 않고 throw 한다', async () => {
    // 여기서 조용히 넘어가면 이 함수가 막으려는 버그(엉뚱한 온톨로지로 적재)가
    // 오류 경로로 되살아난다. 적재가 실패하는 편이 잘못 적재되는 것보다 낫다.
    const apiClient = {
      getDatasetOntology: vi.fn().mockResolvedValue({ datasetId: 7, ontologyId: 42 }),
      getOntologyById: vi.fn().mockRejectedValue(new Error('boom')),
    };
    await expect(resolveDatasetOntology(apiClient as never, 7)).rejects.toThrow('boom');
  });
});
