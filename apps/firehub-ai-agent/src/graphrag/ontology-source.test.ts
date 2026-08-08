import { describe, it, expect, vi } from 'vitest';
import { loadOntology, resolveDatasetOntology } from './ontology-source.js';
import { CORE_ONTOLOGY, serializeOntology, buildExtractionPrompt } from './ontology.js';

describe('loadOntology', () => {
  it('fetch 성공 시 wire 온톨로지를 역직렬화해 반환한다(프롬프트 바이트 동일)', async () => {
    const apiClient = { getOntology: vi.fn().mockResolvedValue(serializeOntology(CORE_ONTOLOGY)) };
    const ontology = await loadOntology(apiClient as never);
    expect(buildExtractionPrompt(ontology)).toBe(buildExtractionPrompt(CORE_ONTOLOGY));
  });

  it('fetch 실패 시 번들 CORE_ONTOLOGY 로 폴백한다', async () => {
    const apiClient = { getOntology: vi.fn().mockRejectedValue(new Error('api down')) };
    const ontology = await loadOntology(apiClient as never);
    expect(ontology).toBe(CORE_ONTOLOGY);
  });
});

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
      getOntology: vi.fn(),
    };
    const result = await resolveDatasetOntology(apiClient as never, 7);
    expect(result.source).toBe('binding');
    expect(result.ontologyId).toBe(42);
    expect(result.ontology.domain).toBe('건축물 안전점검');
    expect(apiClient.getOntology).not.toHaveBeenCalled();
    // getOntologyById 가 ontologyId(42)로 호출됐는지 확인 — datasetId(7)로 잘못 호출해도
    // mock 이 인자와 무관하게 같은 값을 돌려주므로, 인자 없는 단언만으로는 이 실수를 못 잡는다.
    expect(apiClient.getOntologyById).toHaveBeenCalledWith(42);
  });

  it('바인딩이 없으면 기본 온톨로지(GET /ontology)로 폴백한다', async () => {
    const apiClient = {
      getDatasetOntology: vi.fn().mockResolvedValue({ datasetId: 7, ontologyId: null }),
      getOntologyById: vi.fn(),
      getOntology: vi.fn().mockResolvedValue(BOUND_RESPONSE),
    };
    const result = await resolveDatasetOntology(apiClient as never, 7);
    expect(result.source).toBe('default');
    expect(result.ontologyId).toBeNull();
    expect(apiClient.getOntologyById).not.toHaveBeenCalled();
  });

  it('바인딩은 있는데 온톨로지 본체 조회가 실패하면 폴백하지 않고 throw 한다', async () => {
    // 여기서 기본 온톨로지로 조용히 내려가면 이 함수가 고치려던 버그(id=1 스키마로 적재)가
    // 오류 경로로 되살아난다. 적재가 실패하는 편이 잘못 적재되는 것보다 낫다.
    const apiClient = {
      getDatasetOntology: vi.fn().mockResolvedValue({ datasetId: 7, ontologyId: 42 }),
      getOntologyById: vi.fn().mockRejectedValue(new Error('boom')),
      getOntology: vi.fn(),
    };
    await expect(resolveDatasetOntology(apiClient as never, 7)).rejects.toThrow('boom');
    expect(apiClient.getOntology).not.toHaveBeenCalled();
  });

  it('바인딩 조회가 실패해도 기본 온톨로지로 진행한다', async () => {
    const apiClient = {
      getDatasetOntology: vi.fn().mockRejectedValue(new Error('boom')),
      getOntologyById: vi.fn(),
      getOntology: vi.fn().mockResolvedValue(BOUND_RESPONSE),
    };
    const result = await resolveDatasetOntology(apiClient as never, 7);
    expect(result.source).toBe('default');
  });

  it('모든 조회가 실패하면 번들 CORE_ONTOLOGY 로 폴백한다', async () => {
    const apiClient = {
      getDatasetOntology: vi.fn().mockRejectedValue(new Error('boom')),
      getOntologyById: vi.fn(),
      getOntology: vi.fn().mockRejectedValue(new Error('down')),
    };
    const result = await resolveDatasetOntology(apiClient as never, 7);
    expect(result.source).toBe('bundled-fallback');
    expect(result.ontology).toBe(CORE_ONTOLOGY);
  });
});
