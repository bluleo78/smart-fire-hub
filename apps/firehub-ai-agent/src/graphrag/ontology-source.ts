// 추출 시점 온톨로지 소스 — 데이터셋에 바인딩된 온톨로지를 api(DB 소유)에서 fetch한다.
// "기본 온톨로지" 폴백은 존재하지 않는다: 바인딩이 없으면 이 함수는 명확히 실패하고, 호출부가
// 그 실패를 그대로 사용자에게 전달해 "먼저 온톨로지를 연결하라"고 안내해야 한다(#678).
import type { FireHubApiClient } from '../mcp/api-client.js';
import { Ontology, deserializeOntology } from './ontology.js';
import type { GraphReadVerdict } from './graph-read-gate.js';
import { GraphReadableOntologyId, VerifiedOntologyId } from './verified-ontology-id.js';

/** 해소 결과 — 테넌트 경계(RLS)를 통과한 온톨로지. 쓰기·스키마 경로가 쓴다. */
export interface ResolvedOntology {
  ontology: Ontology;
  /** 테넌트 경계를 통과한 id. */
  ontologyId: VerifiedOntologyId;
}

/** 읽기 지점용 해소 결과 — 테넌트 경계에 더해 그래프 읽기 판정(WD-28) 결과를 싣는다. */
export interface ResolvedReadableOntology extends ResolvedOntology {
  /** 판정 통과 시 읽기 브랜드 id, 아니면 'restricted'(권한 없음)·'unavailable'(판정 조회 실패). */
  readable: GraphReadVerdict;
}

/** 쓰기·스키마 경로 해소가 api 클라이언트에서 쓰는 최소 표면 — 테넌트 경계 왕복만. 판정 API 는 일부러 뺐다. */
type ResolverClient = Pick<FireHubApiClient, 'getOntologyById'>;

/** 읽기 지점 해소의 표면 — 테넌트 경계 왕복 + 그래프 읽기 판정. */
type ReadableResolverClient = ResolverClient & Pick<FireHubApiClient, 'getOntologyGraphAccess' | 'hasDelegatedUser'>;

/**
 * id 로 온톨로지를 해소한다 — **그리고 그것이 요청자 테넌트의 것인지 확인한다.**
 *
 * 이 왕복이 그래프 경로의 유일한 테넌트 경계다. Neo4j 는 전 테넌트가 공유하는 단일 DB 라
 * RLS 가 없고 노드에 tenant_id 도 없으므로, "이 ontologyId 를 써도 되는가"는 RLS 가 걸린
 * firehub-api 의 ontology 테이블만 답할 수 있다. api 클라이언트가 위임(delegation) 바인딩돼 있어
 * 남의 온톨로지는 보이지 않고, 없는 것과 똑같이 예외가 된다 — 호출부는 그 예외를 잡지 말고
 * 그대로 올려 Neo4j 를 조회조차 하지 않아야 한다.
 *
 * 쓰기·스키마 경로(적재·표 투영·추론·describe·검수 반영)용이라 읽기 판정(WD-28)은 하지 않는다 — 읽기 제한·판정 장애가
 * 쓰기를 막으면 안 된다(스펙 §6). 그래프를 읽는 지점은 resolveReadableOntologyById 를 쓴다.
 */
export async function resolveOntologyById(apiClient: ResolverClient, ontologyId: number): Promise<ResolvedOntology> {
  const ontology = deserializeOntology(await apiClient.getOntologyById(ontologyId));
  // 위 왕복이 RLS 경계다 — 남의 온톨로지면 여기까지 오지 못하고 예외가 난다.
  // 이 파일이 브랜드를 만드는 **유일한** 곳이라, 그래프를 만지는 함수들은 이 경로를 거칠 수밖에 없다
  // (근거는 verified-ontology-id.ts — 크로스테넌트 논거의 정본).
  return { ontology, ontologyId: ontologyId as VerifiedOntologyId };
}

/**
 * 그래프 **읽기** 지점 전용 해소(WD-28) — 테넌트 경계(resolveOntologyById)를 거친 뒤 읽기 판정을 묻는다.
 * 쓰는 곳은 /agent/graph, graphrag_query, graphrag_structured_query, run-eval 뿐이다. 판정 결과는 throw 하지 않고
 * 값으로 돌려준다 — 라우트는 403/502 로, MCP 도구는 requireGraphReadable 로 각자 응답 형태를 정한다.
 * 테넌트 경계 실패(getOntologyById 예외)는 그대로 전파한다.
 */
export async function resolveReadableOntologyById(
  apiClient: ReadableResolverClient,
  ontologyId: number,
): Promise<ResolvedReadableOntology> {
  const resolved = await resolveOntologyById(apiClient, ontologyId);
  return { ...resolved, readable: await judgeGraphReadable(apiClient, resolved.ontologyId) };
}

/**
 * 그래프 읽기 판정(WD-28). 브랜드 GraphReadableOntologyId 를 만드는 **유일한** 곳이다.
 * - 대행 사용자가 없으면 판정할 주체가 없으므로 묻지 않고 'restricted'. (실경로에서는 FireHubApiClient 가 X-On-Behalf-Of 를
 *   항상 싣고, 그것이 없으면 api 가 인증 자체를 세우지 않아 getOntologyById 가 먼저 실패한다 — 그래도 명시한다.)
 * - `graphReadable === true` 만 통과 — api 가 답했는데 false·필드 없음·형식 이상이면 'restricted'(fail-closed).
 * - 판정 조회 자체의 실패(네트워크·5xx·타임아웃·404)는 'unavailable' — 읽기는 막되 "권한 없음"과 구분해,
 *   사용자가 일시 장애를 권한 문제로 오해하지 않고 재시도할 수 있게 한다. 로그를 남긴다(무로그 실패 금지, #308).
 */
async function judgeGraphReadable(
  apiClient: ReadableResolverClient,
  ontologyId: VerifiedOntologyId,
): Promise<GraphReadVerdict> {
  if (apiClient.hasDelegatedUser !== true) return 'restricted';
  try {
    const access = await apiClient.getOntologyGraphAccess(ontologyId);
    return access?.graphReadable === true ? (ontologyId as GraphReadableOntologyId) : 'restricted';
  } catch (e) {
    console.error(`[graphrag] 그래프 읽기 판정 조회 실패(ontologyId=${ontologyId}) — 읽기를 막는다:`, e);
    return 'unavailable';
  }
}

/**
 * 데이터셋에 바인딩된 온톨로지를 해소한다.
 *
 * 바인딩 조회 자체가 실패하면(네트워크 오류 등) 그대로 전파한다 — 조용히 진행하면 어떤 온톨로지로
 * 적재/조회했는지 알 수 없는 상태가 되고, 그 실수는 그래프가 이미 잘못 적재된 뒤에야 드러난다.
 * 바인딩이 없으면(ontologyId === null) 사용자에게 그대로 보여줄 수 있는 에러를 던진다.
 *
 * 쓰기·스키마 경로용이라 그래프 읽기 판정(WD-28)은 하지 않는다 — 읽기 지점은 resolveReadableOntologyById 를 쓴다.
 */
export async function resolveDatasetOntology(
  apiClient: ResolverClient & Pick<FireHubApiClient, 'getDatasetOntology'>,
  datasetId: number,
): Promise<ResolvedOntology> {
  const { ontologyId } = await apiClient.getDatasetOntology(datasetId);
  if (ontologyId == null) {
    throw new Error(
      `데이터셋 ${datasetId}은(는) 온톨로지에 연결되어 있지 않습니다. `
        + '먼저 온톨로지를 연결하세요(graphrag_bind_ontology 또는 데이터셋 상세 화면의 "온톨로지 연결").',
    );
  }
  return resolveOntologyById(apiClient, ontologyId);
}
