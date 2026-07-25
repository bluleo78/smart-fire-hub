// 배치 오케스트레이션: 청크 bulk-read → 청크별 LLM 추출/해소(로컬) → 전역 시맨틱 해소 → Neo4j 적재.
// 의존성 주입으로 LLM/임베딩/Neo4j를 분리해 유닛 테스트 가능하게 한다.
//
// 2단계 구조인 이유: 임베딩 기반 클러스터링(semantic-resolver.ts)은 데이터셋 전체 엔티티를
// 한 번에 봐야 청크를 넘나드는 표기 변형("스프링클러" vs "스프링클러 설비")을 병합할 수 있다.
// 청크마다 즉시 적재하는 이전 방식은 청크 간 클러스터링이 불가능했다.
import { ExtractionResult, Ontology, EntityType, RelationType, entityTypeId, PropertyReviewCandidate } from './ontology.js';
import { resolveExtraction, ResolvedGraph, ResolvedEntity, ResolvedRelation, entityKey } from './resolver.js';
import { buildCanonicalMap, applyCanonicalMap, EmbedFn, LookupFn, RecordFn } from './semantic-resolver.js';
import { LinkFn } from './semantic-link.js';

// 추출 신뢰도 임계값 — 집계(max) confidence가 이 미만이면 Neo4j 미적재·검수 보류(승인 시 적재).
export const CONFIDENCE_THRESHOLD = 0.6;

// canonical 키별로 집계된 엔티티(신뢰도 max, 출처/속성 병합) — 보류 판정·큐 등록의 단위.
interface AggregatedEntity {
  key: string; type: EntityType; name: string;
  confidence: number; reason?: string;
  properties: Record<string, number | string>;
  sourceChunkIds: number[];
}

// canonical 엣지별로 집계된 관계(신뢰도 max, 출처 합집합) — 보류 판정·큐 등록의 단위.
interface AggregatedRelation {
  subjectKey: string; relType: RelationType; objectKey: string;
  subjectName: string; objectName: string;
  confidence: number; reason?: string; sourceChunkIds: number[];
}

export interface IngestDeps {
  listChunks(datasetId: number): Promise<{ chunkId: number; content: string }[]>;
  extract(text: string): Promise<ExtractionResult>;
  load(
    graph: ResolvedGraph, chunkId: number, schemaVersion: number,
  ): Promise<{ nodes: number; relations: number }>;
  embed: EmbedFn;
  // 근접쌍(코사인 0.5~0.78) LLM 재판단 — 생략 시 semantic-resolver.ts가 기존 임베딩-only 동작을 유지한다.
  link?: LinkFn;
  // HITL 근접쌍 기존 결정 조회/등록 — 생략 시 매 ingest마다 LLM 재판단만 하고 대기열 등록 없이 병합 보류.
  lookupDecision?: LookupFn;
  recordPending?: RecordFn;
  // 정규화 실패 속성 검수 등록(교정형) — 생략 시 등록 없이 기존 동작 유지(하위호환).
  recordPropertyReview?(
    datasetId: number, chunkId: number, entityKey: string, entityType: EntityType,
    propertyName: string, dataType: 'text' | 'number' | 'date', rawText: string,
  ): Promise<void>;
  // 저신뢰 엔티티 기존 검수 결정 조회 — approved면 적재, rejected면 조용히 보류(재큐잉 X), 없으면 보류+큐.
  lookupEntityDecision?(entityType: EntityType, name: string): Promise<'approved' | 'rejected' | undefined>;
  // 저신뢰 엔티티(+보류 관계)를 검수 큐에 등록. best-effort(실패해도 ingest 계속).
  recordPendingEntity?(item: {
    datasetId: number; entityType: EntityType; name: string;
    properties?: Record<string, number | string>; sourceChunkIds: number[];
    confidence: number; reason?: string;
    relations: { relType: RelationType; direction: 'out' | 'in'; otherKey: string }[];
  }): Promise<void>;
  // 저신뢰 관계 기존 검수 결정 조회 — approved면 적재, rejected면 조용히 보류(재큐잉 X), 없으면 보류+큐.
  lookupRelationDecision?(subjectKey: string, relType: RelationType, objectKey: string): Promise<'approved' | 'rejected' | undefined>;
  // 저신뢰 관계를 검수 큐에 등록. best-effort(실패해도 ingest 계속).
  recordPendingRelation?(item: {
    datasetId: number; subjectKey: string; relType: RelationType; objectKey: string;
    subjectName: string; objectName: string; sourceChunkIds: number[];
    confidence: number; reason?: string;
  }): Promise<void>;
}
export interface IngestSummary {
  datasetId: number;
  chunks: number;
  entities: number;
  relations: number;
  extractionFailures?: number;
}

export async function ingestDataset(
  deps: IngestDeps, datasetId: number, ontology: Ontology,
): Promise<IngestSummary> {
  const chunks = await deps.listChunks(datasetId);

  // 1단계(수집): 청크별 추출→로컬 해소. confidence는 resolveExtraction이 엔티티에 실어 온다.
  const perChunk: { chunkId: number; graph: ResolvedGraph; candidates: PropertyReviewCandidate[] }[] = [];
  const allEntities: ResolvedEntity[] = [];
  const allRelations: (ResolvedRelation & { sourceChunkIds: number[] })[] = [];
  let extractionFailures = 0;
  for (const chunk of chunks) {
    const extraction = await deps.extract(chunk.content);
    if (extraction.entities.length === 0 && extraction.relations.length === 0) extractionFailures += 1;
    const graph = resolveExtraction(extraction, ontology);
    perChunk.push({ chunkId: chunk.chunkId, graph, candidates: extraction.propertyReviewCandidates ?? [] });
    // 출처 chunkId 스탬프(confidence/속성은 그대로 유지) → 2단계 전역 해소·집계까지 운반.
    allEntities.push(...graph.entities.map((e) => ({ ...e, sourceChunkIds: [chunk.chunkId] })));
    allRelations.push(...graph.relations.map((r) => ({ ...r, sourceChunkIds: [chunk.chunkId] })));
  }

  // 2단계(전역 해소): canonical map.
  const canonicalMap = await buildCanonicalMap(
    allEntities, deps.embed, ontology, undefined, deps.link, deps.lookupDecision, deps.recordPending, datasetId,
  );

  // 2.5단계(신뢰도 집계): 필터는 canonical 해소 "이후" 집계 엔티티 기준(어드바이저).
  // 저신뢰 mention이 고신뢰 클러스터에 병합되면 corroboration으로 flag 자동 해제(confidence=max),
  // 보류 엔티티 key = 최종 canonical key(키 드리프트 없음 → payload key가 승인 적재 key와 동일).
  const aggregated = new Map<string, AggregatedEntity>();
  for (const e of allEntities) {
    const canon = canonicalMap.get(e.key) ?? e;
    const conf = e.confidence ?? 1; // 미신고 = 고신뢰(1) → 하위호환(보류 없음).
    const prev = aggregated.get(canon.key);
    if (!prev) {
      aggregated.set(canon.key, {
        key: canon.key, type: canon.type, name: canon.name,
        confidence: conf, reason: e.reason,
        properties: { ...(e.properties ?? {}) },
        sourceChunkIds: [...(e.sourceChunkIds ?? [])],
      });
    } else {
      if (conf > prev.confidence) { prev.confidence = conf; prev.reason = e.reason ?? prev.reason; }
      prev.properties = { ...prev.properties, ...(e.properties ?? {}) };
      prev.sourceChunkIds = [...new Set([...prev.sourceChunkIds, ...(e.sourceChunkIds ?? [])])];
    }
  }

  // 보류 판정: confidence<임계값이면서 lookup이 approved가 아닌 키. none만 큐 등록 대상.
  const withheldKeys = new Set<string>();
  const queueKeys = new Set<string>();
  for (const agg of aggregated.values()) {
    if (agg.confidence >= CONFIDENCE_THRESHOLD) continue; // 고신뢰 — 정상 적재.
    let decision: 'approved' | 'rejected' | undefined;
    if (deps.lookupEntityDecision) {
      try { decision = await deps.lookupEntityDecision(agg.type, agg.name); }
      catch { decision = undefined; } // 조회 실패 → none 취급(보류+큐).
    }
    if (decision === 'approved') continue; // 승인됨 — 정상 적재(보류 안 함).
    withheldKeys.add(agg.key);
    if (decision === undefined) queueKeys.add(agg.key); // rejected는 보류하되 큐 등록 안 함(조용히).
  }

  // 2.6단계(관계 신뢰도 집계): allRelations를 canonical 엣지 정체성 기준 max 집계(엔티티 패스와 동일 구조).
  // 엔드포인트 키/이름은 canonicalMap에서 취득(remap 후 최종 엣지 = 승인 적재 엣지와 동일).
  const aggregatedRel = new Map<string, AggregatedRelation>();
  for (const r of allRelations) {
    const cs = canonicalMap.get(r.subjectKey);
    const co = canonicalMap.get(r.objectKey);
    const subjectKey = cs?.key ?? r.subjectKey;
    const objectKey = co?.key ?? r.objectKey;
    const edgeId = `${subjectKey}|${r.type}|${objectKey}`;
    const conf = r.confidence ?? 1; // 미신고 = 고신뢰(1) → 하위호환.
    const prev = aggregatedRel.get(edgeId);
    if (!prev) {
      aggregatedRel.set(edgeId, {
        subjectKey, relType: r.type, objectKey,
        subjectName: cs?.name ?? r.subjectKey, objectName: co?.name ?? r.objectKey,
        confidence: conf, reason: r.reason, sourceChunkIds: [...r.sourceChunkIds],
      });
    } else {
      if (conf > prev.confidence) { prev.confidence = conf; prev.reason = r.reason ?? prev.reason; }
      prev.sourceChunkIds = [...new Set([...prev.sourceChunkIds, ...r.sourceChunkIds])];
    }
  }

  // 관계 보류 판정: confidence<임계값 + 양 끝점이 엔티티-보류가 아닐 것(끝점 우선). none만 큐.
  const relWithheldEdges = new Set<string>();
  const relQueueEdges = new Set<string>();
  for (const [edgeId, agg] of aggregatedRel) {
    if (agg.confidence >= CONFIDENCE_THRESHOLD) continue; // 고신뢰 — 정상 적재.
    // 끝점 우선: 끝점 엔티티가 슬라이스1로 보류된 관계는 여기서 다루지 않음(엔티티 승인 시 함께 적재).
    if (withheldKeys.has(agg.subjectKey) || withheldKeys.has(agg.objectKey)) continue;
    let decision: 'approved' | 'rejected' | undefined;
    if (deps.lookupRelationDecision) {
      try { decision = await deps.lookupRelationDecision(agg.subjectKey, agg.relType, agg.objectKey); }
      catch { decision = undefined; }
    }
    if (decision === 'approved') continue; // 승인됨 — 정상 적재.
    relWithheldEdges.add(edgeId);
    if (decision === undefined) relQueueEdges.add(edgeId);
  }

  // 3단계(적재): canonical 재작성 후 보류 엔티티/관계를 제외하고 Neo4j에 멱등 적재.
  // withheldKeys가 비면 필터는 no-op → 기존과 그래프-동일(판별 불변식).
  const distinctEntityKeys = new Set<string>();
  const distinctRelKeys = new Set<string>();
  // 큐 등록 엔티티의 incident 관계 수집(canonical-remap 뒤, 엔티티별 dedup).
  const relByKey = new Map<string, { relType: RelationType; direction: 'out' | 'in'; otherKey: string }[]>();
  const relSeen = new Map<string, Set<string>>();
  const addRel = (k: string, rel: { relType: RelationType; direction: 'out' | 'in'; otherKey: string }) => {
    const seen = relSeen.get(k) ?? new Set<string>();
    const sig = `${rel.direction}|${rel.relType}|${rel.otherKey}`;
    if (seen.has(sig)) return;
    seen.add(sig); relSeen.set(k, seen);
    const arr = relByKey.get(k) ?? []; arr.push(rel); relByKey.set(k, arr);
  };

  for (const { chunkId, graph, candidates } of perChunk) {
    const remapped = applyCanonicalMap(graph, canonicalMap);
    const filtered: ResolvedGraph = {
      entities: remapped.entities.filter((e) => !withheldKeys.has(e.key)),
      relations: remapped.relations.filter((r) =>
        !withheldKeys.has(r.subjectKey) && !withheldKeys.has(r.objectKey)
        && !relWithheldEdges.has(`${r.subjectKey}|${r.type}|${r.objectKey}`)),
    };
    await deps.load(filtered, chunkId, ontology.schemaVersion);
    for (const e of filtered.entities) distinctEntityKeys.add(e.key);
    for (const r of filtered.relations) distinctRelKeys.add(`${r.subjectKey}|${r.type}|${r.objectKey}`);

    // 큐 등록 엔티티가 끌린 관계 수집(상대가 보류든 아니든 — 승인 시 끝점 존재하면 MERGE).
    for (const r of remapped.relations) {
      if (queueKeys.has(r.subjectKey)) addRel(r.subjectKey, { relType: r.type, direction: 'out', otherKey: r.objectKey });
      if (queueKeys.has(r.objectKey)) addRel(r.objectKey, { relType: r.type, direction: 'in', otherKey: r.subjectKey });
    }

    // 정규화 실패 속성 검수(기존 동작 유지 — 보류 엔티티라도 그대로 등록, 승인 전엔 노드 없어 no-op 될 뿐).
    if (deps.recordPropertyReview) {
      for (const c of candidates) {
        const localKey = entityKey(entityTypeId(ontology, c.entityType), c.entityName);
        const finalKey = canonicalMap.get(localKey)?.key ?? localKey;
        try {
          await deps.recordPropertyReview(datasetId, chunkId, finalKey, c.entityType, c.propertyName, c.dataType, c.rawText);
        } catch (err) {
          console.warn('[ingest] 속성 검수 등록 실패(무시하고 계속):', err);
        }
      }
    }
  }

  // 4단계(큐 등록): 보류-신규 엔티티를 관계와 함께 검수 큐에 등록(best-effort).
  if (deps.recordPendingEntity) {
    for (const key of queueKeys) {
      const agg = aggregated.get(key)!;
      try {
        await deps.recordPendingEntity({
          datasetId, entityType: agg.type, name: agg.name,
          ...(Object.keys(agg.properties).length > 0 ? { properties: agg.properties } : {}),
          sourceChunkIds: agg.sourceChunkIds,
          confidence: agg.confidence, reason: agg.reason,
          relations: relByKey.get(key) ?? [],
        });
      } catch (err) {
        console.warn('[ingest] 엔티티 검수 등록 실패(무시하고 계속):', err);
      }
    }
  }

  // 4.5단계(관계 큐 등록): 보류-신규 저신뢰 관계를 검수 큐에 등록(best-effort).
  if (deps.recordPendingRelation) {
    for (const edgeId of relQueueEdges) {
      const agg = aggregatedRel.get(edgeId)!;
      try {
        await deps.recordPendingRelation({
          datasetId, subjectKey: agg.subjectKey, relType: agg.relType, objectKey: agg.objectKey,
          subjectName: agg.subjectName, objectName: agg.objectName,
          sourceChunkIds: agg.sourceChunkIds, confidence: agg.confidence, reason: agg.reason,
        });
      } catch (err) {
        console.warn('[ingest] 관계 검수 등록 실패(무시하고 계속):', err);
      }
    }
  }

  return {
    datasetId, chunks: chunks.length,
    entities: distinctEntityKeys.size, relations: distinctRelKeys.size,
    ...(extractionFailures > 0 ? { extractionFailures } : {}),
  };
}
