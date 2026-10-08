import { describe, it, expect, vi } from 'vitest';
import { resolveDatasetOntology, resolveOntologyById, resolveReadableOntologyById } from './ontology-source.js';

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

// WD-28: 판정은 읽기 지점에서만 한다. 쓰기·스키마 경로(적재·표 투영·추론·describe·검수 반영 라우트)는 모두 아래 두
// 해소 함수를 쓰므로, 여기서 판정 API 미호출을 못박으면 쓰기 경로 전체가 판정 장애·왕복과 무관함이 보장된다.
describe('쓰기·스키마 해소는 그래프 읽기 판정을 묻지 않는다(WD-28)', () => {
  const writerClient = () => ({
    hasDelegatedUser: true,
    getDatasetOntology: vi.fn().mockResolvedValue({ datasetId: 7, ontologyId: 42 }),
    getOntologyById: vi.fn().mockResolvedValue(BOUND_RESPONSE),
    getOntologyGraphAccess: vi.fn().mockResolvedValue({ graphReadable: true }),
  });

  it('resolveOntologyById 는 getOntologyGraphAccess 를 호출하지 않는다', async () => {
    const apiClient = writerClient();
    const r = await resolveOntologyById(apiClient as never, 42);
    expect(r.ontologyId).toBe(42);
    expect(r).not.toHaveProperty('readable');
    expect(apiClient.getOntologyGraphAccess).not.toHaveBeenCalled();
  });

  it('resolveDatasetOntology 는 getOntologyGraphAccess 를 호출하지 않는다', async () => {
    const apiClient = writerClient();
    const r = await resolveDatasetOntology(apiClient as never, 7);
    expect(r.ontologyId).toBe(42);
    expect(apiClient.getOntologyGraphAccess).not.toHaveBeenCalled();
  });
});

describe('resolveReadableOntologyById — 그래프 읽기 판정(WD-28)', () => {
  const readableClient = (access: unknown) => ({
    hasDelegatedUser: true,
    getOntologyById: vi.fn().mockResolvedValue(BOUND_RESPONSE),
    getOntologyGraphAccess: vi.fn().mockResolvedValue(access),
  });

  it('graphReadable=true 면 읽기 타입을 발급한다', async () => {
    const apiClient = readableClient({ graphReadable: true });
    const r = await resolveReadableOntologyById(apiClient as never, 42);
    expect(r.ontologyId).toBe(42);
    expect(r.readable).toBe(42);
    expect(r.ontology.domain).toBe('건축물 안전점검');
    expect(apiClient.getOntologyGraphAccess).toHaveBeenCalledWith(42);
  });

  it("graphReadable=false 면 'restricted'(권한 없음)이다", async () => {
    const r = await resolveReadableOntologyById(readableClient({ graphReadable: false }) as never, 42);
    expect(r.ontologyId).toBe(42);
    expect(r.readable).toBe('restricted');
  });

  // api 가 답은 했는데 형식이 이상하면(구버전 필드 없음 등) 권한 없음으로 본다(fail-closed) — `=== true` 만 통과.
  it.each([[{}], [{ graphReadable: 'true' }], [{ graphReadable: 1 }], [null]])(
    "판정 응답이 %j 이면 'restricted' 다",
    async (access) => {
      const r = await resolveReadableOntologyById(readableClient(access) as never, 42);
      expect(r.readable).toBe('restricted');
    },
  );

  // 판정 조회 자체가 실패하면(네트워크·5xx·타임아웃·404) 읽기는 막되 '권한 없음'과 구분한다.
  it.each([['404'], ['Request failed with status code 503'], ['timeout of 10000ms exceeded'], ['ECONNREFUSED']])(
    "판정 조회 실패(%s)는 'unavailable' 이고 로그를 남긴다",
    async (message) => {
      const apiClient = {
        hasDelegatedUser: true,
        getOntologyById: vi.fn().mockResolvedValue(BOUND_RESPONSE),
        getOntologyGraphAccess: vi.fn().mockRejectedValue(new Error(message)),
      };
      const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
      const r = await resolveReadableOntologyById(apiClient as never, 42);
      expect(r.ontologyId).toBe(42);
      expect(r.readable).toBe('unavailable');
      // 무로그 실패 금지(#308) — 판정 실패는 로그로 남아야 한다.
      expect(errSpy).toHaveBeenCalled();
      errSpy.mockRestore();
    },
  );

  // 테넌트 경계(getOntologyById) 실패는 판정 결과로 뭉개지 않고 그대로 전파한다 — 판정도 묻지 않는다.
  it('온톨로지 해소가 실패하면 예외를 전파하고 판정을 묻지 않는다', async () => {
    const apiClient = { ...readableClient({ graphReadable: true }), getOntologyById: vi.fn().mockRejectedValue(new Error('없음')) };
    await expect(resolveReadableOntologyById(apiClient as never, 42)).rejects.toThrow('없음');
    expect(apiClient.getOntologyGraphAccess).not.toHaveBeenCalled();
  });

  // 사용자 신원(X-On-Behalf-Of) 없는 클라이언트에는 판정할 주체가 없다 — api 에 묻지도 않고 발급하지 않는다.
  it("대행 사용자가 없는 클라이언트는 판정을 묻지 않고 'restricted' 다", async () => {
    const apiClient = { ...readableClient({ graphReadable: true }), hasDelegatedUser: false };
    const r = await resolveReadableOntologyById(apiClient as never, 42);
    expect(r.readable).toBe('restricted');
    expect(apiClient.getOntologyGraphAccess).not.toHaveBeenCalled();
  });
});
