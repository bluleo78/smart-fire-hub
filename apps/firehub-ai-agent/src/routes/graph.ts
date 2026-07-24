import { Router } from 'express';
import { z } from 'zod/v4';
import { internalAuth } from '../middleware/auth.js';
import { readWholeGraph } from '../graphrag/neo4j-client.js';
import { mergeEntities } from '../graphrag/synonym-merge.js';
import { setEntityProperty } from '../graphrag/property-mutation.js';
import { EntityType } from '../graphrag/ontology.js';
import { loadOntology } from '../graphrag/ontology-source.js';
import { FireHubApiClient } from '../mcp/api-client.js';

// 온톨로지 시각화용 읽기 전용 + HITL 승인 병합 라우터. 온톨로지 스키마는 api DB 소유로 이관됨(이 라우트 제거).
// 5-6: 엔티티 타입 리네임은 이제 순수 DB 연산(entity_type_id 보존)이라 Neo4j 마이그레이션 라우트가
// 불필요해져 제거했다(5-5의 POST /graph/rename-type — resolver.ts entityKey 참조).
const router = Router();

// 전체 지식그래프(Neo4j). 읽기 실패 시 502(상위 프록시가 그대로 전파).
router.get('/graph', internalAuth, async (_req, res) => {
  try {
    res.json(await readWholeGraph());
  } catch {
    res.status(502).json({ error: 'graph read failed' });
  }
});

const mergeBodySchema = z.object({
  entityType: z.string().min(1),
  nameA: z.string().min(1),
  nameB: z.string().min(1),
});

// HITL 승인된 근접쌍 동기 병합 — firehub-api(SynonymMergeClient)가 승인 시 호출한다.
// entityKey가 typeId 기반(5-6)이라 entityType 문자열→typeId 변환에 온톨로지가 필요하다.
// 사용자 세션이 없는 서비스 간 호출이라, on-behalf-of는 시스템 사용자(id=1)로 고정한다
// (다른 백엔드 트리거 스크립트들과 동일한 관례 — migrate-entity-keys-to-id.ts, run-eval.ts 참고).
router.post('/graph/merge-entities', internalAuth, async (req, res) => {
  const parsed = mergeBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080/api/v1';
    const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
    const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, 1);
    const ontology = await loadOntology(apiClient);
    await mergeEntities(ontology, parsed.data.entityType as EntityType, parsed.data.nameA, parsed.data.nameB);
    res.status(204).send();
  } catch {
    res.status(502).json({ error: 'entity merge failed' });
  }
});

const setPropertyBodySchema = z.object({
  entityKey: z.string().min(1),
  propertyName: z.string().min(1),
  dataType: z.enum(['text', 'number', 'date']),
  value: z.string(),
});

// HITL 승인된 속성 정정값을 Neo4j 노드에 write — firehub-api(GraphMutationClient)가 승인 시 호출.
router.post('/graph/set-property', internalAuth, async (req, res) => {
  const parsed = setPropertyBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    await setEntityProperty(parsed.data.entityKey, parsed.data.propertyName, parsed.data.dataType, parsed.data.value);
    res.status(204).send();
  } catch {
    res.status(502).json({ error: 'set property failed' });
  }
});

export default router;
