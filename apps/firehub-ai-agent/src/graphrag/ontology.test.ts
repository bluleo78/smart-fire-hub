import { describe, it, expect } from 'vitest';
import {
  isEntityType, isRelationType, isAllowedTriple, buildExtractionPrompt, CORE_ONTOLOGY, ONTOLOGY_TRIPLES,
} from './ontology.js';

describe('ontology', () => {
  it('유효 엔티티/관계 타입을 인식한다', () => {
    expect(isEntityType('Incident')).toBe(true);
    expect(isEntityType('Person')).toBe(false);
    expect(isRelationType('CAUSED_BY')).toBe(true);
    expect(isRelationType('KNOWS')).toBe(false);
  });
  it('허용된 트리플만 통과시킨다', () => {
    expect(isAllowedTriple('Incident', 'CAUSED_BY', 'Cause')).toBe(true);
    expect(isAllowedTriple('Building', 'HAS_EQUIPMENT', 'Equipment')).toBe(true);
    // 방향/조합이 온톨로지에 없으면 거부
    expect(isAllowedTriple('Cause', 'CAUSED_BY', 'Incident')).toBe(false);
    expect(isAllowedTriple('Building', 'CAUSED_BY', 'Cause')).toBe(false);
  });
  it('허용 트리플은 온톨로지 관계 정의에서 파생된다', () => {
    // CORE_ONTOLOGY.relations와 ONTOLOGY_TRIPLES가 항상 일치해야 한다(단일 소스).
    expect(ONTOLOGY_TRIPLES).toHaveLength(CORE_ONTOLOGY.relations.length);
    for (const r of CORE_ONTOLOGY.relations) {
      expect(isAllowedTriple(r.subject, r.relation, r.object)).toBe(true);
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
});
