// 배치 오케스트레이션: 청크 bulk-read → 청크별 LLM 추출 → 해소 → Neo4j 적재.
// 의존성 주입으로 LLM/Neo4j를 분리해 유닛 테스트 가능하게 한다.
import { ExtractionResult } from './ontology.js';
import { resolveExtraction, ResolvedGraph } from './resolver.js';

export interface IngestDeps {
  listChunks(datasetId: number): Promise<{ chunkId: number; content: string }[]>;
  extract(text: string): Promise<ExtractionResult>;
  load(graph: ResolvedGraph, chunkId: number): Promise<{ nodes: number; relations: number }>;
}
export interface IngestSummary { datasetId: number; chunks: number; entities: number; relations: number; }

export async function ingestDataset(deps: IngestDeps, datasetId: number): Promise<IngestSummary> {
  const chunks = await deps.listChunks(datasetId);
  let entities = 0, relations = 0;
  for (const chunk of chunks) {
    const extraction = await deps.extract(chunk.content); // 추출 실패 시 extractor가 빈 결과 반환 → 계속
    const graph = resolveExtraction(extraction);
    const res = await deps.load(graph, chunk.chunkId);
    // 청크별 입력 크기를 단순 합산한 값 — 엔티티/관계가 청크 간 중복되면 중복 집계됨(그래프의 distinct 노드/엣지 수가 아님). MERGE 단계에서 실제 중복 제거됨.
    entities += res.nodes; relations += res.relations;
  }
  return { datasetId, chunks: chunks.length, entities, relations };
}
