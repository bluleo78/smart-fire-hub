import { describe, it, expect } from 'vitest';
import { filterProposal, buildOntologyInferencePrompt, inferOntology } from './ontology-inference.js';
import type { ColumnProfile } from './column-profiler.js';

// 규칙 위반이 없는 최소 정상 제안 — 각 테스트가 여기서 한 가지씩만 어긋뜨린다.
function validRaw() {
  return {
    entities: [
      {
        type: 'Inspection',
        description: '점검 행위',
        naming: '표기 그대로',
        resolution: 'embedding',
        properties: [{ name: 'inspectedAt', description: '점검일', dataType: 'date', unit: null }],
      },
      {
        type: 'Building',
        description: '건축물',
        naming: '표기 그대로',
        resolution: 'embedding',
        properties: [],
      },
    ],
    relations: [
      { subject: 'Inspection', relation: 'TARGETS', object: 'Building', description: '점검 대상' },
    ],
  };
}

describe('filterProposal', () => {
  it('정상 제안은 그대로 통과한다', () => {
    const r = filterProposal(validRaw());
    expect(r.entities).toHaveLength(2);
    expect(r.relations).toHaveLength(1);
    expect(r.dropped).toHaveLength(0);
  });

  // 규칙 1: resolution 이 허용값 밖이면 embedding 으로 보정한다(드롭 아님).
  it('알 수 없는 resolution 은 embedding 으로 보정한다', () => {
    const raw = validRaw();
    raw.entities[0].resolution = 'fuzzy';
    const r = filterProposal(raw);
    expect(r.entities[0].resolution).toBe('embedding');
    expect(r.entities).toHaveLength(2);
  });

  // 규칙 2: dataType 이 3종 밖이면 그 속성만 드롭.
  it('허용되지 않은 dataType 속성만 드롭한다', () => {
    const raw = validRaw();
    raw.entities[0].properties[0].dataType = 'boolean';
    const r = filterProposal(raw);
    expect(r.entities[0].properties).toHaveLength(0);
    expect(r.entities).toHaveLength(2);
    expect(r.dropped).toContainEqual({ kind: 'property', detail: expect.stringContaining('dataType') });
  });

  // 규칙 3: UNIQUE(ontology_id, type) — 중복 엔티티 타입은 뒤엣것 드롭.
  it('중복 엔티티 타입은 뒤엣것을 드롭한다', () => {
    const raw = validRaw();
    raw.entities.push({ ...raw.entities[0], description: '중복' });
    const r = filterProposal(raw);
    expect(r.entities).toHaveLength(2);
    expect(r.dropped).toContainEqual({ kind: 'entity', detail: expect.stringContaining('중복 엔티티 타입') });
  });

  // 규칙 4: UNIQUE(entity_type_id, name) — 같은 엔티티 내 속성명 중복은 뒤엣것 드롭.
  it('같은 엔티티 내 중복 속성명은 뒤엣것을 드롭한다', () => {
    const raw = validRaw();
    raw.entities[0].properties.push({ name: 'inspectedAt', description: '중복', dataType: 'text', unit: null });
    const r = filterProposal(raw);
    expect(r.entities[0].properties).toHaveLength(1);
    expect(r.dropped).toContainEqual({ kind: 'property', detail: expect.stringContaining('중복 속성명') });
  });

  // 규칙 5: 예약어는 엔티티 타입명·속성명 양쪽에서 금지.
  it('예약어 속성명을 드롭한다', () => {
    const raw = validRaw();
    raw.entities[0].properties.push({ name: 'name', description: '예약어', dataType: 'text', unit: null });
    const r = filterProposal(raw);
    expect(r.entities[0].properties).toHaveLength(1);
    expect(r.dropped).toContainEqual({ kind: 'property', detail: expect.stringContaining('예약어') });
  });

  it('예약어 엔티티 타입명을 드롭한다', () => {
    const raw = validRaw();
    raw.entities.push({
      type: 'schemaVersion', description: '예약어', naming: '-', resolution: 'exact', properties: [],
    });
    const r = filterProposal(raw);
    expect(r.entities).toHaveLength(2);
    expect(r.dropped).toContainEqual({ kind: 'entity', detail: expect.stringContaining('예약어') });
  });

  // 규칙 6: 트리플의 subject/object 가 살아남은 엔티티 집합 밖이면 관계 드롭.
  it('제안 엔티티 밖을 가리키는 관계를 드롭한다', () => {
    const raw = validRaw();
    raw.relations.push({ subject: 'Inspection', relation: 'CITES', object: 'Regulation', description: '없는 대상' });
    const r = filterProposal(raw);
    expect(r.relations).toHaveLength(1);
    expect(r.dropped).toContainEqual({ kind: 'relation', detail: expect.stringContaining('Regulation') });
  });

  it('드롭된 엔티티를 가리키는 관계도 함께 드롭한다', () => {
    const raw = validRaw();
    raw.entities[1].type = 'name'; // 예약어라 Building 이 드롭됨
    const r = filterProposal(raw);
    expect(r.entities).toHaveLength(1);
    expect(r.relations).toHaveLength(0);
  });

  // 규칙 7: 백엔드 seenTriples 가 subject|relation|object 중복을 무조건 400 으로 거부하므로
  // 필터 단계에서 먼저 걸러야 한다. 설명만 다른 동일 트리플도 중복으로 본다.
  it('같은 트리플이 두 번 오면 하나만 남고 dropped 에 기록된다', () => {
    const raw = validRaw();
    raw.relations.push({ subject: 'Inspection', relation: 'TARGETS', object: 'Building', description: '설명만 다름' });
    const r = filterProposal(raw);
    expect(r.relations).toHaveLength(1);
    expect(r.dropped).toContainEqual({ kind: 'relation', detail: expect.stringContaining('중복 트리플') });
  });

  // str() 이 trim 하지 않으면 "Building "과 "Building"이 다른 타입으로 갈라져
  // 중복 판정(엔티티 타입·트리플)이 공백 차이만으로 무력화된다.
  it('앞뒤 공백이 있는 타입명은 trim 되어 같은 타입으로 취급된다', () => {
    const raw = validRaw();
    raw.entities[1].type = 'Building '; // 끝에 공백
    raw.relations[0].object = 'Building'; // 공백 없는 쪽을 참조
    const r = filterProposal(raw);
    // 공백 차이로 별개 타입이 되지 않았다면: Building 엔티티 1개만 남고,
    // 관계는 정상적으로 그 엔티티를 끝점으로 매칭된다(드롭되지 않음).
    expect(r.entities.filter((e) => e.type === 'Building')).toHaveLength(1);
    expect(r.relations).toHaveLength(1);
    expect(r.relations[0].object).toBe('Building');
  });

  // 규칙 8: 엔티티 0개는 호출부가 저장하지 않도록 빈 결과로 표면화.
  it('형식이 깨진 입력은 빈 결과를 낸다', () => {
    expect(filterProposal(null).entities).toHaveLength(0);
    expect(filterProposal({ entities: 'nope' }).entities).toHaveLength(0);
  });

  it('필수 문자열 필드가 없는 엔티티를 드롭한다', () => {
    const raw = validRaw();
    (raw.entities[0] as { type?: string }).type = undefined;
    const r = filterProposal(raw);
    expect(r.entities).toHaveLength(1);
    expect(r.dropped).toContainEqual({ kind: 'entity', detail: expect.stringContaining('type') });
  });
});

describe('buildOntologyInferencePrompt', () => {
  const profile: ColumnProfile = {
    columnName: 'bld_name', dataType: 'VARCHAR', ontologyDataType: 'text',
    distinctCount: 120, nullRatio: 0.01, cardinalityRatio: 0.9,
    sampleValues: ['○○아파트', '△△빌딩'], isPrimaryKey: false,
  };
  const geom: ColumnProfile = { ...profile, columnName: 'geom', dataType: 'GEOMETRY', ontologyDataType: null };

  it('표 컬럼 프로파일과 문서 청크를 모두 담는다', () => {
    const p = buildOntologyInferencePrompt('건축물 안전점검', '점검 이력 중심', [
      { datasetId: 1, name: '건축물대장', kind: 'table', profiles: [profile] },
      { datasetId: 2, name: '점검보고서', kind: 'document', chunks: ['2026년 3월 정기점검 결과...'] },
    ]);
    expect(p).toContain('건축물 안전점검');
    expect(p).toContain('점검 이력 중심');
    expect(p).toContain('bld_name');
    expect(p).toContain('2026년 3월 정기점검 결과');
  });

  it('ontologyDataType 이 null 인 컬럼은 제외한다', () => {
    const p = buildOntologyInferencePrompt('d', undefined, [
      { datasetId: 1, name: 't', kind: 'table', profiles: [profile, geom] },
    ]);
    expect(p).not.toContain('geom');
  });

  it('제약(예약어·dataType·resolution)을 프롬프트에 명시한다', () => {
    const p = buildOntologyInferencePrompt('d', undefined, []);
    expect(p).toContain('sourceChunkIds');
    expect(p).toContain('embedding');
    expect(p).toContain('date');
  });
});

describe('inferOntology', () => {
  it('LLM 출력의 JSON 블록을 파싱해 필터를 적용한다', async () => {
    const complete = async () => '```json\n' + JSON.stringify(validRaw()) + '\n```';
    const r = await inferOntology({ complete }, 'd', undefined, []);
    expect(r.entities).toHaveLength(2);
  });

  it('LLM 호출이 실패하면 빈 결과를 반환한다(throw 하지 않는다)', async () => {
    const complete = async () => { throw new Error('cli down'); };
    const r = await inferOntology({ complete }, 'd', undefined, []);
    expect(r.entities).toHaveLength(0);
    expect(r.relations).toHaveLength(0);
  });

  it('JSON 파싱 실패 시 빈 결과를 반환한다', async () => {
    const complete = async () => '설명만 있고 JSON 이 없습니다';
    const r = await inferOntology({ complete }, 'd', undefined, []);
    expect(r.entities).toHaveLength(0);
  });

  it('실패 결과는 호출마다 새 객체다(공유 상수 오염 방지)', async () => {
    const complete = async () => { throw new Error('cli down'); };
    const a = await inferOntology({ complete }, 'd', undefined, []);
    const b = await inferOntology({ complete }, 'd', undefined, []);
    expect(a).not.toBe(b);
    expect(a.entities).not.toBe(b.entities);
    a.entities.push({ type: 'X', description: '', naming: '', resolution: 'exact', properties: [] });
    expect(b.entities).toHaveLength(0);
  });
});
