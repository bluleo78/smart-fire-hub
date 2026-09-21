// 온톨로지 id 가 "테넌트 경계를 통과한 값"임을 컴파일 타임에 표시하는 브랜드 타입.
//
// 왜 필요한가: Neo4j 는 전 테넌트가 공유하는 단일 DB 라 RLS 가 없고 노드에 tenant_id 도 없다.
// 그래서 "이 ontologyId 를 써도 되는가"의 유일한 답은 RLS 가 걸린 firehub-api 의 ontology 테이블
// 조회뿐이고, 그 왕복을 거친 값과 클라이언트가 그냥 보낸 숫자는 런타임에 똑같은 `number` 다.
// 구분이 타입에 없으면 다음 사람이 `readWholeGraph(Number(req.query.ontologyId))` 를 쓰고,
// 그 코드는 아무 경고 없이 컴파일된다(실제로 한 번 그렇게 샜다).
//
// 브랜드를 붙이면 그 호출이 컴파일되지 않는다. 값을 만들 수 있는 곳은 ontology-source.ts 의
// resolveOntologyById / resolveDatasetOntology 뿐이고, 그 둘은 api 왕복(=RLS 경계)을 반드시 거친다.
// 예외(escape hatch)는 두지 않았다 — 한때 "호출부인 api 가 이미 확인했다"며 GET /agent/graph 에
// 하나 두려 했지만, 내부 토큰은 만능 자격증명이라 그 구멍이 곧 유일하게 중요한 구멍이었다.
// 지금은 그 라우트도 requireDelegation + resolveOntologyById 를 거친다.
//
// 이 파일이 별도 모듈인 이유: loader.ts 같은 저수준 모듈도 이 타입을 써야 하는데,
// ontology-source.ts 는 api 클라이언트에 의존하므로 거기서 가져오면 의존 방향이 뒤집힌다.
//
// **이 파일은 크로스테넌트 근거의 정본이다** — 다른 파일들의 주석은 여기를 가리킨다.
import neo4j, { type Integer } from 'neo4j-driver';

/** RLS 경계를 통과한 온톨로지 id. 런타임에는 그냥 number 다. */
export type VerifiedOntologyId = number & { readonly __verifiedOntologyId: unique symbol };

/**
 * 스코프 술어(`n.ontologyId = $ontologyId`)에 바인딩할 형태로 바꾼다.
 *
 * plain JS number 를 그대로 넘기면 드라이버가 Cypher FLOAT 로 직렬화하는데, 저장된 값은
 * INTEGER 라 비교가 **항상 거짓**이 된다(#308). 그 실패 모양이 고약하다: 읽기는 빈 결과,
 * 쓰기는 조용한 no-op — 둘 다 에러 없이 "데이터가 없는 것처럼" 보인다.
 * 브랜드와 올바른 바인딩을 한 함수에 묶어 두면 둘 중 하나만 맞추는 일이 생기지 않는다.
 */
export function ontologyIdParam(ontologyId: VerifiedOntologyId): Integer {
  return neo4j.int(ontologyId);
}
