import { Router, type Response } from 'express';
import { z } from 'zod/v4';
import { internalAuth, requireDelegation, type Delegation } from '../middleware/auth.js';
import { isValidTenantId } from '../agent/tenant-paths.js';
import { readWholeGraph } from '../graphrag/neo4j-client.js';
import { mergeEntities } from '../graphrag/synonym-merge.js';
import { setEntityProperty } from '../graphrag/property-mutation.js';
import { addEntity, AddEntityInput } from '../graphrag/entity-add.js';
import { addRelation } from '../graphrag/relation-add.js';
import { EntityType, RelationType } from '../graphrag/ontology.js';
import { GraphMutationRejectedError } from '../graphrag/graph-mutation-guard.js';
import { resolveDatasetOntology, resolveOntologyById } from '../graphrag/ontology-source.js';
import { FireHubApiClient } from '../mcp/api-client.js';

/**
 * resolveDatasetOntology(apiClient, datasetId) 호출부 공통 참고 — entityType 문자열 → typeId 변환에 쓸,
 * datasetId 에 **바인딩된** 온톨로지를 로드한다. resolver.ts 의 entityKey 는 `<entity_type_id>:<name>` 이고,
 * 적재는 바인딩된 온톨로지의 typeId 를 쓰므로, 다른 온톨로지로 변환하면 데이터셋의 키와 어긋난다.
 *
 * datasetId 는 필수다 — "기본 온톨로지" 폴백은 없다(#678). GET /api/v1/ontology 자체가
 * firehub-api 에서 제거되어, 예전의 폴백 경로는 어차피 네트워크 계층에서 항상 실패했다.
 *
 * ontology 뿐 아니라 ontologyId 도 그대로 반환한다 — add-entity/add-relation 이 Neo4j 노드/엣지에
 * ontologyId 를 스탬프해야 "구버전" 판정(loader.ts 와 동일 관용구)이 가능하다(#678).
 */

// 온톨로지 시각화용 읽기 전용 + HITL 승인 병합 라우터. 온톨로지 스키마는 api DB 소유로 이관됨(이 라우트 제거).
// 5-6: 엔티티 타입 리네임은 이제 순수 DB 연산(entity_type_id 보존)이라 Neo4j 마이그레이션 라우트가
// 불필요해져 제거했다(5-5의 POST /graph/rename-type — resolver.ts entityKey 참조).
const router = Router();

/**
 * `requireDelegation` 이 확정한 주체로, 그 사용자·테넌트를 대행해 api 를 역호출하는 클라이언트를
 * 만든다. 검증과 400 응답은 미들웨어가 끝냈으므로 여기서는 널을 돌려주지 않는다.
 */
function delegationClient(res: Response): FireHubApiClient {
  const { userId, tenantId } = res.locals.delegation as Delegation;
  const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
  return new FireHubApiClient(apiBaseUrl, internalToken, userId, tenantId);
}

/**
 * 그래프 변경 실패를 상태코드로 나눠 응답한다(#310).
 * - GraphMutationRejectedError 계열: "지금 이 요청으로는 반영 불가"인 상태 → 409 + 사용자용 사유.
 *   대상 노드 부재(GraphTargetMissingError, #310)와 정정값 형식 위반(PropertyValueInvalidError, #311)이 여기 해당한다.
 *   firehub-api가 이 사유를 그대로 ErrorResponse.message로 올려 검수 UI 토스트에 노출하고, 항목은 pending으로 남는다.
 * - 그 외: 진짜 장애 → 502(무로그 502 금지 #308 — 항상 로그를 남긴다).
 */
function respondMutationError(res: import('express').Response, opLabel: string, fallback: string, e: unknown): void {
  console.error(`[graph] ${opLabel} 실패:`, e);
  if (e instanceof GraphMutationRejectedError) {
    res.status(409).json({ error: 'graph mutation rejected', message: e.message });
    return;
  }
  res.status(502).json({ error: fallback });
}

// 한 온톨로지의 지식그래프(Neo4j). 읽기 실패 시 502(상위 프록시가 그대로 전파).
//
// ontologyId 는 필수 쿼리 파라미터다 — 선택 인자로 두면 무스코프 전체 조회 경로가 그대로 살아남아,
// 내부 호출자 누구나 전 테넌트 그래프를 읽을 수 있다.
//
// 소유권 검증은 변형 라우트 네 개와 **똑같이** 여기서 한다: requireDelegation 으로 주체를 확정하고,
// 그 주체를 대행해 RLS 걸린 ontology 테이블을 되읽는다(resolveOntologyById). 예전에는 "호출부인
// firehub-api 가 이미 확인했다"며 값을 그대로 믿었는데, 내부 토큰은 만능 자격증명이라 그 말은
// "ai-agent 쪽에는 검증 지점이 없다"와 같았다 — 내부망에 닿는 누구나 전 테넌트 그래프를 읽을 수 있었다.
// api 쪽 확인이 사라진 것은 아니고(OntologyService#getGraph), 두 겹이 된 것이다.
router.get('/graph', internalAuth, requireDelegation, async (req, res) => {
  const ontologyId = Number(req.query.ontologyId);
  // 양수 정수 판정은 isValidTenantId 를 그대로 재사용한다 — 이름은 테넌트지만 그 술어의 주석이
  // "복제하고 주석으로 같은 강도로 맞춰라" 방식은 이미 한 번 어긋났다고 못박고 있고, auth.ts 가
  // userId 에도 같은 술어를 쓴다. 쿼리 파라미터 검증은 이 리포에서 zod 가 아니라 이 관용구다
  // (chat.ts 의 tenantId 가드와 동일 — `Number('')` 이 0 으로 통과하는 구멍도 함께 막힌다).
  if (!isValidTenantId(ontologyId)) {
    res.status(400).json({ error: 'ontologyId is required' });
    return;
  }
  try {
    // 이 왕복이 테넌트 경계다 — 남의 온톨로지면 RLS 때문에 "없는 것"과 같아져 예외가 나고,
    // Neo4j 는 조회조차 하지 않는다. 통과한 값만 VerifiedOntologyId 라 readWholeGraph 에 들어간다.
    const { ontologyId: verified } = await resolveOntologyById(delegationClient(res), ontologyId);
    res.json(await readWholeGraph(verified));
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
  // 바인딩된 온톨로지로 typeId 를 변환하는 데 필수다 — 이유는 상단 resolveDatasetOntology 주석 참고.
  datasetId: z.number(),
});

// HITL 승인된 근접쌍 동기 병합 — firehub-api(SynonymMergeClient)가 승인 시 호출한다.
// entityKey가 typeId 기반(5-6)이라 entityType 문자열→typeId 변환에 온톨로지가 필요하다.
// 사용자 세션이 없는 서비스 간 호출이라, on-behalf-of는 시스템 사용자(id=1)로 고정한다
// (다른 백엔드 트리거 스크립트들과 동일한 관례 — migrate-entity-keys-to-id.ts, run-eval.ts 참고).
router.post('/graph/merge-entities', internalAuth, requireDelegation, async (req, res) => {
  const parsed = mergeBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    const apiClient = delegationClient(res);
    const { ontology, ontologyId } = await resolveDatasetOntology(apiClient, parsed.data.datasetId);
    await mergeEntities(ontology, ontologyId, parsed.data.entityType as EntityType, parsed.data.nameA, parsed.data.nameB);
    res.status(204).send();
  } catch (e) {
    respondMutationError(res, 'merge-entities', 'entity merge failed', e);
  }
});

const setPropertyBodySchema = z.object({
  entityKey: z.string().min(1),
  propertyName: z.string().min(1),
  dataType: z.enum(['text', 'number', 'date']),
  value: z.string(),
  // 다른 세 변형 라우트와 같은 이유로 필수다 — 여기서는 typeId 변환이 아니라 **스코프**가 목적이다.
  // entityKey 는 추측 가능한 문자열이라, 이 값 없이는 남의 온톨로지 노드를 덮어쓰는 것을 막을 수 없다.
  datasetId: z.number(),
});

// HITL 승인된 속성 정정값을 Neo4j 노드에 write — firehub-api(GraphMutationClient)가 승인 시 호출.
// 예전에는 이 라우트만 requireDelegation 없이 entityKey 를 그대로 받아 write 했다 — 내부 토큰만 있으면
// 임의 키의 노드 속성을 고칠 수 있었다. 이제 datasetId→온톨로지 해소(RLS 경계)를 거쳐 스코프된 write 만 한다.
router.post('/graph/set-property', internalAuth, requireDelegation, async (req, res) => {
  const parsed = setPropertyBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    const apiClient = delegationClient(res);
    const { ontologyId } = await resolveDatasetOntology(apiClient, parsed.data.datasetId);
    await setEntityProperty(ontologyId, parsed.data.entityKey, parsed.data.propertyName,
      parsed.data.dataType, parsed.data.value);
    res.status(204).send();
  } catch (e) {
    respondMutationError(res, 'set-property', 'set property failed', e);
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
  // 바인딩된 온톨로지로 typeId 를 변환하는 데 필수다 — 이유는 상단 resolveDatasetOntology 주석 참고.
  datasetId: z.number(),
});

// HITL 승인된 저신뢰 엔티티를 Neo4j에 적재 — firehub-api(GraphMutationClient)가 승인 시 호출.
// entityKey가 typeId 기반이라 entityType→typeId 변환에 온톨로지가 필요(merge-entities와 동일 관례, 시스템유저 id=1).
router.post('/graph/add-entity', internalAuth, requireDelegation, async (req, res) => {
  const parsed = addEntityBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    const apiClient = delegationClient(res);
    const { ontology, ontologyId } = await resolveDatasetOntology(apiClient, parsed.data.datasetId);
    await addEntity(ontology, ontologyId, {
      entityType: parsed.data.entityType as EntityType,
      name: parsed.data.name,
      properties: parsed.data.properties,
      sourceChunkIds: parsed.data.sourceChunkIds,
      relations: parsed.data.relations.map((r) => ({ ...r, relType: r.relType as RelationType })) as AddEntityInput['relations'],
    });
    res.status(204).send();
  } catch (e) {
    // add-entity는 409 매핑 대상이 아니다(#310) — 노드 MERGE는 MATCH 없이 항상 생성되므로 무음 유실이 없고,
    // 끝점이 아직 없는 보류 관계를 건너뛰는 것은 "양쪽 보류는 마지막 승인 때 생성"이라는 설계상 정상 동작이다.
    console.error('[graph] add-entity 실패:', e);
    res.status(502).json({ error: 'add entity failed' });
  }
});

const addRelationBodySchema = z.object({
  subjectKey: z.string().min(1),
  relType: z.string().min(1),
  objectKey: z.string().min(1),
  sourceChunkIds: z.array(z.number()).default([]),
  // 바인딩된 온톨로지로 typeId 를 변환하는 데 필수다 — 이유는 상단 resolveDatasetOntology 주석 참고.
  datasetId: z.number(),
});

// HITL 승인된 저신뢰 관계를 Neo4j에 적재 — firehub-api(GraphMutationClient)가 승인 시 호출.
// 엣지 schemaVersion 스탬프를 위해 온톨로지를 로드한다(add-entity와 동일 관례).
router.post('/graph/add-relation', internalAuth, requireDelegation, async (req, res) => {
  const parsed = addRelationBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    const apiClient = delegationClient(res);
    const { ontology, ontologyId } = await resolveDatasetOntology(apiClient, parsed.data.datasetId);
    // relType은 zod의 문자열 검사만 거친 값이므로 온톨로지 대조는 addRelation이 담당한다(#319).
    // 위반 시 OntologyConformanceError → respondMutationError가 409 + 한국어 사유로 매핑한다
    // (400이 아니라 409인 이유: firehub-api의 GraphMutationClient는 409만 사유 문구를 살려 올린다).
    await addRelation(ontology, ontologyId, parsed.data.subjectKey, parsed.data.relType as RelationType,
      parsed.data.objectKey, parsed.data.sourceChunkIds);
    res.status(204).send();
  } catch (e) {
    respondMutationError(res, 'add-relation', 'add relation failed', e);
  }
});

export default router;
