// 일회성 마이그레이션(#308): schemaVersion 스탬프(426b69c3) 도입 이후 적재된 노드/엣지의
// schemaVersion이 FLOAT(1.0)로 저장돼 있다. 쓰기측이 plain JS number를 바인딩했고 neo4j-driver가
// 이를 Cypher FLOAT로 직렬화했기 때문이다. 쓰기측은 neo4j.int()로 고쳤으나 이미 적재된 값은 FLOAT로
// 남으므로 이 스크립트로 INTEGER로 정정한다.
//
// 실행이 "필수"는 아니다: 읽기측(neo4j-client.ts toJsNumber)이 FLOAT/INTEGER를 모두 받도록 방어하므로
// 스크립트를 돌리지 않아도 그래프 조회는 정상 동작한다. 이 스크립트는 저장 타입을 설계 의도(INTEGER)로
// 되돌리는 정리 작업이며, 배포 게이트가 아니다. 그래서 기동 시 마이그레이션이 아니라 일회성 스크립트로
// 둔다(bootstrapConstraints는 ingest마다 호출되므로, 일회성 과거 유물 정리를 그곳에 넣으면 영구 비용).
//
// 멱등성: toInteger()는 이미 INTEGER인 값에 대해 no-op이므로 몇 번 실행해도 안전하다.
// 실행: cd apps/firehub-ai-agent && npx tsx src/graphrag/migrate-schema-version-to-int.ts
//   (NEO4J_URI/NEO4J_USER/NEO4J_PASSWORD 는 getDriver 기본값 또는 환경변수로 주입 — 직접 tsx 실행 시
//    .env가 자동 로드되지 않으므로 필요하면 inline으로 넘긴다)
import { getSession, closeDriver } from './neo4j-client.js';

async function main() {
  const session = getSession();
  try {
    const nodeRes = await session.run(
      `MATCH (n:Entity) WHERE n.schemaVersion IS NOT NULL
       SET n.schemaVersion = toInteger(n.schemaVersion)
       RETURN count(n) AS migrated`,
    );
    console.log(`[migrate] 노드 ${nodeRes.records[0].get('migrated')}개 schemaVersion → INTEGER`);

    const relRes = await session.run(
      `MATCH ()-[r:REL]->() WHERE r.schemaVersion IS NOT NULL
       SET r.schemaVersion = toInteger(r.schemaVersion)
       RETURN count(r) AS migrated`,
    );
    console.log(`[migrate] 엣지 ${relRes.records[0].get('migrated')}개 schemaVersion → INTEGER`);
  } finally {
    await session.close();
    await closeDriver();
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
