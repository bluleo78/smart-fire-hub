import { describe, it, expect } from 'vitest';
import { normalizeName, entityKey, resolveExtraction } from './resolver.js';

describe('resolver', () => {
  it('정규화는 공백/대소문자 변형을 하나로 만든다', () => {
    expect(normalizeName('  중앙로   상가 ')).toBe('중앙로 상가');
    expect(entityKey('Building', '중앙로  상가')).toBe(entityKey('Building', '중앙로 상가'));
  });
  it('정규화 변형 엔티티를 하나로 병합하고 관계를 키로 재작성한다', () => {
    const g = resolveExtraction({
      entities: [
        { type: 'Building', name: '중앙로 상가' },
        { type: 'Building', name: '중앙로  상가' },   // 공백 변형 → 병합
        { type: 'Incident', name: '2026-001' },
      ],
      relations: [
        { subject: '2026-001', type: 'OCCURRED_AT', object: '중앙로  상가' },
      ],
    });
    expect(g.entities).toHaveLength(2); // Building 1 + Incident 1
    expect(g.relations).toEqual([
      { subjectKey: entityKey('Incident', '2026-001'), type: 'OCCURRED_AT', objectKey: entityKey('Building', '중앙로 상가') },
    ]);
  });
});
