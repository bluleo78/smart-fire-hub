// 추출 시점 온톨로지 소스 — 데이터셋에 바인딩된 온톨로지를 api(DB 소유)에서 fetch한다.
// "기본 온톨로지" 폴백은 존재하지 않는다: 바인딩이 없으면 이 함수는 명확히 실패하고, 호출부가
// 그 실패를 그대로 사용자에게 전달해 "먼저 온톨로지를 연결하라"고 안내해야 한다(#678).
import type { FireHubApiClient } from '../mcp/api-client.js';
import { Ontology, deserializeOntology } from './ontology.js';
import { GraphReadableOntologyId, VerifiedOntologyId } from './verified-ontology-id.js';

/** 해소 결과. readableOntologyId 는 그래프 **읽기** 판정까지 통과했을 때만 값이 있다(WD-28). */
export interface ResolvedOntology {
  ontology: Ontology;
  /** 테넌트 경계를 통과한 id — 쓰기·스키마 경로가 쓴다. 읽기 제한이어도 항상 있다. */
  ontologyId: VerifiedOntologyId;
  /** 그래프 읽기 판정 통과 시에만 값. 읽기 지점은 requireGraphReadable 로 좁힌다. */
  readableOntologyId: GraphReadableOntologyId | null;
}

/** 해소 함수가 api 클라이언트에서 쓰는 최소 표면 — 테넌트 경계 왕복 + 그래프 읽기 판정. */
type ResolverClient = Pick<FireHubApiClient, 'getOntologyById' | 'getOntologyGraphAccess' | 'hasDelegatedUser'>;

/**
 * id 로 온톨로지를 해소한다 — **그리고 그것이 요청자 테넌트의 것인지 확인한다.**
 *
 * 이 왕복이 그래프 경로의 유일한 테넌트 경계다. Neo4j 는 전 테넌트가 공유하는 단일 DB 라
 * RLS 가 없고 노드에 tenant_id 도 없으므로, "이 ontologyId 를 써도 되는가"는 RLS 가 걸린
 * firehub-api 의 ontology 테이블만 답할 수 있다. api 클라이언트가 위임(delegation) 바인딩돼 있어
 * 남의 온톨로지는 보이지 않고, 없는 것과 똑같이 예외가 된다 — 호출부는 그 예외를 잡지 말고
 * 그대로 올려 Neo4j 를 조회조차 하지 않아야 한다.
 *
 * 소유권 확인만 필요한 호출부(graphrag_query)도 이 함수를 쓴다. `await getOntologyById(id)` 를
 * 결과 버리고 호출하는 형태보다, 이름이 목적을 말하는 편이 다음 사람이 지우지 않는다.
 *
 * WD-28: 그래프 **읽기** 판정(graph-access)도 함께 받아 readableOntologyId 를 정한다. 읽기 제한이어도 throw 하지
 * 않는다 — 이 함수는 쓰기·스키마 경로(적재·표 투영·추론·describe·검수 반영)도 쓰기 때문이다. 읽기 지점만
 * requireGraphReadable 로 좁힌다.
 */
export async function resolveOntologyById(apiClient: ResolverClient, ontologyId: number): Promise<ResolvedOntology> {
  const ontology = deserializeOntology(await apiClient.getOntologyById(ontologyId));
  // 위 왕복이 RLS 경계다 — 남의 온톨로지면 여기까지 오지 못하고 예외가 난다.
  // 이 파일이 브랜드를 만드는 **유일한** 곳이라, 그래프를 만지는 함수들은 이 경로를 거칠 수밖에 없다
  // (근거는 verified-ontology-id.ts — 크로스테넌트 논거의 정본).
  const verified = ontologyId as VerifiedOntologyId;
  return { ontology, ontologyId: verified, readableOntologyId: await judgeGraphReadable(apiClient, verified) };
}

/**
 * 그래프 읽기 판정(WD-28). 브랜드 GraphReadableOntologyId 를 만드는 **유일한** 곳이다.
 * - 대행 사용자가 없으면 판정할 주체가 없으므로 묻지 않고 null. (실경로에서는 FireHubApiClient 가 X-On-Behalf-Of 를
 *   항상 싣고, 그것이 없으면 api 가 인증 자체를 세우지 않아 위 getOntologyById 가 먼저 실패한다 — 그래도 명시한다.)
 * - `graphReadable === true` 만 통과 — 필드가 없는 구버전 api·형식 이상은 읽기 불가(fail-closed).
 * - 판정 조회 실패도 null 이되 throw 하지 않는다 — 해소 함수는 쓰기 경로(적재·검수 반영)도 쓰므로, 읽기 판정
 *   실패가 쓰기를 막으면 안 된다(스펙 §6). 대신 로그를 남긴다(무로그 실패 금지, #308).
 */
async function judgeGraphReadable(
  apiClient: ResolverClient,
  ontologyId: VerifiedOntologyId,
): Promise<GraphReadableOntologyId | null> {
  if (apiClient.hasDelegatedUser !== true) return null;
  try {
    const access = await apiClient.getOntologyGraphAccess(ontologyId);
    return access?.graphReadable === true ? (ontologyId as GraphReadableOntologyId) : null;
  } catch (e) {
    console.error(`[graphrag] 그래프 읽기 판정 조회 실패(ontologyId=${ontologyId}) — 읽기를 막는다:`, e);
    return null;
  }
}

/**
 * 데이터셋에 바인딩된 온톨로지를 해소한다.
 *
 * 바인딩 조회 자체가 실패하면(네트워크 오류 등) 그대로 전파한다 — 조용히 진행하면 어떤 온톨로지로
 * 적재/조회했는지 알 수 없는 상태가 되고, 그 실수는 그래프가 이미 잘못 적재된 뒤에야 드러난다.
 * 바인딩이 없으면(ontologyId === null) 사용자에게 그대로 보여줄 수 있는 에러를 던진다.
 *
 * 마지막 resolveOntologyById 위임이 그래프 읽기 판정(WD-28)까지 거친다.
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
