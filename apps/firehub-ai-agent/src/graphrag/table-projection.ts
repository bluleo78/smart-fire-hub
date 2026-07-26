// 표 데이터셋 행을 승인된 매핑에 따라 그래프로 결정적 투영한다(exact 키만, LLM/임베딩 미사용).
import { Ontology, entityTypeId } from './ontology.js';
import { ResolvedGraph, ResolvedEntity, ResolvedRelation, entityKey } from './resolver.js';

// api MappingResponse.spec 구조의 TS 미러(엔티티 인덱스 참조 관계).
export interface MappingSpec {
  entities: { entityType: string; nameColumn: string; properties: { column: string; propertyName: string }[] }[];
  relations: { subjectRef: number; relation: string; objectRef: number }[];
}
// getDatasetMapping 반환.
export interface DatasetMapping { spec: MappingSpec; status: string; ontologyId: number; }
// queryDatasetData 응답 중 투영에 필요한 부분.
export interface DataPage { rows: Record<string, unknown>[]; totalPages: number; }

export interface ProjectTableDeps {
  fetchRows(datasetId: number, page: number, size: number): Promise<DataPage>;
  load(graph: ResolvedGraph, datasetId: number, schemaVersion: number): Promise<{ nodes: number; relations: number }>;
}
export interface ProjectionSummary {
  datasetId: number; rowCount: number; nodeCount: number; edgeCount: number; pageCount: number;
}

const PAGE_SIZE = 200; // GET /datasets/{id}/data 최대 페이지 크기

// 한 행 → 그래프 조각. 빈 nameColumn 엔티티와 끝점이 빠진 관계는 생성하지 않는다.
export function rowToGraph(
  row: Record<string, unknown>, ontology: Ontology, mapping: MappingSpec,
): ResolvedGraph {
  const entities: ResolvedEntity[] = [];
  const keyByRef = new Map<number, string>();

  mapping.entities.forEach((em, idx) => {
    const rawName = row[em.nameColumn];
    if (rawName == null || String(rawName).trim() === '') return; // 이름 없으면 노드 없음
    const name = String(rawName);
    const key = entityKey(entityTypeId(ontology, em.entityType), name);
    const def = ontology.entities.find((e) => e.type === em.entityType);
    const properties: Record<string, number | string> = {};
    for (const pm of em.properties ?? []) {
      const raw = row[pm.column];
      if (raw == null || String(raw).trim() === '') continue; // 빈 값 제외
      const dataType = def?.properties?.find((p) => p.name === pm.propertyName)?.dataType;
      if (dataType === 'number') {
        const num = Number(raw);
        properties[pm.propertyName] = Number.isFinite(num) ? num : String(raw); // 파싱 실패 시 원문 보존
      } else {
        properties[pm.propertyName] = String(raw);
      }
    }
    entities.push({
      key, type: em.entityType, name,
      ...(Object.keys(properties).length > 0 ? { properties } : {}),
    });
    keyByRef.set(idx, key);
  });

  const relations: ResolvedRelation[] = [];
  for (const rm of mapping.relations ?? []) {
    const subjectKey = keyByRef.get(rm.subjectRef);
    const objectKey = keyByRef.get(rm.objectRef);
    if (subjectKey == null || objectKey == null) continue; // 끝점 하나라도 없으면 관계 skip
    relations.push({ subjectKey, type: rm.relation, objectKey });
  }
  return { entities, relations };
}

// 데이터셋 전체를 페이지 순회하며 투영. 페이지마다 그래프를 모아 1회 load(멱등 MERGE).
export async function projectTableDataset(
  deps: ProjectTableDeps, datasetId: number, ontology: Ontology, mapping: MappingSpec,
): Promise<ProjectionSummary> {
  const distinctNodeKeys = new Set<string>();
  const distinctEdgeKeys = new Set<string>();
  let rowCount = 0;
  let page = 0;
  let totalPages = 1;

  do {
    const dataPage = await deps.fetchRows(datasetId, page, PAGE_SIZE);
    totalPages = dataPage.totalPages;
    const graph: ResolvedGraph = { entities: [], relations: [] };
    for (const row of dataPage.rows) {
      rowCount += 1;
      const rowGraph = rowToGraph(row, ontology, mapping);
      graph.entities.push(...rowGraph.entities);
      graph.relations.push(...rowGraph.relations);
    }
    if (graph.entities.length > 0 || graph.relations.length > 0) {
      await deps.load(graph, datasetId, ontology.schemaVersion);
    }
    for (const e of graph.entities) distinctNodeKeys.add(e.key);
    for (const r of graph.relations) distinctEdgeKeys.add(`${r.subjectKey}|${r.type}|${r.objectKey}`);
    page += 1;
  } while (page < totalPages);

  return {
    datasetId, rowCount,
    nodeCount: distinctNodeKeys.size, edgeCount: distinctEdgeKeys.size, pageCount: page,
  };
}
