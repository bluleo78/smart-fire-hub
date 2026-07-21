// 질문 → 기존 벡터검색으로 시드 청크 확보 → 시드 유래 엔티티에서 앵커타입 인지 허브 감쇠 BFS 확장 → 서브그래프+출처 조립.
// 의도적으로 얇게: 영리한 랭킹/요약 없음.
//
// 배경(결함 A, 1차): 기존 undirected [:REL*0..2] 순수 Cypher 확장은 "소방시설법 제12조" 같은
// 슈퍼허브(모든 사건이 VIOLATED, 모든 설비가 GOVERNED_BY로 연결)를 만나면 2홉 내에서
// 그래프 전체에 도달해 모든 질문이 동일한 ~30노드 서브그래프를 반환하는 문제가 있었다.
//
// 배경(결함 A, 2차 — 6문서 e2e 재현): 단순 "degree 기반 허브 감쇠 + 시드는 항상 확장"만으로는
// 부족했다. 시드 청크 안에 등장하는 모든 엔티티가 "시드"가 되고 시드는 무조건 확장되므로,
// "스프링클러 설비"처럼 여러 문서에 공유되는 Equipment/Regulation 엔티티가 시드로 잡히면
// 그 자체가 다리(bridge)가 되어 무관한 건물/사건까지 2홉 내에서 전부 끌려온다.
// 해결: 확장 가능 여부를 "시드인가"가 아니라 "엔티티 타입이 anchorTypes에 속하는가(Incident 등
// 사건 중심 타입은 무조건 확장) OR degree < hubDegree(저차수 노드)"로 통일한다. 시드도 예외 없이
// 이 규칙을 따른다 — 시드는 항상 결과에 "포함"되지만, 공유 Equipment/Regulation 시드는 고차수면
// 더 이상 확장하지 않는 "종단(terminal)" 노드가 된다.
import { getSession } from './neo4j-client.js';

export interface SubgraphNode { key: string; type: string; name: string; }
export interface SubgraphRelation { subject: string; type: string; object: string; }
export interface SourceChunk { chunkId: number; fileName: string; content: string; }
export interface RetrievalResult { nodes: SubgraphNode[]; relations: SubgraphRelation[]; sourceChunks: SourceChunk[]; }

export interface RetrieverDeps {
  searchDocuments(query: string, datasetIds?: number[], topK?: number, mode?: string):
    Promise<Array<{ chunkId: number; fileName: string; content: string }>>;
}

// 검색/확장 파라미터.
// - hubDegree=4: 6문서 e2e 관찰상 공유 Equipment/Regulation은 degree ~4 이상, 개별 Building은
//   degree 2~3 수준 → 4로 낮춰서 공유 엔티티를 종단으로 만들되 일반 Building은 계속 확장되게 한다.
// - anchorTypes={'Incident'}: 사건은 이 도메인에서 항상 확장해야 하는 "질문의 중심" 타입 —
//   degree가 높아도(사건에 원인/피해/규정이 많이 걸려도) 무조건 확장한다.
export interface RetrieveOptions {
  topK?: number;
  maxHops?: number;
  hubDegree?: number;
  maxNodes?: number;
  anchorTypes?: Set<string>;
}
const DEFAULT_ANCHOR_TYPES = new Set(['Incident']);
const DEFAULT_OPTS: Required<RetrieveOptions> = {
  topK: 8, maxHops: 2, hubDegree: 4, maxNodes: 40, anchorTypes: DEFAULT_ANCHOR_TYPES,
};

// fetchNeighbors가 반환하는 1개 간선(edge) 레코드 — neighbor의 전역 degree를 함께 실어 허브 판정에 쓴다.
export interface NeighborRow {
  fromKey: string;
  relType: string;
  neighbor: { key: string; type: string; name: string };
  neighborDegree: number;
}
export type FetchNeighbors = (keys: string[]) => Promise<NeighborRow[]>;

// 서브그래프 노드(로컬 BFS용) — key로 dedupe하기 위한 최소 표현.
interface LocalNode { key: string; type: string; name: string; }
// 시드 노드 — 확장 가능 여부 판정을 위해 degree까지 함께 전달받는다(비-시드 이웃과 동일한 규칙 적용).
export interface SeedNode extends SubgraphNode { degree: number; }

/**
 * 순수 함수(부수효과 없음) — Neo4j 세션을 직접 다루지 않고 fetchNeighbors만 주입받아
 * 유닛 테스트에서 Cypher 없이 BFS 로직만 검증할 수 있게 분리했다.
 *
 * 알고리즘:
 * - included: key→node 맵, seedNodes로 시작(항상 결과에 포함). relations: 확정된 간선 목록.
 *   frontier: 다음 홉에서 확장을 "시도"할 키(시드 포함).
 * - 확장 가능 여부는 시드/비시드 구분 없이 동일한 규칙을 따른다:
 *   `anchorTypes.has(node.type) || degree < hubDegree`.
 *   즉 Incident처럼 도메인상 항상 확장해야 하는 앵커 타입은 degree가 높아도 확장하고,
 *   그 외 타입은 degree가 hubDegree 미만인 저차수 노드만 확장한다.
 *   공유 Equipment/Regulation처럼 여러 문서에 걸친 고차수 비-앵커 노드는 시드로 잡혀도
 *   "포함은 되지만 그 너머로 확장하지 않는" 종단(terminal) 노드가 되어 브릿징을 차단한다.
 * - maxNodes 도달 후에는 새 노드를 추가하지 않되, 이미 포함된 노드 사이의 관계는 계속 기록한다.
 */
export async function expandSubgraph(
  seedKeys: string[],
  seedNodes: SeedNode[],
  fetchNeighbors: FetchNeighbors,
  opts: Required<Pick<RetrieveOptions, 'maxHops' | 'hubDegree' | 'maxNodes' | 'anchorTypes'>>,
): Promise<{ nodes: SubgraphNode[]; relations: SubgraphRelation[] }> {
  const included = new Map<string, LocalNode>();
  const degreeByKey = new Map<string, number>();
  for (const n of seedNodes) {
    included.set(n.key, { key: n.key, type: n.type, name: n.name });
    degreeByKey.set(n.key, n.degree);
  }

  // 관계 dedupe(subject|type|object 기준) — BFS 중 같은 간선이 여러 홉에서 재발견될 수 있음.
  const relSeen = new Set<string>();
  const relations: SubgraphRelation[] = [];

  function addRelation(fromKey: string, relType: string, toKey: string): void {
    if (!included.has(fromKey) || !included.has(toKey)) return;
    const dedup = `${fromKey}|${relType}|${toKey}`;
    if (relSeen.has(dedup)) return;
    relSeen.add(dedup);
    relations.push({ subject: fromKey, type: relType, object: toKey });
  }

  // 노드가 확장 가능한지 판정 — 앵커 타입이거나 저차수(비허브)일 때만 그 노드를 거쳐 더 나아간다.
  function isExpandable(key: string): boolean {
    const node = included.get(key);
    if (!node) return false;
    if (opts.anchorTypes.has(node.type)) return true;
    return (degreeByKey.get(key) ?? 0) < opts.hubDegree;
  }

  let frontier = [...seedKeys];
  for (let hop = 0; hop < opts.maxHops && frontier.length > 0; hop += 1) {
    const expandable = frontier.filter((k) => isExpandable(k));
    if (expandable.length === 0) break;

    const rows = await fetchNeighbors(expandable);
    const nextFrontier: string[] = [];

    for (const row of rows) {
      degreeByKey.set(row.neighbor.key, row.neighborDegree);

      const alreadyIncluded = included.has(row.neighbor.key);
      if (!alreadyIncluded) {
        if (included.size >= opts.maxNodes) {
          // 노드 정원 초과 — 새 노드는 추가하지 않는다(관계도 양끝이 included일 때만 기록되므로 자동 스킵).
          continue;
        }
        included.set(row.neighbor.key, row.neighbor);
        nextFrontier.push(row.neighbor.key);
      }
      addRelation(row.fromKey, row.relType, row.neighbor.key);
    }
    frontier = nextFrontier;
  }

  return { nodes: [...included.values()], relations };
}

export async function retrieve(
  deps: RetrieverDeps,
  query: string,
  opts: RetrieveOptions = {},
): Promise<RetrievalResult> {
  const o = { ...DEFAULT_OPTS, ...opts };

  // 1) 시드: 기존 하이브리드 문서검색으로 관련 청크 확보.
  const hits = await deps.searchDocuments(query, undefined, o.topK, 'HYBRID');
  const chunkIds = hits.map((h) => h.chunkId);
  if (chunkIds.length === 0) return { nodes: [], relations: [], sourceChunks: [] };

  const session = getSession();
  try {
    // 2) 시드 청크에서 유래한 엔티티 키/노드 + 전역 degree 조회.
    //    앵커타입 인지 확장 규칙을 시드에도 동일 적용하려면 시드의 degree가 필요하다
    //    (공유 Equipment/Regulation 시드가 고차수면 확장하지 않는 종단 노드가 되어야 함).
    const seedResult = await session.run(
      `MATCH (seed:Entity) WHERE any(c IN seed.sourceChunkIds WHERE c IN $chunkIds)
       RETURN seed.key AS key, seed.type AS type, seed.name AS name,
              COUNT { (seed)-[:REL]-() } AS degree`,
      { chunkIds },
    );
    const seedNodes: SeedNode[] = seedResult.records.map((rec) => ({
      key: rec.get('key') as string,
      type: rec.get('type') as string,
      name: rec.get('name') as string,
      degree: Number(rec.get('degree')),
    }));
    const seedKeys = seedNodes.map((n) => n.key);
    if (seedKeys.length === 0) return { nodes: [], relations: [], sourceChunks: hits };

    // 3) fetchNeighbors — 주어진 키 집합에서 나가는 간선 + 이웃의 전역 degree.
    //    COUNT{}로 이웃 b의 전역(양방향) degree를 별도 계산해, 경로 중복 카운팅 문제를 피한다.
    const fetchNeighbors: FetchNeighbors = async (keys: string[]) => {
      if (keys.length === 0) return [];
      const r = await session.run(
        `MATCH (a:Entity)-[rel:REL]-(b:Entity)
         WHERE a.key IN $keys AND b.key <> a.key
         RETURN DISTINCT a.key AS fromKey, rel.type AS relType,
                b.key AS neighborKey, b.type AS neighborType, b.name AS neighborName,
                COUNT { (b)-[:REL]-() } AS neighborDegree`,
        { keys },
      );
      return r.records.map((rec) => ({
        fromKey: rec.get('fromKey') as string,
        relType: rec.get('relType') as string,
        neighbor: {
          key: rec.get('neighborKey') as string,
          type: rec.get('neighborType') as string,
          name: rec.get('neighborName') as string,
        },
        neighborDegree: Number(rec.get('neighborDegree')),
      }));
    };

    const { nodes, relations } = await expandSubgraph(seedKeys, seedNodes, fetchNeighbors, {
      maxHops: o.maxHops, hubDegree: o.hubDegree, maxNodes: o.maxNodes, anchorTypes: o.anchorTypes,
    });

    // 서브그래프 관계는 key 기반(subject/object)이지만, 응답 스키마는 기존과 동일하게
    // subject/object에 노드 name이 아닌 key를 그대로 노출하지 않도록 name으로 치환한다.
    const nameByKey = new Map(nodes.map((n) => [n.key, n.name]));
    const relationsOut: SubgraphRelation[] = relations.map((r) => ({
      subject: nameByKey.get(r.subject) ?? r.subject,
      type: r.type,
      object: nameByKey.get(r.object) ?? r.object,
    }));

    return { nodes, relations: relationsOut, sourceChunks: hits };
  } finally {
    await session.close();
  }
}
