// 질문 → 기존 벡터검색으로 시드 청크 확보 → 시드 유래 엔티티에서 1~2홉 확장 → 서브그래프+출처 조립.
// 의도적으로 얇게: 영리한 랭킹/요약 없음.
import { getSession } from './neo4j-client.js';

export interface SubgraphNode { key: string; type: string; name: string; }
export interface SubgraphRelation { subject: string; type: string; object: string; }
export interface SourceChunk { chunkId: number; fileName: string; content: string; }
export interface RetrievalResult { nodes: SubgraphNode[]; relations: SubgraphRelation[]; sourceChunks: SourceChunk[]; }

export interface RetrieverDeps {
  searchDocuments(query: string, datasetIds?: number[], topK?: number, mode?: string):
    Promise<Array<{ chunkId: number; fileName: string; content: string }>>;
}

export async function retrieve(deps: RetrieverDeps, query: string, topK = 8): Promise<RetrievalResult> {
  // 1) 시드: 기존 하이브리드 문서검색으로 관련 청크 확보.
  const hits = await deps.searchDocuments(query, undefined, topK, 'HYBRID');
  const chunkIds = hits.map((h) => h.chunkId);
  if (chunkIds.length === 0) return { nodes: [], relations: [], sourceChunks: [] };

  // 2) 시드 청크 유래 엔티티에서 최대 2홉 확장한 서브그래프.
  const session = getSession();
  try {
    const r = await session.run(
      `MATCH (seed:Entity) WHERE any(c IN seed.sourceChunkIds WHERE c IN $chunkIds)
       MATCH path = (seed)-[:REL*0..2]-(:Entity)
       WITH collect(path) AS paths
       UNWIND paths AS p
       WITH [n IN nodes(p) | {key:n.key, type:n.type, name:n.name}] AS ns,
            [rel IN relationships(p) | {subject: startNode(rel).name, type: rel.type, object: endNode(rel).name}] AS rs
       UNWIND ns AS n UNWIND (CASE WHEN size(rs)=0 THEN [null] ELSE rs END) AS r
       RETURN collect(DISTINCT n) AS nodes,
              collect(DISTINCT r) AS relations`,
      { chunkIds },
    );
    const rec = r.records[0];
    const nodes = (rec?.get('nodes') ?? []) as SubgraphNode[];
    const relations = ((rec?.get('relations') ?? []) as (SubgraphRelation | null)[]).filter((x): x is SubgraphRelation => x != null);
    return { nodes, relations, sourceChunks: hits };
  } finally { await session.close(); }
}
