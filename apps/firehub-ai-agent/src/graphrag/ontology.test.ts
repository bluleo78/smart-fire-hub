import { describe, it, expect } from 'vitest';
import {
  isEntityType, isRelationType, isAllowedTriple, buildExtractionPrompt, CORE_ONTOLOGY,
  entityResolutionPolicy, serializeOntology, deserializeOntology, SerializedOntology,
} from './ontology.js';

describe('ontology', () => {
  it('유효 엔티티/관계 타입을 인식한다', () => {
    expect(isEntityType('Incident')).toBe(true);
    expect(isEntityType('Person')).toBe(false);
    expect(isRelationType('CAUSED_BY')).toBe(true);
    expect(isRelationType('KNOWS')).toBe(false);
  });
  it('허용된 트리플만 통과시킨다', () => {
    expect(isAllowedTriple(CORE_ONTOLOGY, 'Incident', 'CAUSED_BY', 'Cause')).toBe(true);
    expect(isAllowedTriple(CORE_ONTOLOGY, 'Building', 'HAS_EQUIPMENT', 'Equipment')).toBe(true);
    // 방향/조합이 온톨로지에 없으면 거부
    expect(isAllowedTriple(CORE_ONTOLOGY, 'Cause', 'CAUSED_BY', 'Incident')).toBe(false);
    expect(isAllowedTriple(CORE_ONTOLOGY, 'Building', 'CAUSED_BY', 'Cause')).toBe(false);
  });
  it('허용 트리플은 온톨로지 관계 정의에서 파생된다', () => {
    // CORE_ONTOLOGY.relations의 모든 관계는 isAllowedTriple로 허용되어야 한다(단일 소스).
    for (const r of CORE_ONTOLOGY.relations) {
      expect(isAllowedTriple(CORE_ONTOLOGY, r.subject, r.relation, r.object)).toBe(true);
    }
  });
  it('추출 프롬프트는 온톨로지 정의에서 생성된다(도메인 하드코딩 없음)', () => {
    const prompt = buildExtractionPrompt();
    // 도메인·모든 엔티티 타입·모든 관계 방향·고유 명명 규칙이 프롬프트에 반영되어야 한다.
    expect(prompt).toContain(CORE_ONTOLOGY.domain);
    for (const e of CORE_ONTOLOGY.entities) expect(prompt).toContain(e.type);
    for (const r of CORE_ONTOLOGY.relations) {
      expect(prompt).toContain(`${r.subject} -${r.relation}-> ${r.object}`);
    }
    expect(prompt).toContain('문서마다 고유해야 한다'); // Incident 고유 명명 규칙
  });
  it('다른 온톨로지를 주면 그 도메인으로 프롬프트가 바뀐다(도메인 무관 검증)', () => {
    const custom = { ...CORE_ONTOLOGY, domain: '의료 기록' };
    expect(buildExtractionPrompt(custom)).toContain('의료 기록');
    expect(buildExtractionPrompt(custom)).not.toContain('화재조사 보고서');
  });
  it('엔티티 타입별 해소(resolution) 정책 — 수치/고유 엔티티는 exact, 표기변형 엔티티는 embedding', () => {
    expect(entityResolutionPolicy(CORE_ONTOLOGY, 'Damage')).toBe('exact');
    expect(entityResolutionPolicy(CORE_ONTOLOGY, 'Incident')).toBe('exact');
    expect(entityResolutionPolicy(CORE_ONTOLOGY, 'Building')).toBe('embedding');
  });
});

describe('serializeOntology', () => {
  it('domain·entities·relations를 평문 형태로 직렬화한다', () => {
    const s = serializeOntology(CORE_ONTOLOGY);
    expect(s.domain).toBe('화재조사 보고서');
    expect(s.entities).toHaveLength(6);
    expect(s.entities[0]).toEqual({
      type: 'Incident', description: expect.any(String), naming: expect.any(String), resolution: 'exact',
      properties: [expect.objectContaining({ name: '피해액', dataType: 'number', unit: '원' })],
    });
    expect(s.relations).toHaveLength(6);
    expect(s.relations[0]).toEqual({ subject: 'Incident', relation: 'OCCURRED_AT', object: 'Building', description: expect.any(String) });
  });
});

describe('ontology 파라미터화 + 직렬화 왕복', () => {
  // wire 왕복(serialize→deserialize) 후 프롬프트가 원본과 바이트 동일해야 한다(소스 플립의 핵심 불변식).
  it('deserializeOntology 왕복 후 buildExtractionPrompt 가 바이트 동일하다', () => {
    const roundTripped = deserializeOntology(serializeOntology(CORE_ONTOLOGY));
    expect(buildExtractionPrompt(roundTripped)).toBe(buildExtractionPrompt(CORE_ONTOLOGY));
  });

  it('resolution 정책이 ontology 인자 기준으로 정확하다', () => {
    expect(entityResolutionPolicy(CORE_ONTOLOGY, 'Incident')).toBe('exact');
    expect(entityResolutionPolicy(CORE_ONTOLOGY, 'Building')).toBe('embedding');
  });

  it('isAllowedTriple 이 ontology 인자 기준으로 허용/차단한다', () => {
    expect(isAllowedTriple(CORE_ONTOLOGY, 'Incident', 'OCCURRED_AT', 'Building')).toBe(true);
    expect(isAllowedTriple(CORE_ONTOLOGY, 'Building', 'OCCURRED_AT', 'Incident')).toBe(false);
  });
});

describe('온톨로지 속성', () => {
  it('CORE_ONTOLOGY 의 Incident 는 피해액(number,원) 속성을 가진다', () => {
    const inc = CORE_ONTOLOGY.entities.find((e) => e.type === 'Incident')!;
    expect(inc.properties).toEqual([
      expect.objectContaining({ name: '피해액', dataType: 'number', unit: '원' }),
    ]);
  });

  it('deserializeOntology 는 wire properties 를 매핑한다', () => {
    const wire: SerializedOntology = {
      domain: 'd',
      schemaVersion: 2,
      entities: [{ type: 'Incident', description: 'x', naming: 'y', resolution: 'exact',
        properties: [{ name: '피해액', description: 'z', dataType: 'number', unit: '원' }] }],
      relations: [],
    };
    const ont = deserializeOntology(wire);
    expect(ont.entities[0].properties).toEqual([
      { name: '피해액', description: 'z', dataType: 'number', unit: '원' },
    ]);
  });

  it('buildExtractionPrompt 는 속성 있는 타입엔 속성 줄을, 없는 타입엔 없음(회귀)', () => {
    const prompt = buildExtractionPrompt(CORE_ONTOLOGY);
    expect(prompt).toContain('피해액');       // Incident 속성 렌더
    // Building 은 속성 없음 → 속성 헤더가 Building 블록에 붙지 않음(느슨한 회귀 확인)
    expect(prompt).toMatch(/Building:[^\n]*\n\s+· 명명:/);
  });

  it('deserializeOntology 는 schemaVersion 을 매핑하고, CORE_ONTOLOGY 는 1', () => {
    expect(CORE_ONTOLOGY.schemaVersion).toBe(1);
    const ont = deserializeOntology({ domain: 'd', schemaVersion: 5, entities: [], relations: [] } as SerializedOntology);
    expect(ont.schemaVersion).toBe(5);
  });
});
