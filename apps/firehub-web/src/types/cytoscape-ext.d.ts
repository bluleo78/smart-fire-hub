// Cytoscape 확장 모듈 타입 선언 — 공식 @types가 없어 최소 형태로 선언한다.
// cytoscape.use()에 전달할 수 있는 확장(Ext) 함수 형태만 보장하면 충분하다.
declare module 'cytoscape-fcose' {
  import type { Ext } from 'cytoscape';
  const ext: Ext;
  export default ext;
}
declare module 'cytoscape-expand-collapse' {
  import type { Ext } from 'cytoscape';
  const ext: Ext;
  export default ext;
}
