// 추출된 엔티티를 정규화 키로 병합하고, 관계를 키 기반으로 재작성한다(엔티티 해소).
import { ExtractionResult, EntityType, RelationType } from './ontology.js';

export interface ResolvedEntity { key: string; type: EntityType; name: string; }
export interface ResolvedRelation { subjectKey: string; type: RelationType; objectKey: string; }
export interface ResolvedGraph { entities: ResolvedEntity[]; relations: ResolvedRelation[]; }

// 앞뒤 공백 제거 + 연속 공백 1칸 + 소문자화. 표기 변형을 흡수하는 최소 정규화.
export function normalizeName(name: string): string {
  return name.trim().replace(/\s+/g, ' ').toLowerCase();
}
// 엔티티 병합 키 = "<type>:<정규화이름>". Neo4j 노드 유일성 기준.
export function entityKey(type: EntityType, name: string): string {
  return `${type}:${normalizeName(name)}`;
}

export function resolveExtraction(extraction: ExtractionResult): ResolvedGraph {
  // 이름(원표기)→키, 키→정규 엔티티. 첫 등장 표기를 대표 name으로 보존.
  const byKey = new Map<string, ResolvedEntity>();
  const keyByName = new Map<string, string>();
  for (const e of extraction.entities) {
    const key = entityKey(e.type, e.name);
    keyByName.set(e.name, key);
    if (!byKey.has(key)) byKey.set(key, { key, type: e.type, name: e.name.trim().replace(/\s+/g, ' ') });
  }
  // 관계를 키로 재작성 + (subjectKey,type,objectKey) 중복 제거.
  const relSet = new Set<string>();
  const relations: ResolvedRelation[] = [];
  for (const r of extraction.relations) {
    const sk = keyByName.get(r.subject), ok = keyByName.get(r.object);
    if (!sk || !ok) continue;
    const dedup = `${sk}|${r.type}|${ok}`;
    if (relSet.has(dedup)) continue;
    relSet.add(dedup);
    relations.push({ subjectKey: sk, type: r.type, objectKey: ok });
  }
  return { entities: [...byKey.values()], relations };
}
