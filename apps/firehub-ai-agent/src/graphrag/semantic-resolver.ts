// 임베딩 기반 시맨틱 엔티티 해소 — resolver.ts의 정확 문자열 병합을 보완한다.
// 같은 EntityType 내에서 표기가 달라도 의미가 같은 엔티티(예: "스프링클러" vs "스프링클러 설비")를
// 코사인 유사도로 클러스터링해 하나의 canonical 엔티티로 합친다.
import { EntityType, entityResolutionPolicy } from './ontology.js';
import { entityKey, ResolvedEntity, ResolvedGraph } from './resolver.js';
import { cosineSimilarity } from './embedding.js';

// bge-m3 실측 검증값: 변형 표기(동의어) ≥0.78, 서로 다른 엔티티는 ≤0.755로 명확히 분리됨.
export const MERGE_THRESHOLD = 0.78;

// 텍스트 배열 → 벡터 배열. 실제 구현은 embedding.ts의 embedTexts를 주입하고,
// 테스트에서는 결정적 mock을 주입해 Ollama 의존 없이 검증한다.
export interface EmbedFn { (texts: string[]): Promise<number[][]>; }

// 이름들 사이에서 union-find로 유사도 클러스터를 찾는다.
function clusterNames(names: string[], vectors: number[][], threshold: number): string[][] {
  const parent = names.map((_, i) => i);
  function find(i: number): number {
    if (parent[i] !== i) parent[i] = find(parent[i]);
    return parent[i];
  }
  function union(i: number, j: number): void {
    const ri = find(i), rj = find(j);
    if (ri !== rj) parent[ri] = rj;
  }
  for (let i = 0; i < names.length; i += 1) {
    for (let j = i + 1; j < names.length; j += 1) {
      if (cosineSimilarity(vectors[i], vectors[j]) >= threshold) union(i, j);
    }
  }
  const groups = new Map<number, string[]>();
  for (let i = 0; i < names.length; i += 1) {
    const root = find(i);
    const arr = groups.get(root) ?? [];
    arr.push(names[i]);
    groups.set(root, arr);
  }
  return [...groups.values()];
}

// 클러스터 내 canonical 이름 선택: 가장 긴 이름 우선, 길이가 같으면 사전순으로 가장 앞선 것.
function pickCanonicalName(names: string[]): string {
  return [...names].sort((a, b) => (b.length - a.length) || a.localeCompare(b))[0];
}

// 입력 엔티티 전체를 EntityType별로 그룹핑 후 임베딩 클러스터링하여,
// 모든 입력 엔티티 key → 해당 클러스터의 canonical ResolvedEntity로 매핑하는 테이블을 만든다.
export async function buildCanonicalMap(
  entities: ResolvedEntity[],
  embed: EmbedFn,
  threshold: number = MERGE_THRESHOLD,
): Promise<Map<string, ResolvedEntity>> {
  // key 기준 dedupe(같은 엔티티가 여러 청크에서 중복 등장 가능).
  const distinct = new Map<string, ResolvedEntity>();
  for (const e of entities) if (!distinct.has(e.key)) distinct.set(e.key, e);

  // 타입별로 그룹핑.
  const byType = new Map<EntityType, ResolvedEntity[]>();
  for (const e of distinct.values()) {
    const arr = byType.get(e.type) ?? [];
    arr.push(e);
    byType.set(e.type, arr);
  }

  const result = new Map<string, ResolvedEntity>();
  for (const [type, list] of byType) {
    // 'exact' 정책 타입(Incident/Damage 등 수치·고유 엔티티)은 임베딩 클러스터링을 건너뛰고
    // 각 엔티티를 그대로 자기 자신에 매핑한다 — 표기가 비슷해도 값이 다르면 병합 금지.
    if (entityResolutionPolicy(type) === 'exact') {
      for (const e of list) result.set(e.key, e);
      continue;
    }

    const names = list.map((e) => e.name);
    const vectors = await embed(names);
    const clusters = clusterNames(names, vectors, threshold);

    for (const clusterNames_ of clusters) {
      const canonicalName = pickCanonicalName(clusterNames_);
      const canonical: ResolvedEntity = { key: entityKey(type, canonicalName), type, name: canonicalName };
      for (const name of clusterNames_) {
        // 같은 이름을 가진 모든 원본 엔티티(중복 name)를 canonical로 매핑.
        for (const e of list) if (e.name === name) result.set(e.key, canonical);
      }
    }
  }
  return result;
}

// ResolvedGraph의 엔티티/관계를 canonical map으로 재작성한다(순수 함수, 부수효과 없음).
// map에 없는 키는 원본 그대로 통과시킨다.
export function applyCanonicalMap(graph: ResolvedGraph, map: Map<string, ResolvedEntity>): ResolvedGraph {
  const entityByKey = new Map<string, ResolvedEntity>();
  for (const e of graph.entities) {
    const canonical = map.get(e.key) ?? e;
    entityByKey.set(canonical.key, canonical);
  }

  const relSeen = new Set<string>();
  const relations: ResolvedGraph['relations'] = [];
  for (const r of graph.relations) {
    const subjectKey = map.get(r.subjectKey)?.key ?? r.subjectKey;
    const objectKey = map.get(r.objectKey)?.key ?? r.objectKey;
    const dedup = `${subjectKey}|${r.type}|${objectKey}`;
    if (relSeen.has(dedup)) continue;
    relSeen.add(dedup);
    relations.push({ subjectKey, type: r.type, objectKey });
  }

  return { entities: [...entityByKey.values()], relations };
}
