import { describe, it, expect } from 'vitest';
import { isEntityType, isRelationType, isAllowedTriple } from './ontology.js';

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
});
