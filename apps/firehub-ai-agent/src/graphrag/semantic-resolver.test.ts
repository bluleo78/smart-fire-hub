import { describe, it, expect } from 'vitest';
import { buildCanonicalMap, applyCanonicalMap, MERGE_THRESHOLD, EmbedFn } from './semantic-resolver.js';
import { entityKey, ResolvedEntity, ResolvedGraph } from './resolver.js';

// 이름 → 벡터 고정 매핑(Ollama 미사용). '스프링클러'/'스프링클러 설비'는 코사인 ≥0.78로 근접,
// '감지기'는 직교(0)로 명확히 별개 클러스터가 되도록 손으로 만든 값.
const VECTORS: Record<string, number[]> = {
  '스프링클러': [1, 0, 0],
  '스프링클러 설비': [0.99, 0.14, 0],
  '감지기': [0, 1, 0],
};

const mockEmbed: EmbedFn = async (texts: string[]) => texts.map((t) => VECTORS[t] ?? [0, 0, 1]);

describe('MERGE_THRESHOLD', () => {
  it('기본값 0.78', () => {
    expect(MERGE_THRESHOLD).toBe(0.78);
  });
});

describe('buildCanonicalMap', () => {
  it('같은 타입 내 근접 이름을 가장 긴 이름으로 병합하고, 직교 이름은 분리한다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey('Equipment', '스프링클러'), type: 'Equipment', name: '스프링클러' },
      { key: entityKey('Equipment', '스프링클러 설비'), type: 'Equipment', name: '스프링클러 설비' },
      { key: entityKey('Equipment', '감지기'), type: 'Equipment', name: '감지기' },
    ];
    const map = await buildCanonicalMap(entities, mockEmbed);

    const sprinklerKey = entityKey('Equipment', '스프링클러');
    const sprinklerFullKey = entityKey('Equipment', '스프링클러 설비');
    const detectorKey = entityKey('Equipment', '감지기');

    expect(map.get(sprinklerKey)?.name).toBe('스프링클러 설비');
    expect(map.get(sprinklerFullKey)?.name).toBe('스프링클러 설비');
    expect(map.get(sprinklerKey)?.key).toBe(map.get(sprinklerFullKey)?.key);
    expect(map.get(detectorKey)?.name).toBe('감지기');
    expect(map.get(detectorKey)?.key).not.toBe(map.get(sprinklerKey)?.key);
  });

  it('타입이 다르면 이름이 같아도 병합하지 않는다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey('Equipment', '스프링클러'), type: 'Equipment', name: '스프링클러' },
      { key: entityKey('Building', '스프링클러'), type: 'Building', name: '스프링클러' },
    ];
    const map = await buildCanonicalMap(entities, mockEmbed);
    expect(map.get(entityKey('Equipment', '스프링클러'))?.type).toBe('Equipment');
    expect(map.get(entityKey('Building', '스프링클러'))?.type).toBe('Building');
  });
});

describe('applyCanonicalMap', () => {
  it('엔티티/관계를 canonical 키로 재작성하고 중복을 제거한다', async () => {
    const eKey = entityKey('Equipment', '스프링클러');
    const eFullKey = entityKey('Equipment', '스프링클러 설비');
    const dKey = entityKey('Equipment', '감지기');
    const bKey = entityKey('Building', '중앙로 상가건물');

    const graph: ResolvedGraph = {
      entities: [
        { key: eKey, type: 'Equipment', name: '스프링클러' },
        { key: eFullKey, type: 'Equipment', name: '스프링클러 설비' },
        { key: dKey, type: 'Equipment', name: '감지기' },
        { key: bKey, type: 'Building', name: '중앙로 상가건물' },
      ],
      relations: [
        { subjectKey: bKey, type: 'HAS_EQUIPMENT', objectKey: eKey },
        { subjectKey: bKey, type: 'HAS_EQUIPMENT', objectKey: eFullKey },
      ],
    };
    const entities: ResolvedEntity[] = [
      { key: eKey, type: 'Equipment', name: '스프링클러' },
      { key: eFullKey, type: 'Equipment', name: '스프링클러 설비' },
      { key: dKey, type: 'Equipment', name: '감지기' },
      { key: bKey, type: 'Building', name: '중앙로 상가건물' },
    ];
    const map = await buildCanonicalMap(entities, mockEmbed);

    const result = applyCanonicalMap(graph, map);

    // 스프링클러/스프링클러 설비가 하나의 canonical 엔티티로 합쳐져 총 3개(canonical Equipment 2종 + Building)
    expect(result.entities).toHaveLength(3);
    expect(result.entities.map((e) => e.name).sort()).toEqual(['감지기', '스프링클러 설비', '중앙로 상가건물']);

    // 두 관계 모두 같은 canonical objectKey를 가리키므로 dedupe되어 1개만 남는다.
    expect(result.relations).toHaveLength(1);
    expect(result.relations[0].objectKey).toBe(entityKey('Equipment', '스프링클러 설비'));
    expect(result.relations[0].subjectKey).toBe(bKey);
  });

  it('map에 없는 키는 그대로 통과한다', () => {
    const graph: ResolvedGraph = {
      entities: [{ key: 'Equipment:foo', type: 'Equipment', name: 'foo' }],
      relations: [],
    };
    const result = applyCanonicalMap(graph, new Map());
    expect(result.entities).toEqual(graph.entities);
  });
});
