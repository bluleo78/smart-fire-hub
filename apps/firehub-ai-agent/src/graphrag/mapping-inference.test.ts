// mapping-inference 단위 테스트 — complete 스텁 주입, conformance 필터(규칙 2~6) 및 드롭 검증.
import { describe, it, expect } from 'vitest';
import { inferMapping, InferMappingDeps } from './mapping-inference.js';
import type { Ontology } from './ontology.js';
import type { ColumnProfile } from './column-profiler.js';

// 최소 온톨로지: 엔티티 Building(id 속성 name·address), Inspection, 허용 트리플 Building-HAS_INSPECTION->Inspection.
const ontology: Ontology = {
  domain: 'fire', schemaVersion: 1,
  entities: [
    { type: 'Building', description: '', naming: '', resolution: 'exact', id: 1,
      properties: [
        { name: 'address', description: '', dataType: 'text' },
        { name: 'loss', description: '', dataType: 'number' }, // 규칙6 타입 불일치 검증용
      ] },
    { type: 'Inspection', description: '', naming: '', resolution: 'exact', id: 2, properties: [] },
  ],
  relations: [{ subject: 'Building', relation: 'HAS_INSPECTION', object: 'Inspection', description: '' }],
};

const profiles: ColumnProfile[] = [
  { columnName: 'bld_name', dataType: 'VARCHAR', ontologyDataType: 'text', distinctCount: 2, nullRatio: 0, cardinalityRatio: 1, sampleValues: [], isPrimaryKey: false },
  { columnName: 'addr', dataType: 'VARCHAR', ontologyDataType: 'text', distinctCount: 2, nullRatio: 0, cardinalityRatio: 1, sampleValues: [], isPrimaryKey: false },
  { columnName: 'insp_id', dataType: 'INTEGER', ontologyDataType: 'number', distinctCount: 2, nullRatio: 0, cardinalityRatio: 1, sampleValues: [], isPrimaryKey: true },
];

// LLM 응답을 고정 문자열로 돌려주는 스텁 complete.
function stub(json: unknown): InferMappingDeps {
  return { complete: async () => '```json\n' + JSON.stringify(json) + '\n```' };
}

describe('inferMapping', () => {
  it('적합한 제안을 MappingSpec으로 통과시킨다', async () => {
    const res = await inferMapping(stub({
      entities: [
        { entityType: 'Building', nameColumn: 'bld_name', properties: [{ column: 'addr', propertyName: 'address' }], confidence: 0.9 },
        { entityType: 'Inspection', nameColumn: 'insp_id', properties: [], confidence: 0.8 },
      ],
      relations: [{ subjectRef: 0, relation: 'HAS_INSPECTION', objectRef: 1, confidence: 0.7 }],
    }), ontology, profiles);
    expect(res.spec.entities).toHaveLength(2);
    expect(res.spec.entities[0]).toEqual({ entityType: 'Building', nameColumn: 'bld_name', properties: [{ column: 'addr', propertyName: 'address' }] });
    expect(res.spec.relations).toEqual([{ subjectRef: 0, relation: 'HAS_INSPECTION', objectRef: 1 }]);
    expect(res.confidences.length).toBe(3);
  });

  it('규칙2: 알 수 없는 entityType 엔티티를 버린다', async () => {
    const res = await inferMapping(stub({ entities: [{ entityType: 'Ghost', nameColumn: 'bld_name', properties: [] }], relations: [] }), ontology, profiles);
    expect(res.spec.entities).toHaveLength(0);
    expect(res.dropped.some((d) => d.kind === 'entity')).toBe(true);
  });

  it('규칙3: 없는 nameColumn 엔티티를 버린다', async () => {
    const res = await inferMapping(stub({ entities: [{ entityType: 'Building', nameColumn: 'nope', properties: [] }], relations: [] }), ontology, profiles);
    expect(res.spec.entities).toHaveLength(0);
  });

  it('규칙4: 미정의 속성/없는 컬럼 속성만 버리고 엔티티는 유지', async () => {
    const res = await inferMapping(stub({
      entities: [{ entityType: 'Building', nameColumn: 'bld_name', properties: [
        { column: 'addr', propertyName: 'nonexistent' }, // 미정의 속성
        { column: 'nope', propertyName: 'address' },       // 없는 컬럼
      ] }],
      relations: [],
    }), ontology, profiles);
    expect(res.spec.entities).toHaveLength(1);
    expect(res.spec.entities[0].properties).toHaveLength(0);
    expect(res.dropped.filter((d) => d.kind === 'property')).toHaveLength(2);
  });

  it('규칙6: number 속성에 문자열 컬럼을 붙인 제안만 버리고 나머지는 유지 (#324)', async () => {
    const res = await inferMapping(stub({
      entities: [{ entityType: 'Building', nameColumn: 'bld_name', properties: [
        { column: 'addr', propertyName: 'loss' },       // VARCHAR(text) → number 속성: 드롭
        { column: 'addr', propertyName: 'address' },    // text → text: 유지
      ] }],
      relations: [],
    }), ontology, profiles);
    expect(res.spec.entities[0].properties).toEqual([{ column: 'addr', propertyName: 'address' }]);
    expect(res.dropped.some((d) => d.detail.includes('타입 불일치'))).toBe(true);
  });

  it('규칙6: number 속성에 숫자 컬럼은 통과시킨다 (#324)', async () => {
    const res = await inferMapping(stub({
      entities: [{ entityType: 'Building', nameColumn: 'bld_name', properties: [
        { column: 'insp_id', propertyName: 'loss' },
      ] }],
      relations: [],
    }), ontology, profiles);
    expect(res.spec.entities[0].properties).toEqual([{ column: 'insp_id', propertyName: 'loss' }]);
    expect(res.dropped).toHaveLength(0);
  });

  it('규칙5b: 허용되지 않은 트리플 관계를 버린다', async () => {
    const res = await inferMapping(stub({
      entities: [
        { entityType: 'Building', nameColumn: 'bld_name', properties: [] },
        { entityType: 'Inspection', nameColumn: 'insp_id', properties: [] },
      ],
      relations: [{ subjectRef: 1, relation: 'HAS_INSPECTION', objectRef: 0 }], // 방향 반대 → 미허용
    }), ontology, profiles);
    expect(res.spec.relations).toHaveLength(0);
    expect(res.dropped.some((d) => d.kind === 'relation')).toBe(true);
  });

  it('규칙5a: 드롭된 엔티티를 참조하던 관계도 버리고, 남은 관계 ref를 새 인덱스로 재계산', async () => {
    // index 0 Ghost(드롭), 1 Building, 2 Inspection. 관계 1->2는 유지되며 ref는 0->1로 재계산.
    const res = await inferMapping(stub({
      entities: [
        { entityType: 'Ghost', nameColumn: 'bld_name', properties: [] },       // 드롭
        { entityType: 'Building', nameColumn: 'bld_name', properties: [] },     // 새 인덱스 0
        { entityType: 'Inspection', nameColumn: 'insp_id', properties: [] },    // 새 인덱스 1
      ],
      relations: [
        { subjectRef: 0, relation: 'HAS_INSPECTION', objectRef: 2 }, // Ghost 참조 → 드롭
        { subjectRef: 1, relation: 'HAS_INSPECTION', objectRef: 2 }, // 유지 → {0, 1}로 재계산
      ],
    }), ontology, profiles);
    expect(res.spec.entities).toHaveLength(2);
    expect(res.spec.relations).toEqual([{ subjectRef: 0, relation: 'HAS_INSPECTION', objectRef: 1 }]);
  });

  it('LLM 호출 실패 → 빈 결과', async () => {
    const res = await inferMapping({ complete: async () => { throw new Error('down'); } }, ontology, profiles);
    expect(res.spec).toEqual({ entities: [], relations: [] });
  });

  it('JSON 파싱 실패 → 빈 결과', async () => {
    const res = await inferMapping({ complete: async () => 'not json at all' }, ontology, profiles);
    expect(res.spec).toEqual({ entities: [], relations: [] });
  });
});
