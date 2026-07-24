import { describe, it, expect, vi } from 'vitest';
import { buildCanonicalMap, applyCanonicalMap, MERGE_THRESHOLD, EmbedFn } from './semantic-resolver.js';
import { entityKey, ResolvedEntity, ResolvedGraph } from './resolver.js';
import { CORE_ONTOLOGY, entityTypeId } from './ontology.js';

// 5-6: entityKey는 typeId 기반 — CORE_ONTOLOGY의 고정 id를 조회해 사용한다.
const equipmentId = entityTypeId(CORE_ONTOLOGY, 'Equipment');
const buildingId = entityTypeId(CORE_ONTOLOGY, 'Building');
const damageId = entityTypeId(CORE_ONTOLOGY, 'Damage');
const causeId = entityTypeId(CORE_ONTOLOGY, 'Cause');

// 이름 → 벡터 고정 매핑(Ollama 미사용). '스프링클러'/'스프링클러 설비'는 코사인 ≥0.78로 근접,
// '감지기'는 직교(0)로 명확히 별개 클러스터가 되도록 손으로 만든 값.
const VECTORS: Record<string, number[]> = {
  '스프링클러': [1, 0, 0],
  '스프링클러 설비': [0.99, 0.14, 0],
  '감지기': [0, 1, 0],
  // Damage 타입 테스트용 — mock embed로는 threshold(0.78) 이상으로 근접하게 만들어
  // "값에 관계없이 exact 정책이 클러스터링 자체를 건너뛴다"는 것을 검증한다.
  '재산피해 약 1.2억원': [1, 0, 0],
  '재산피해 약 4.5억원': [0.99, 0.14, 0],
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
      { key: entityKey(equipmentId, '스프링클러'), type: 'Equipment', name: '스프링클러' },
      { key: entityKey(equipmentId, '스프링클러 설비'), type: 'Equipment', name: '스프링클러 설비' },
      { key: entityKey(equipmentId, '감지기'), type: 'Equipment', name: '감지기' },
    ];
    const map = await buildCanonicalMap(entities, mockEmbed, CORE_ONTOLOGY);

    const sprinklerKey = entityKey(equipmentId, '스프링클러');
    const sprinklerFullKey = entityKey(equipmentId, '스프링클러 설비');
    const detectorKey = entityKey(equipmentId, '감지기');

    expect(map.get(sprinklerKey)?.name).toBe('스프링클러 설비');
    expect(map.get(sprinklerFullKey)?.name).toBe('스프링클러 설비');
    expect(map.get(sprinklerKey)?.key).toBe(map.get(sprinklerFullKey)?.key);
    expect(map.get(detectorKey)?.name).toBe('감지기');
    expect(map.get(detectorKey)?.key).not.toBe(map.get(sprinklerKey)?.key);
  });

  it('타입이 다르면 이름이 같아도 병합하지 않는다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(equipmentId, '스프링클러'), type: 'Equipment', name: '스프링클러' },
      { key: entityKey(buildingId, '스프링클러'), type: 'Building', name: '스프링클러' },
    ];
    const map = await buildCanonicalMap(entities, mockEmbed, CORE_ONTOLOGY);
    expect(map.get(entityKey(equipmentId, '스프링클러'))?.type).toBe('Equipment');
    expect(map.get(entityKey(buildingId, '스프링클러'))?.type).toBe('Building');
  });

  it("resolution='exact' 타입(Damage)은 mock embed가 threshold 이상을 반환해도 병합하지 않는다", async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(damageId, '재산피해 약 1.2억원'), type: 'Damage', name: '재산피해 약 1.2억원' },
      { key: entityKey(damageId, '재산피해 약 4.5억원'), type: 'Damage', name: '재산피해 약 4.5억원' },
    ];
    const map = await buildCanonicalMap(entities, mockEmbed, CORE_ONTOLOGY);

    const key1 = entityKey(damageId, '재산피해 약 1.2억원');
    const key2 = entityKey(damageId, '재산피해 약 4.5억원');
    // 서로 다른 canonical로 남아 두 개의 별개 엔티티를 유지해야 한다.
    expect(map.get(key1)?.key).toBe(key1);
    expect(map.get(key2)?.key).toBe(key2);
    expect(map.get(key1)?.key).not.toBe(map.get(key2)?.key);
  });

  it("resolution='embedding' 타입(Equipment)은 근접 이름을 그대로 병합한다(회귀 확인)", async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(equipmentId, '스프링클러'), type: 'Equipment', name: '스프링클러' },
      { key: entityKey(equipmentId, '스프링클러 설비'), type: 'Equipment', name: '스프링클러 설비' },
    ];
    const map = await buildCanonicalMap(entities, mockEmbed, CORE_ONTOLOGY);
    expect(map.get(entityKey(equipmentId, '스프링클러'))?.key)
      .toBe(map.get(entityKey(equipmentId, '스프링클러 설비'))?.key);
  });

  it('근접쌍(코사인 0.5~0.78)에서 link 가 same:true 를 반환해도 recordPending 미주입 시에는 그냥 호출만 되고 병합하지 않는다(HITL)', async () => {
    // 코사인 유사도 계산: dot(a,b)/(|a||b|). [1,0,0] vs [0.6,0.6,0] → 0.6/(1*0.849) ≈ 0.707 (0.5~0.78 구간).
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '분전반의 누전'), type: 'Cause', name: '분전반의 누전' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) =>
      (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]));
    const link = vi.fn().mockResolvedValue({ same: true, rationale: '동일 원인' });
    const map = await buildCanonicalMap(entities, embed, CORE_ONTOLOGY, MERGE_THRESHOLD, link);

    expect(link).toHaveBeenCalledWith('전기적 요인', '분전반의 누전', 'Cause');
    // HITL: same:true여도 recordPending 미주입 시 union하지 않는다(병합 보류) — typeId 기반 key로 검증.
    expect(map.get(entityKey(causeId, '전기적 요인'))?.key)
      .not.toBe(map.get(entityKey(causeId, '분전반의 누전'))?.key);
  });

  it('근접쌍에서 link 가 same:false 를 반환하면 병합하지 않는다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '분전반의 누전'), type: 'Cause', name: '분전반의 누전' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) =>
      (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]));
    const link = vi.fn().mockResolvedValue({ same: false, rationale: '' });
    const map = await buildCanonicalMap(entities, embed, CORE_ONTOLOGY, MERGE_THRESHOLD, link);

    expect(map.get(entityKey(causeId, '전기적 요인'))?.key)
      .not.toBe(map.get(entityKey(causeId, '분전반의 누전'))?.key);
  });

  it('코사인 0.5 미만 쌍은 link 가 주입되어 있어도 호출하지 않는다', async () => {
    // 직교 벡터 → 코사인 0.
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '누수'), type: 'Cause', name: '누수' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) => (t === '전기적 요인' ? [1, 0, 0] : [0, 1, 0]));
    const link = vi.fn().mockResolvedValue({ same: true, rationale: '' });
    await buildCanonicalMap(entities, embed, CORE_ONTOLOGY, MERGE_THRESHOLD, link);

    expect(link).not.toHaveBeenCalled();
  });

  it('코사인 0.78 이상 쌍은 link 가 주입되어 있어도 호출하지 않고 바로 병합한다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(equipmentId, '스프링클러'), type: 'Equipment', name: '스프링클러' },
      { key: entityKey(equipmentId, '스프링클러 설비'), type: 'Equipment', name: '스프링클러 설비' },
    ];
    const link = vi.fn().mockResolvedValue({ same: false, rationale: '' });
    const map = await buildCanonicalMap(entities, mockEmbed, CORE_ONTOLOGY, MERGE_THRESHOLD, link);

    expect(link).not.toHaveBeenCalled();
    expect(map.get(entityKey(equipmentId, '스프링클러'))?.key)
      .toBe(map.get(entityKey(equipmentId, '스프링클러 설비'))?.key);
  });

  it('link 미주입 시 기존 동작과 동일하다(근접쌍이 있어도 병합하지 않음)', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '분전반의 누전'), type: 'Cause', name: '분전반의 누전' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) =>
      (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]));
    const map = await buildCanonicalMap(entities, embed, CORE_ONTOLOGY);

    expect(map.get(entityKey(causeId, '전기적 요인'))?.key)
      .not.toBe(map.get(entityKey(causeId, '분전반의 누전'))?.key);
  });

  it('lookupDecision이 approved를 반환하면 link를 호출하지 않고 바로 병합한다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '분전반의 누전'), type: 'Cause', name: '분전반의 누전' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) => (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]));
    const link = vi.fn();
    const lookupDecision = vi.fn().mockResolvedValue('approved');
    const map = await buildCanonicalMap(entities, embed, CORE_ONTOLOGY, MERGE_THRESHOLD, link, lookupDecision);

    expect(link).not.toHaveBeenCalled();
    expect(map.get(entityKey(causeId, '전기적 요인'))?.key).toBe(map.get(entityKey(causeId, '분전반의 누전'))?.key);
  });

  it('lookupDecision이 rejected를 반환하면 link를 호출하지 않고 병합하지 않는다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '분전반의 누전'), type: 'Cause', name: '분전반의 누전' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) => (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]));
    const link = vi.fn();
    const lookupDecision = vi.fn().mockResolvedValue('rejected');
    const map = await buildCanonicalMap(entities, embed, CORE_ONTOLOGY, MERGE_THRESHOLD, link, lookupDecision);

    expect(link).not.toHaveBeenCalled();
    expect(map.get(entityKey(causeId, '전기적 요인'))?.key).not.toBe(map.get(entityKey(causeId, '분전반의 누전'))?.key);
  });

  it('결정이 없고(undefined) link가 same:true를 반환해도 union하지 않고 recordPending만 호출한다(HITL 보류)', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '분전반의 누전'), type: 'Cause', name: '분전반의 누전' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) => (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]));
    const link = vi.fn().mockResolvedValue({ same: true, rationale: '동의어로 보임' });
    const lookupDecision = vi.fn().mockResolvedValue(undefined);
    const recordPending = vi.fn().mockResolvedValue(undefined);
    const map = await buildCanonicalMap(entities, embed, CORE_ONTOLOGY, MERGE_THRESHOLD, link, lookupDecision, recordPending);

    expect(map.get(entityKey(causeId, '전기적 요인'))?.key).not.toBe(map.get(entityKey(causeId, '분전반의 누전'))?.key);
    expect(recordPending).toHaveBeenCalledWith('전기적 요인', '분전반의 누전', 'Cause', expect.any(Number), '동의어로 보임', undefined, []);
  });

  it('link가 same:false를 반환하면 recordPending을 호출하지 않는다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '분전반의 누전'), type: 'Cause', name: '분전반의 누전' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) => (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]));
    const link = vi.fn().mockResolvedValue({ same: false, rationale: '' });
    const recordPending = vi.fn();
    await buildCanonicalMap(entities, embed, CORE_ONTOLOGY, MERGE_THRESHOLD, link, undefined, recordPending);

    expect(recordPending).not.toHaveBeenCalled();
  });

  it('lookupDecision이 실패(reject)해도 LLM 호출로 폴백한다', async () => {
    const entities: ResolvedEntity[] = [
      { key: entityKey(causeId, '전기적 요인'), type: 'Cause', name: '전기적 요인' },
      { key: entityKey(causeId, '분전반의 누전'), type: 'Cause', name: '분전반의 누전' },
    ];
    const embed: EmbedFn = async (texts) => texts.map((t) => (t === '전기적 요인' ? [1, 0, 0] : [0.6, 0.6, 0]));
    const link = vi.fn().mockResolvedValue({ same: false, rationale: '' });
    const lookupDecision = vi.fn().mockRejectedValue(new Error('network error'));
    await buildCanonicalMap(entities, embed, CORE_ONTOLOGY, MERGE_THRESHOLD, link, lookupDecision);

    expect(link).toHaveBeenCalled();
  });
});

describe('applyCanonicalMap', () => {
  it('엔티티/관계를 canonical 키로 재작성하고 중복을 제거한다', async () => {
    const eKey = entityKey(equipmentId, '스프링클러');
    const eFullKey = entityKey(equipmentId, '스프링클러 설비');
    const dKey = entityKey(equipmentId, '감지기');
    const bKey = entityKey(buildingId, '중앙로 상가건물');

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
    const map = await buildCanonicalMap(entities, mockEmbed, CORE_ONTOLOGY);

    const result = applyCanonicalMap(graph, map);

    // 스프링클러/스프링클러 설비가 하나의 canonical 엔티티로 합쳐져 총 3개(canonical Equipment 2종 + Building)
    expect(result.entities).toHaveLength(3);
    expect(result.entities.map((e) => e.name).sort()).toEqual(['감지기', '스프링클러 설비', '중앙로 상가건물']);

    // 두 관계 모두 같은 canonical objectKey를 가리키므로 dedupe되어 1개만 남는다.
    expect(result.relations).toHaveLength(1);
    expect(result.relations[0].objectKey).toBe(entityKey(equipmentId, '스프링클러 설비'));
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
