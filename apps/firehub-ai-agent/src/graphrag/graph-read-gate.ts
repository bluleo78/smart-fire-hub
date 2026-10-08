// 지식그래프 읽기 게이트(WD-28)의 ai-agent 쪽 정본 — 제한 코드·문구와, 읽기 지점이 브랜드 타입을 요구하는 헬퍼.
// ontology-source.ts 와 분리한 이유: 도구·라우트 테스트가 ontology-source 모듈을 통째로 vi.mock 하는데, 이 헬퍼까지
// 목이 되면 "제한이면 Neo4j 를 부르지 않는다"를 실제 코드로 검증할 수 없다.
import type { GraphReadableOntologyId } from './verified-ontology-id.js';

/** firehub-api·web 과 공유하는 제한 코드. */
export const GRAPH_READ_RESTRICTED_CODE = 'GRAPH_READ_RESTRICTED';

/** MCP 도구·채팅이 그대로 전달하는 문구(스펙 §5 원문). 어느 데이터셋 때문인지는 밝히지 않는다. */
export const GRAPH_READ_RESTRICTED_MESSAGE =
  '이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 조회할 수 없습니다.';

/** 그래프 읽기 제한. safeTool 이 message 를 그대로 isError 결과로 돌려준다. */
export class GraphReadRestrictedError extends Error {
  readonly code = GRAPH_READ_RESTRICTED_CODE;

  constructor() {
    super(GRAPH_READ_RESTRICTED_MESSAGE);
    this.name = 'GraphReadRestrictedError';
  }
}

/** 판정 조회 자체가 실패했을 때(네트워크·5xx·타임아웃·404)의 코드 — "권한 없음"(GRAPH_READ_RESTRICTED)과 구분한다. */
export const GRAPH_READ_CHECK_FAILED_CODE = 'GRAPH_READ_CHECK_FAILED';

/** 판정 조회 실패 문구 — 일시 장애이므로 재시도를 안내한다. */
export const GRAPH_READ_CHECK_FAILED_MESSAGE = '지식그래프 열람 권한을 확인하지 못했습니다. 잠시 후 다시 시도하세요.';

/** 그래프 읽기 판정 조회 실패. 읽기는 막지만(fail-closed) 권한 없음과 다른 오류로 알린다. */
export class GraphReadCheckFailedError extends Error {
  readonly code = GRAPH_READ_CHECK_FAILED_CODE;

  constructor() {
    super(GRAPH_READ_CHECK_FAILED_MESSAGE);
    this.name = 'GraphReadCheckFailedError';
  }
}

/**
 * 그래프 읽기 판정 결과 — 통과하면 읽기 브랜드 id, 아니면 'restricted'(권한 없음) 또는 'unavailable'(판정 조회 실패).
 * 값은 ontology-source.ts 의 resolveReadableOntologyById 만 만든다.
 */
export type GraphReadVerdict = GraphReadableOntologyId | 'restricted' | 'unavailable';

/**
 * 읽기 지점 전용 — 판정이 통과가 아니면 예외를 던지고, 통과면 읽기 타입을 돌려준다.
 * 'restricted' → GraphReadRestrictedError, 'unavailable' → GraphReadCheckFailedError.
 * 쓰기 경로는 판정 자체를 하지 않는다(읽기 제한이 적재·검수 반영을 막으면 안 된다, 스펙 §6).
 */
export function requireGraphReadable(verdict: GraphReadVerdict): GraphReadableOntologyId {
  if (verdict === 'restricted') throw new GraphReadRestrictedError();
  if (verdict === 'unavailable') throw new GraphReadCheckFailedError();
  return verdict;
}
