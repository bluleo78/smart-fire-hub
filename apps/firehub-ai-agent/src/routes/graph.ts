import { Router } from 'express';
import { z } from 'zod/v4';
import { internalAuth } from '../middleware/auth.js';
import { readWholeGraph } from '../graphrag/neo4j-client.js';
import { mergeEntities } from '../graphrag/synonym-merge.js';
import { setEntityProperty } from '../graphrag/property-mutation.js';
import { addEntity, AddEntityInput } from '../graphrag/entity-add.js';
import { addRelation } from '../graphrag/relation-add.js';
import { EntityType, RelationType } from '../graphrag/ontology.js';
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
  } catch (e) {
    // 무로그 502 금지(#308) — 로그가 없으면 원인 추적이 불가능하다.
    console.error('[graph] readWholeGraph 실패:', e);
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
  } catch (e) {
    console.error('[graph] merge-entities 실패:', e);
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
  } catch (e) {
    console.error('[graph] set-property 실패:', e);
    res.status(502).json({ error: 'set property failed' });
  }
});

const addEntityBodySchema = z.object({
  entityType: z.string().min(1),
  name: z.string().min(1),
  properties: z.record(z.string(), z.union([z.number(), z.string()])).optional(),
  sourceChunkIds: z.array(z.number()).default([]),
  relations: z.array(z.object({
    relType: z.string().min(1),
    direction: z.enum(['out', 'in']),
    otherKey: z.string().min(1),
  })).default([]),
});

// HITL 승인된 저신뢰 엔티티를 Neo4j에 적재 — firehub-api(GraphMutationClient)가 승인 시 호출.
// entityKey가 typeId 기반이라 entityType→typeId 변환에 온톨로지가 필요(merge-entities와 동일 관례, 시스템유저 id=1).
router.post('/graph/add-entity', internalAuth, async (req, res) => {
  const parsed = addEntityBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080/api/v1';
    const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
    const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, 1);
    const ontology = await loadOntology(apiClient);
    await addEntity(ontology, {
      entityType: parsed.data.entityType as EntityType,
      name: parsed.data.name,
      properties: parsed.data.properties,
      sourceChunkIds: parsed.data.sourceChunkIds,
      relations: parsed.data.relations.map((r) => ({ ...r, relType: r.relType as RelationType })) as AddEntityInput['relations'],
    });
    res.status(204).send();
  } catch (e) {
    console.error('[graph] add-entity 실패:', e);
    res.status(502).json({ error: 'add entity failed' });
  }
});

const addRelationBodySchema = z.object({
  subjectKey: z.string().min(1),
  relType: z.string().min(1),
  objectKey: z.string().min(1),
  sourceChunkIds: z.array(z.number()).default([]),
});

// HITL 승인된 저신뢰 관계를 Neo4j에 적재 — firehub-api(GraphMutationClient)가 승인 시 호출.
// 엣지 schemaVersion 스탬프를 위해 온톨로지를 로드한다(add-entity와 동일 관례, 시스템유저 id=1).
router.post('/graph/add-relation', internalAuth, async (req, res) => {
  const parsed = addRelationBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080/api/v1';
    const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
    const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, 1);
    const ontology = await loadOntology(apiClient);
    await addRelation(ontology.schemaVersion, parsed.data.subjectKey, parsed.data.relType as RelationType,
      parsed.data.objectKey, parsed.data.sourceChunkIds);
    res.status(204).send();
  } catch (e) {
    console.error('[graph] add-relation 실패:', e);
    res.status(502).json({ error: 'add relation failed' });
  }
});

export default router;
