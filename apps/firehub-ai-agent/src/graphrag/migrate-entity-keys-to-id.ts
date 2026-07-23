// 일회성 마이그레이션(5-6): 스탬프 도입 이전에 적재된 Neo4j 노드의 key("<type문자열>:<정규화이름>")를
// entity_type_id 기반("<id>:<정규화이름>")으로 재작성한다. 이 스크립트를 실행한 이후부터는 엔티티 타입
// 리네임이 Neo4j에 전혀 영향을 주지 않는다(entityKey가 더 이상 타입 문자열을 담지 않으므로).
//
// 실행 전제: 이 마이그레이션 시점의 api DB entity_type_id 스냅샷을 기준으로 삼는다 — 마이그레이션 이후에
// 이뤄지는 리네임은 OntologyRepository가 id를 UPDATE로 보존하므로 별도 조치 없이 안전하다.
//
// 멱등성: 이미 "id:이름" 형식으로 바뀐 노드는 건드리지 않는다(WHERE n.key STARTS WITH 원본 타입명 + ':').
// 실행: cd apps/firehub-ai-agent && npx tsx src/graphrag/migrate-entity-keys-to-id.ts
import { FireHubApiClient } from '../mcp/api-client.js';
import { getSession, closeDriver } from './neo4j-client.js';

async function main() {
  const apiBaseUrl = process.env.API_BASE_URL ?? 'http://localhost:5010/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN;
  const userId = Number(process.env.MIGRATION_USER_ID ?? '0');
  if (!internalToken) throw new Error('INTERNAL_SERVICE_TOKEN 환경변수가 필요합니다.');
  if (!userId) throw new Error('MIGRATION_USER_ID 환경변수(dataset:read 권한을 가진 사용자 id)가 필요합니다.');

  const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, userId);
  const ontology = await apiClient.getOntology();

  const session = getSession();
  const skipped: string[] = [];
  try {
    // 현재 Neo4j에 존재하는 서로 다른 type 문자열 목록(적재된 적 있는 타입만 대상).
    const typesInGraph = await session.run('MATCH (n:Entity) RETURN DISTINCT n.type AS type');
    for (const record of typesInGraph.records) {
      const type: string = record.get('type');
      const def = ontology.entities.find((e) => e.type === type);
      if (!def || def.id == null) {
        // 이 시점 온톨로지에 없는 타입 = 마이그레이션 실행 전에 이미 리네임/삭제됨.
        // 이 노드들은 id를 부여받지 못해 향후 재적재와 절대 MERGE되지 않는 고아로 영구히 남는다
        // (silent skip 금지 — 반드시 사람이 보고 수동 대응하도록 실패로 처리한다).
        skipped.push(type);
        continue;
      }
      const result = await session.run(
        `MATCH (n:Entity {type: $type})
         WHERE n.key STARTS WITH $type + ':'
         SET n.key = $id + ':' + substring(n.key, size($type) + 1)
         RETURN count(n) AS migrated`,
        { type, id: def.id },
      );
      const migrated = result.records[0].get('migrated').toNumber();
      console.log(`[migrate] ${type} → id ${def.id}: 노드 ${migrated}개 마이그레이션`);
    }
  } finally {
    await session.close();
    await closeDriver();
  }

  if (skipped.length > 0) {
    console.error(
      `[migrate] 실패 — 현재 온톨로지에 없는(이미 리네임/삭제된) 타입 ${skipped.length}개를 건너뛰었습니다: `
      + `${skipped.join(', ')}. 이 타입의 노드는 id 없이 남아 향후 재적재와 병합되지 않는 고아가 됩니다.\n`
      + `→ 이 스크립트는 5-6 배포 직후, 어떤 리네임도 일어나기 전에 실행해야 합니다. `
      + `이미 리네임이 발생했다면 해당 노드를 수동으로 조사·정리하세요.`,
    );
    process.exit(1);
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
