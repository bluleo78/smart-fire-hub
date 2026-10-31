// 그래프 노드 속성 중 사용자 입력과 충돌하면 안 되는 예약 키 — identity/provenance 필드.
// loader 가 정의하던 것을 따로 둔 이유(WD-382): neo4j-client(그래프 읽기)도 노드의 사용자 속성만 골라내려고
// 이 목록이 필요한데, loader 가 neo4j-client 를 import 하므로 loader 에서 가져오면 순환 import 가 된다.
// entity-add.ts·property-mutation.ts 는 지금처럼 loader 의 재내보내기를 쓴다(로컬 복제 금지 — 드리프트 방지).
export const RESERVED_NODE_KEYS: ReadonlySet<string> = new Set([
  'key', 'type', 'name', 'sourceChunkIds', 'sourceDatasetIds', 'schemaVersion', 'ontologyId',
]);
