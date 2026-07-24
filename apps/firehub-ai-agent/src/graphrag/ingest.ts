// 배치 오케스트레이션: 청크 bulk-read → 청크별 LLM 추출/해소(로컬) → 전역 시맨틱 해소 → Neo4j 적재.
// 의존성 주입으로 LLM/임베딩/Neo4j를 분리해 유닛 테스트 가능하게 한다.
//
// 2단계 구조인 이유: 임베딩 기반 클러스터링(semantic-resolver.ts)은 데이터셋 전체 엔티티를
// 한 번에 봐야 청크를 넘나드는 표기 변형("스프링클러" vs "스프링클러 설비")을 병합할 수 있다.
// 청크마다 즉시 적재하는 이전 방식은 청크 간 클러스터링이 불가능했다.
import { ExtractionResult, Ontology, EntityType, entityTypeId, PropertyReviewCandidate } from './ontology.js';
import { resolveExtraction, ResolvedGraph, ResolvedEntity, entityKey } from './resolver.js';
import { buildCanonicalMap, applyCanonicalMap, EmbedFn, LookupFn, RecordFn } from './semantic-resolver.js';
import { LinkFn } from './semantic-link.js';

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

  // 1단계(수집): 청크별로 추출→로컬 해소(정확 문자열 병합)만 수행하고, 적재는 미룬다.
  const perChunk: { chunkId: number; graph: ResolvedGraph; candidates: PropertyReviewCandidate[] }[] = [];
  const allEntities: ResolvedEntity[] = [];
  let extractionFailures = 0;
  for (const chunk of chunks) {
    const extraction = await deps.extract(chunk.content); // 추출 실패 시 extractor가 빈 결과 반환 → 계속
    if (extraction.entities.length === 0 && extraction.relations.length === 0) extractionFailures += 1;
    const graph = resolveExtraction(extraction, ontology);
    // 정규화 실패 후보(원본 이름 그대로)는 청크별로 보존해 3단계에서 canonical key로 바인딩한다.
    perChunk.push({ chunkId: chunk.chunkId, graph, candidates: extraction.propertyReviewCandidates ?? [] });
    allEntities.push(...graph.entities);
  }

  // 2단계(전역 해소): 데이터셋 전체 엔티티를 임베딩 클러스터링해 canonical map을 만든다.
  const canonicalMap = await buildCanonicalMap(
    allEntities, deps.embed, ontology, undefined, deps.link, deps.lookupDecision, deps.recordPending,
  );

  // 3단계(적재): 청크별 그래프를 canonical map으로 재작성 후 Neo4j에 멱등 적재한다.
  // sourceChunkIds는 loader.ts가 청크 단위로 누적하므로 청크별 load 호출을 유지한다.
  const distinctEntityKeys = new Set<string>();
  const distinctRelKeys = new Set<string>();
  for (const { chunkId, graph, candidates } of perChunk) {
    const remapped = applyCanonicalMap(graph, canonicalMap);
    await deps.load(remapped, chunkId, ontology.schemaVersion);
    for (const e of remapped.entities) distinctEntityKeys.add(e.key);
    for (const r of remapped.relations) distinctRelKeys.add(`${r.subjectKey}|${r.type}|${r.objectKey}`);

    // 정규화 실패 후보를 canonical 최종 key로 바인딩해 검수 큐에 등록(best-effort — 실패해도 ingest 계속).
    // 후보는 추출 당시의 원본(로컬) 이름을 담고 있으므로, 전역 해소 결과인 canonicalMap을 거쳐
    // 실제로 값이 기록될 노드의 key로 치환해야 검수 항목과 노드가 어긋나지 않는다.
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

  return {
    datasetId,
    chunks: chunks.length,
    entities: distinctEntityKeys.size,
    relations: distinctRelKeys.size,
    ...(extractionFailures > 0 ? { extractionFailures } : {}),
  };
}
