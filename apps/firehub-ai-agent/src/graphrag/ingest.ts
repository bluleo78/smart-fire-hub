// 배치 오케스트레이션: 청크 bulk-read → 청크별 LLM 추출/해소(로컬) → 전역 시맨틱 해소 → Neo4j 적재.
// 의존성 주입으로 LLM/임베딩/Neo4j를 분리해 유닛 테스트 가능하게 한다.
//
// 2단계 구조인 이유: 임베딩 기반 클러스터링(semantic-resolver.ts)은 데이터셋 전체 엔티티를
// 한 번에 봐야 청크를 넘나드는 표기 변형("스프링클러" vs "스프링클러 설비")을 병합할 수 있다.
// 청크마다 즉시 적재하는 이전 방식은 청크 간 클러스터링이 불가능했다.
import { ExtractionResult, Ontology } from './ontology.js';
import { resolveExtraction, ResolvedGraph, ResolvedEntity } from './resolver.js';
import { buildCanonicalMap, applyCanonicalMap, EmbedFn } from './semantic-resolver.js';
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
  const perChunk: { chunkId: number; graph: ResolvedGraph }[] = [];
  const allEntities: ResolvedEntity[] = [];
  let extractionFailures = 0;
  for (const chunk of chunks) {
    const extraction = await deps.extract(chunk.content); // 추출 실패 시 extractor가 빈 결과 반환 → 계속
    if (extraction.entities.length === 0 && extraction.relations.length === 0) extractionFailures += 1;
    const graph = resolveExtraction(extraction);
    perChunk.push({ chunkId: chunk.chunkId, graph });
    allEntities.push(...graph.entities);
  }

  // 2단계(전역 해소): 데이터셋 전체 엔티티를 임베딩 클러스터링해 canonical map을 만든다.
  const canonicalMap = await buildCanonicalMap(allEntities, deps.embed, ontology, undefined, deps.link);

  // 3단계(적재): 청크별 그래프를 canonical map으로 재작성 후 Neo4j에 멱등 적재한다.
  // sourceChunkIds는 loader.ts가 청크 단위로 누적하므로 청크별 load 호출을 유지한다.
  const distinctEntityKeys = new Set<string>();
  const distinctRelKeys = new Set<string>();
  for (const { chunkId, graph } of perChunk) {
    const remapped = applyCanonicalMap(graph, canonicalMap);
    await deps.load(remapped, chunkId, ontology.schemaVersion);
    for (const e of remapped.entities) distinctEntityKeys.add(e.key);
    for (const r of remapped.relations) distinctRelKeys.add(`${r.subjectKey}|${r.type}|${r.objectKey}`);
  }

  return {
    datasetId,
    chunks: chunks.length,
    entities: distinctEntityKeys.size,
    relations: distinctRelKeys.size,
    ...(extractionFailures > 0 ? { extractionFailures } : {}),
  };
}
