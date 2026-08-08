// GraphRAG 적재/질의 MCP 도구. register 규약: (apiClient, safeTool, jsonResult) => Tool[]
import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';
import { ingestDataset } from '../../graphrag/ingest.js';
import { extractGraph } from '../../graphrag/extractor.js';
import { createCliCompleter } from '../../graphrag/llm-cli.js';
import { loadGraph, loadTableGraph } from '../../graphrag/loader.js';
import { bootstrapConstraints } from '../../graphrag/neo4j-client.js';
import { retrieve } from '../../graphrag/retriever.js';
import { projectTableDataset, DataPage } from '../../graphrag/table-projection.js';
import { deserializeOntology } from '../../graphrag/ontology.js';
import { profileColumns } from '../../graphrag/column-profiler.js';
import { inferMapping } from '../../graphrag/mapping-inference.js';
import { inferOntology, DatasetEvidence } from '../../graphrag/ontology-inference.js';
// 추출 시점 온톨로지는 api(DB 소유)에서 fetch하고 실패 시 번들 CORE_ONTOLOGY 로 폴백한다.
import { loadOntology, loadOntologyWithSource, resolveDatasetOntology } from '../../graphrag/ontology-source.js';
import { structuredQuery, Filter, Operator } from '../../graphrag/structured-query.js';
import { link as semanticLink } from '../../graphrag/semantic-link.js';

/**
 * 검수 항목을 타입별로 정규화한다.
 *
 * 왜 payload 를 그대로 흘리지 않는가: payload 는 itemType 마다 필드가 다른 자유 JSON 이라,
 * 그대로 넘기면 모델이 타입별 필드명을 추측하거나(환각) 프롬프트에 하드코딩하게 된다.
 * 사람이 읽을 수 있는 한 줄 summary 로 환원해 넘긴다.
 */
export function summarizeReviewItem(item: {
  id: number; itemType: string; status: string; datasetId: number | null;
  signalScore: number | null; reason: string | null; payload: Record<string, unknown>; createdAt?: string;
}): Record<string, unknown> {
  const p = item.payload ?? {};
  const s = (k: string) => (p[k] == null ? '' : String(p[k]));
  let summary: string;
  switch (item.itemType) {
    case 'synonym':
      summary = `동의어 병합 후보: ${s('entityType')} "${s('nameA')}" ↔ "${s('nameB')}"`;
      break;
    case 'property':
      summary = `속성 정규화 실패: ${s('entityType')} ${s('propertyName')}(${s('dataType')}) 원문 "${s('rawText')}"`;
      break;
    case 'entity':
      summary = `저신뢰 엔티티: ${s('entityType')} "${s('name')}"`;
      break;
    case 'relation':
      summary = `저신뢰 관계: "${s('subjectName')}" -[${s('relType')}]-> "${s('objectName')}"`;
      break;
    default:
      summary = `알 수 없는 항목 타입: ${item.itemType}`;
  }
  return {
    id: item.id, itemType: item.itemType, status: item.status, datasetId: item.datasetId,
    confidence: item.signalScore, reason: item.reason, createdAt: item.createdAt, summary,
    // 속성 항목 승인은 정정값이 필요하므로 원문을 별도 필드로도 노출한다.
    ...(item.itemType === 'property' ? { rawText: s('rawText') } : {}),
  };
}

type TableColumn = { columnName: string; dataType: string; isPrimaryKey: boolean };

/**
 * TABLE 데이터셋의 행을 페이지네이션으로 표본 추출한다(200행/페이지, cap행 상한).
 * 컬럼 메타는 첫 페이지에서만 수집한다(이후 페이지에도 같은 값이 반복되므로 중복 불필요).
 * graphrag_infer_mapping 과 collectDatasetEvidence(graphrag_infer_ontology 용)가 동일한 루프를
 * 각자 갖고 있어 드리프트 위험이 있었다 — 이 헬퍼로 통합한다.
 */
async function sampleTableRows(
  apiClient: Pick<FireHubApiClient, 'queryDatasetData'>,
  datasetId: number,
  cap: number,
): Promise<{ columns: TableColumn[]; rows: Record<string, unknown>[] }> {
  const columns: TableColumn[] = [];
  const rows: Record<string, unknown>[] = [];
  let page = 0;
  while (rows.length < cap) {
    const resp = (await apiClient.queryDatasetData(datasetId, { page, size: 200, includeTotalCount: true })) as {
      columns: TableColumn[]; rows: Record<string, unknown>[]; totalPages: number;
    };
    if (page === 0) columns.push(...resp.columns);
    rows.push(...resp.rows);
    page += 1;
    if (page >= resp.totalPages || resp.rows.length === 0) break;
  }
  return { columns, rows };
}

// 데이터셋 1건에서 온톨로지 추론용 근거를 모은다.
// TABLE 은 컬럼 프로파일(infer_mapping 과 동일 표본 규모), DOCUMENT 는 청크 표본,
// FILE 등은 근거로 쓸 수 없어 { skipReason } 을 돌려 호출부가 skipped 에 기록하게 한다.
const EVIDENCE_ROW_CAP = 600;        // 표 표본 행 상한(infer_mapping 과 동일)
const EVIDENCE_CHUNKS_PER_DATASET = 40; // 문서 데이터셋당 청크 표본 상한
const EVIDENCE_CHUNK_CHARS = 1500;   // 청크 1건 본문 절단 길이

async function collectDatasetEvidence(
  apiClient: FireHubApiClient,
  datasetId: number,
): Promise<DatasetEvidence | { skipReason: string }> {
  const meta = (await apiClient.getDataset(datasetId)) as { name?: string; storageType?: string };
  const name = meta?.name ?? `dataset:${datasetId}`;
  const storageType = meta?.storageType;

  if (storageType === 'TABLE') {
    const { columns, rows: sampleRows } = await sampleTableRows(apiClient, datasetId, EVIDENCE_ROW_CAP);
    // 행이 0건이면 컬럼만 있어도 근거가 없는 것과 같다 — profileColumns가 total=0일 때 nullRatio=0을
    // 돌려줘 "null비율=0.00"이라는 사실과 다른 값이 프롬프트에 실리므로, DOCUMENT와 대칭으로 여기서 스킵한다.
    if (sampleRows.length === 0) {
      return { skipReason: '표에 행이 없습니다(적재 전이거나 빈 테이블)' };
    }
    const profiles = profileColumns(columns, sampleRows);
    // 매핑 가능한 컬럼이 하나도 없으면 근거가 없는 것과 같다 — 아래 DOCUMENT 와 같은 이유로 스킵한다.
    if (profiles.every((p) => p.ontologyDataType === null)) {
      return { skipReason: '온톨로지 속성으로 쓸 수 있는 컬럼이 없습니다' };
    }
    return { datasetId, name, kind: 'table', profiles };
  }

  if (storageType === 'DOCUMENT') {
    // listDocumentChunks 는 청크 **전체**를 반환한다. 그대로 넣으면 컨텍스트가 터지므로
    // 균등 간격으로 표본을 뽑는다(앞부분 편중 방지) + 청크당 본문도 절단한다.
    // 인덱스를 직접 계산하는 이유: i % step 방식은 상한을 살짝 넘는 구간에서 표본이 급감한다
    // (41건/상한40 -> step=2 -> 21건만). 여기서는 항상 min(len, cap) 건을 고르게 뽑는다.
    const all = await apiClient.listDocumentChunks(datasetId);
    const take = Math.min(all.length, EVIDENCE_CHUNKS_PER_DATASET);
    const chunks = Array.from({ length: take }, (_, i) =>
      all[Math.floor((i * all.length) / take)].content.slice(0, EVIDENCE_CHUNK_CHARS),
    ).filter((c) => c.trim() !== '');
    // 청크가 0건이거나 전부 공백이면 근거가 없다. 여기서 스킵하지 않으면 evidence 에 빈 블록이
    // 실려 "근거 있음"으로 통과하고, LLM 이 도메인 지식만으로 지어낸 온톨로지가 데이터 기반인 것처럼
    // basedOn 에 실려 나간다 — 이 도구의 존재 이유가 무너지는 지점이다.
    if (chunks.length === 0) {
      return { skipReason: '문서 청크가 없습니다(적재 전이거나 본문이 비어 있음)' };
    }
    return { datasetId, name, kind: 'document', chunks };
  }

  return { skipReason: `근거로 쓸 수 없는 storageType: ${storageType ?? '알 수 없음'}` };
}

/**
 * 새 온톨로지 도메인명이 아직 살아있는 온톨로지와 겹치지 않는지 확인한다.
 * archived 는 이름을 선점하지 않으므로(V79 부분 유니크 인덱스가 archived 제외) 중복 판정에서 뺀다.
 * 백엔드도 409로 막지만, 여기서 먼저 걸러야 에이전트가 같은 턴에 정정할 수 있다.
 * onClashSuffix 는 겹칠 때 안내 문구 중 도구별로 달라지는 뒷부분("이름을 바꾸세요" vs
 * "삭제 후 재시도" 등)만 넘긴다 — graphrag_propose_ontology 와 graphrag_infer_ontology 가
 * 앞부분(겹침 사실 + bind_ontology 안내)은 공유하되 뒷부분 안내는 서로 다르기 때문이다.
 */
async function assertDomainAvailable(
  apiClient: Pick<FireHubApiClient, 'listOntologies'>,
  domain: string,
  onClashSuffix: string,
): Promise<void> {
  const existing = await apiClient.listOntologies('all');
  const clash = existing.find((o) => o.domain === domain && o.status !== 'archived');
  if (clash) {
    throw new Error(
      `이미 "${domain}" 도메인의 온톨로지가 있습니다(id=${clash.id}, ${clash.status}). `
        + `기존 것을 쓰려면 graphrag_bind_ontology 를, ${onClashSuffix}`,
    );
  }
}

/**
 * 항상 draft 상태로 온톨로지를 생성한다 — status 는 인자로 받지 않는다.
 * 모델이 status 를 고르게 두면 사람 검토를 건너뛰고 바로 운영에 들어갈 수 있어, 이 불변식을
 * graphrag_propose_ontology 와 graphrag_infer_ontology 두 도구가 각자 지키게 두지 않고 여기 모은다.
 */
async function createDraftOntology(
  apiClient: Pick<FireHubApiClient, 'createOntology'>,
  domain: string,
  entities: unknown[],
  relations: unknown[],
): Promise<{ ontologyId: number; domain: string; status: 'draft'; entityCount: number; relationCount: number }> {
  const ontologyId = await apiClient.createOntology({ domain, entities, relations, status: 'draft' });
  return { ontologyId, domain, status: 'draft', entityCount: entities.length, relationCount: relations.length };
}

/**
 * GraphRAG 관련 MCP 도구를 등록한다.
 * 엔티티/관계 추출 LLM 호출은 인증된 claude CLI 헤드리스 실행(createCliCompleter)에 위임한다.
 * (로컬은 macOS 키체인, prod는 CLAUDE_CODE_OAUTH_TOKEN 환경변수로 인증되며 API 키가 필요 없다.)
 */
export function registerGraphragTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  const complete = createCliCompleter();

  return [
    safeTool(
      'graphrag_ingest',
      'DOCUMENT 데이터셋의 청크에서 엔티티/관계를 추출해 지식 그래프(Neo4j)에 적재한다. 관리/구축 목적으로만 사용.',
      { datasetId: z.number().describe('그래프로 적재할 DOCUMENT 데이터셋 ID') },
      async (args: { datasetId: number }) => {
        // Neo4j 제약조건(유니크 키 등)을 먼저 보장한 뒤 적재를 수행한다.
        await bootstrapConstraints();
        // 데이터셋에 바인딩된 온톨로지로 적재한다(미바인딩이면 기본 온톨로지 폴백).
        // ingest 당 1회 fetch → 청크 전반에 재사용.
        const resolved = await resolveDatasetOntology(apiClient, args.datasetId);
        const ontology = resolved.ontology;
        const summary = await ingestDataset(
          {
            listChunks: (id) => apiClient.listDocumentChunks(id),
            extract: (text) => extractGraph(text, { complete, ontology }),
            load: (graph, chunkId, schemaVersion) => loadGraph(graph, chunkId, schemaVersion),
            // 데이터셋 전역 시맨틱 엔티티 해소(semantic-resolver.ts)용 임베딩 — firehub-api 활성 provider 에 위임.
            embed: (texts) => apiClient.embed(texts),
            // 임베딩 임계값 미달 근접쌍(코사인 0.5~0.78)을 LLM으로 재판단해 의미적 동의어를 추가 병합.
            link: (a, b, type) => semanticLink(complete, a, b, type),
            // HITL: 근접쌍 기존 결정 조회 + LLM "같다" 판정을 대기열에 등록.
            lookupDecision: (a, b, type) => apiClient.lookupSynonymDecision(type, a, b),
            recordPending: (a, b, type, similarity, rationale, datasetId, sourceChunkIds) =>
              apiClient.recordPendingSynonym(type, a, b, similarity, rationale, datasetId, sourceChunkIds),
            // 정규화 실패 속성 검수 등록(교정형).
            recordPropertyReview: (datasetId, chunkId, key, type, prop, dataType, raw) =>
              apiClient.recordPropertyReview(datasetId, chunkId, key, type, prop, dataType, raw),
            // 엔티티 추출 검수: 저신뢰 엔티티 기존 결정 조회 + 보류 엔티티(+관계) 큐 등록.
            lookupEntityDecision: (type, name) => apiClient.lookupEntityDecision(type, name),
            recordPendingEntity: (item) => apiClient.recordPendingEntity(item),
            // 관계 추출 검수: 저신뢰 관계 기존 결정 조회 + 보류 관계 큐 등록.
            lookupRelationDecision: (subjectKey, relType, objectKey) => apiClient.lookupRelationDecision(subjectKey, relType, objectKey),
            recordPendingRelation: (item) => apiClient.recordPendingRelation(item),
          },
          args.datasetId,
          ontology,
        );
        // 적재 이력을 best-effort 로 기록한다(실패해도 적재 결과 반환에는 영향 없음).
        const failures = summary.extractionFailures ?? 0;
        try {
          await apiClient.recordGraphIngest(args.datasetId, {
            schemaVersionAtIngest: ontology.schemaVersion,
            chunkCount: summary.chunks, nodeCount: summary.entities, edgeCount: summary.relations,
            extractionFailures: failures, status: failures > 0 ? 'PARTIAL' : 'SUCCESS',
          });
        } catch (err) {
          console.warn('[graphrag] 적재 이력 기록 실패(무시하고 계속):', err);
        }
        // 어떤 스키마로 적재됐는지(바인딩/기본/번들 폴백) 사용자가 알 수 있도록 출처를 함께 노출한다.
        return jsonResult({ ...summary, ontologyId: resolved.ontologyId, ontologySource: resolved.source });
      },
    ),
    safeTool(
      'graphrag_project_table',
      'TABLE 데이터셋의 행을 승인된(active) 매핑에 따라 지식 그래프(Neo4j)에 결정적으로 투영한다. 관리/구축 목적으로만 사용.',
      { datasetId: z.number().describe('투영할 TABLE 데이터셋 ID') },
      async (args: { datasetId: number }) => {
        // Neo4j 제약 보장 → 매핑 조회(active 게이트) → 바인딩 온톨로지 로드 → 투영.
        await bootstrapConstraints();
        const mapping = await apiClient.getDatasetMapping(args.datasetId);
        if (mapping.status !== 'active') {
          throw new Error(`매핑이 active 상태가 아닙니다(현재: ${mapping.status ?? '없음'}). 먼저 매핑을 활성화하세요.`);
        }
        // 표는 id=1이 아닌 온톨로지에 바인딩될 수 있어 by-id로 로드한다(폴백 없음).
        const ontology = deserializeOntology(await apiClient.getOntologyById(mapping.ontologyId));
        const summary = await projectTableDataset(
          {
            fetchRows: (id, page, size) =>
              apiClient.queryDatasetData(id, { page, size, includeTotalCount: true }) as Promise<DataPage>,
            load: (graph, datasetId, schemaVersion) => loadTableGraph(graph, datasetId, schemaVersion),
          },
          args.datasetId, ontology, mapping.spec,
        );
        // 투영 이력 best-effort 기록(chunkCount에는 처리 행 수를 담는다 — 표엔 청크 개념이 없음).
        try {
          await apiClient.recordGraphIngest(args.datasetId, {
            schemaVersionAtIngest: ontology.schemaVersion,
            chunkCount: summary.rowCount, nodeCount: summary.nodeCount, edgeCount: summary.edgeCount,
            extractionFailures: 0, status: 'SUCCESS',
          });
        } catch (err) {
          console.warn('[graphrag] 표 투영 이력 기록 실패(무시하고 계속):', err);
        }
        return jsonResult(summary);
      },
    ),
    safeTool(
      'graphrag_infer_mapping',
      'TABLE 데이터셋의 컬럼을 프로파일링하고 LLM으로 온톨로지 매핑(MappingSpec)을 추론해 draft 매핑으로 저장한다. 관리/구축 목적으로만 사용.',
      {
        datasetId: z.number().describe('매핑을 추론할 TABLE 데이터셋 ID'),
        force: z.boolean().optional().describe('active 매핑이 있어도 덮어써 재추론(draft로 강등됨, 재활성화 필요)'),
      },
      async (args: { datasetId: number; force?: boolean }) => {
        // 1) 존재/활성 가드: 404=매핑없음(진행), draft=덮어씀, active=거부(force 아니면).
        //    getDatasetMapping은 매핑이 없으면 404로 throw하므로 catch해서 상태를 구분한다.
        let existing: { status: string } | null = null;
        try {
          existing = await apiClient.getDatasetMapping(args.datasetId);
        } catch (err) {
          const status = (err as { response?: { status?: number } })?.response?.status;
          if (status !== 404) throw err; // 404만 "매핑 없음"으로 취급, 그 외 에러는 전파
        }
        if (existing?.status === 'active' && !args.force) {
          throw new Error(
            '활성(active) 매핑이 있습니다. 재추론하려면 force:true 를 지정하세요' +
              '(경고: 재활성화 전까지 그래프는 기존 매핑 기준으로 남습니다).',
          );
        }
        // 2) 온톨로지 바인딩 로드 — 미바인딩이면 백엔드 저장이 400이므로 사전 거부.
        const binding = await apiClient.getDatasetOntology(args.datasetId);
        if (binding.ontologyId == null) {
          throw new Error('데이터셋이 온톨로지에 바인딩되지 않았습니다. 먼저 온톨로지를 바인딩하세요.');
        }
        const ontology = deserializeOntology(await apiClient.getOntologyById(binding.ontologyId));
        // 3) 컬럼 메타 + 행 표본을 동일 data 쿼리로 확보(최대 3페이지, ≤600행).
        const SAMPLE_ROW_CAP = 600;
        const { columns, rows: sampleRows } = await sampleTableRows(apiClient, args.datasetId, SAMPLE_ROW_CAP);
        // 4) 프로파일 → 5) 추론(자체 conformance 필터로 부적합분 드롭).
        const profiles = profileColumns(columns, sampleRows);
        const result = await inferMapping({ complete }, ontology, profiles);
        // 6) 빈 결과 가드: 무용한 빈 draft를 저장하지 않고 표면화(조용히 저장 금지).
        if (result.spec.entities.length === 0) {
          throw new Error('추론 결과가 비었습니다(LLM 실패 또는 매핑 가능한 컬럼 없음). draft를 저장하지 않았습니다.');
        }
        // 7) draft 저장(PUT → status=draft).
        await apiClient.saveDatasetMapping(args.datasetId, result.spec);
        // 8) 요약 반환. confidence/dropped는 휘발성(draft엔 저장 안 됨).
        return jsonResult({
          datasetId: args.datasetId,
          status: 'draft',
          entityCount: result.spec.entities.length,
          relationCount: result.spec.relations.length,
          dropped: result.dropped,
          confidences: result.confidences,
          forcedOverActive: existing?.status === 'active' && !!args.force,
        });
      },
    ),
    safeTool(
      'graphrag_list_ontologies',
      '등록된 온톨로지 목록(id·도메인·스키마버전)을 조회한다. 데이터셋에 온톨로지를 바인딩하기 전 대상 id를 고를 때 사용.',
      {},
      async () => jsonResult({ ontologies: await apiClient.listOntologies() }),
    ),
    safeTool(
      'graphrag_propose_ontology',
      '새 도메인 온톨로지를 **초안(draft)**으로 제안한다. 데이터셋에 맞는 기존 온톨로지가 없을 때만 사용. '
        + '초안은 바인딩·적재에 쓰이지 않으며, 사람이 "지식 모델" 화면에서 검토 후 활성화해야 한다. '
        + '기존 온톨로지 수정은 이 툴로 할 수 없다. 관리/구축 목적으로만 사용.',
      {
        domain: z.string().describe('온톨로지 도메인명(고유해야 함). 예: "건축물 안전점검"'),
        entities: z
          .array(
            z.object({
              type: z.string().describe('엔티티 타입명(영문 PascalCase 권장). 예: Inspection'),
              description: z.string().describe('이 타입이 무엇을 가리키는지'),
              naming: z.string().describe('이름 표기 규칙. 예: "본문에 등장한 표기를 그대로 사용한다."'),
              resolution: z
                .enum(['exact', 'embedding'])
                .describe('동일성 판정 방식. 표기가 흔들리면 embedding, 정확 일치면 exact'),
              properties: z
                .array(
                  z.object({
                    name: z.string().describe('속성명(key/type/name/sourceChunkIds/schemaVersion은 예약어라 불가)'),
                    description: z.string(),
                    dataType: z.enum(['text', 'number', 'date']),
                    unit: z.string().nullable().describe('단위(없으면 null). 예: "원", "㎡"'),
                  }),
                )
                .describe('속성 목록(없으면 빈 배열)'),
            }),
          )
          .describe('엔티티 타입 목록. 최소 1개 이상 제안할 것'),
        relations: z
          .array(
            z.object({
              subject: z.string().describe('주어 엔티티 타입명(entities에 있어야 함)'),
              relation: z.string().describe('관계명(영문 SCREAMING_SNAKE_CASE 권장). 예: OCCURRED_AT'),
              object: z.string().describe('목적어 엔티티 타입명(entities에 있어야 함)'),
              description: z.string(),
            }),
          )
          .describe('허용 관계(트리플) 목록. 없으면 빈 배열'),
      },
      async (args: {
        domain: string;
        entities: unknown[];
        relations: unknown[];
      }) => {
        await assertDomainAvailable(apiClient, args.domain, '이름을 바꾸려면 다른 도메인명을 사용하세요.');

        const created = await createDraftOntology(apiClient, args.domain, args.entities, args.relations);
        return jsonResult({
          ...created,
          nextStep:
            '초안으로 저장했습니다. 사용자가 "지식 모델" 화면(/knowledge-graph/model)에서 내용을 검토하고 '
            + '활성화해야 데이터셋에 연결할 수 있습니다. 에이전트는 활성화할 수 없습니다.',
        });
      },
    ),
    safeTool(
      'graphrag_infer_ontology',
      '데이터셋(문서·표)의 실제 내용을 근거로 새 도메인 온톨로지를 **초안(draft)**으로 유도한다. '
        + '맞는 기존 온톨로지가 없고 근거로 삼을 데이터셋이 있을 때 사용(근거가 없으면 graphrag_propose_ontology). '
        + '초안은 바인딩·적재에 쓰이지 않으며 사람이 "지식 모델" 화면에서 검토 후 활성화해야 한다. '
        + '기존 온톨로지 수정은 이 툴로 할 수 없다. 관리/구축 목적으로만 사용.',
      {
        domain: z.string().describe('온톨로지 도메인명(고유해야 함). 예: "건축물 안전점검"'),
        datasetIds: z
          .array(z.number())
          .min(1)
          .describe('근거로 삼을 데이터셋 ID 목록. 여러 개(문서+표 혼재)를 넘길수록 통합 개념이 잘 잡힌다'),
        hint: z.string().optional().describe('설계 방향 힌트(선택). 예: "점검 이력과 지적사항 중심"'),
      },
      async (args: { domain: string; datasetIds: number[]; hint?: string }) => {
        // 1) 도메인 충돌 사전 체크.
        await assertDomainAvailable(
          apiClient, args.domain,
          '다시 만들려면 "지식 모델" 화면에서 기존 것을 삭제한 뒤 재시도하거나 다른 도메인명을 사용하세요'
            + '(덮어쓰기는 지원하지 않습니다).',
        );

        // 2) 데이터셋별 근거 수집(FILE 등은 스킵하고 이유를 남긴다).
        // 중복 id는 정규화한다 — [3, 3]처럼 같은 데이터셋이 두 번 들어오면 프롬프트/basedOn에 같은
        // 블록이 중복되고, 무엇보다 evidence.length가 부풀어 아래 4)의 "단일 TABLE 경고"가
        // 조용히 안 걸린다(바로 그 실패 모드를 감지해야 하는 가드가 무경고로 지나감).
        const uniqueDatasetIds = [...new Set(args.datasetIds)];
        const evidence: DatasetEvidence[] = [];
        const skipped: { datasetId: number; reason: string }[] = [];
        // 데이터셋별 수집은 서로 독립이라 동시에 돈다 — 순차로 돌면 데이터셋 수만큼 HTTP 왕복이
        // 직렬로 쌓여, 여러 개를 넘기라고 안내하는 이 도구에서 LLM 호출 전 대기가 그대로 늘어난다.
        // allSettled 라 한 건이 실패해도 나머지는 진행하고, map 순서가 보존돼 basedOn/skipped 순서는 그대로다.
        const settled = await Promise.allSettled(uniqueDatasetIds.map((id) => collectDatasetEvidence(apiClient, id)));
        settled.forEach((result, i) => {
          const id = uniqueDatasetIds[i];
          if (result.status === 'rejected') {
            // 모델이 지어낸 id(404) 하나 때문에 여러 데이터셋짜리 요청 전체가 죽으면 안 된다 —
            // FILE 등 스킵 경로와 비대칭이었다. 조회 실패도 스킵 사유로 남기고 나머지는 계속 진행한다.
            const err = result.reason as Error;
            skipped.push({ datasetId: id, reason: `근거 수집 실패: ${err?.message ?? String(result.reason)}` });
            return;
          }
          if ('skipReason' in result.value) skipped.push({ datasetId: id, reason: result.value.skipReason });
          else evidence.push(result.value);
        });
        if (evidence.length === 0) {
          throw new Error('근거로 쓸 수 있는 데이터셋이 없습니다(문서 또는 표 데이터셋을 지정하세요).');
        }

        // 3) 추론 + 검증 필터.
        const result = await inferOntology({ complete }, args.domain, args.hint, evidence);
        if (result.entities.length === 0) {
          throw new Error(
            '추론 결과가 비었습니다(LLM 실패 또는 제안이 전부 검증에서 탈락). 온톨로지를 저장하지 않았습니다.',
          );
        }

        // 4) 단일 TABLE 가드 — 차단하지 않고 경고. 표 하나만 보면 컬럼을 그대로 옮긴
        //    온톨로지가 나와 매핑 레이어가 무의미해진다.
        const warnings: string[] = [];
        if (evidence.length === 1 && evidence[0].kind === 'table') {
          warnings.push(
            '표 데이터셋 하나만 근거로 삼았습니다. 컬럼 목록을 그대로 옮긴 온톨로지가 되기 쉬우니, '
              + '관련 데이터셋을 더 넘기거나 문서 데이터셋을 포함해 다시 시도하는 것을 권합니다.',
          );
        }

        // 5) draft 저장.
        const created = await createDraftOntology(apiClient, args.domain, result.entities, result.relations);

        return jsonResult({
          ...created,
          basedOn: evidence.map((e) => ({ datasetId: e.datasetId, name: e.name, kind: e.kind })),
          skipped,
          dropped: result.dropped,
          warnings,
          nextStep:
            '초안으로 저장했습니다. 사용자가 "지식 모델" 화면(/knowledge-graph/model)에서 내용을 검토하고 '
            + '활성화해야 데이터셋에 연결할 수 있습니다. 에이전트는 활성화·수정할 수 없습니다.',
        });
      },
    ),
    safeTool(
      'graphrag_describe_ontology',
      '온톨로지의 엔티티 타입·필터 가능 속성(이름/타입/단위)·허용 관계 트리플을 조회한다. '
        + 'graphrag_structured_query 의 entityType·property 인자를 정하기 전에 반드시 이 도구로 실제 스키마를 확인하라.',
      { ontologyId: z.number().optional().describe('조회할 온톨로지 id(생략 시 기본 온톨로지)') },
      async (args: { ontologyId?: number }) => {
        // 기본 온톨로지는 loadOntology(폴백 있음), id 지정 시 by-id 로드(폴백 없음).
        // ⚠️ 폴백이 걸리면 DB 가 아니라 번들 CORE_ONTOLOGY 를 보게 되는데, 그걸 숨기면
        // "하드코딩 속성 광고 금지"라는 이 도구의 존재 이유가 무너진다 → source 로 표면화한다.
        const { ontology, source } = args.ontologyId == null
          ? await loadOntologyWithSource(apiClient)
          : { ontology: deserializeOntology(await apiClient.getOntologyById(args.ontologyId)), source: 'db' as const };
        return jsonResult({
          domain: ontology.domain,
          schemaVersion: ontology.schemaVersion,
          // 'bundled-fallback' 이면 실제 등록 스키마와 다를 수 있으므로 단정하지 말고 확인 불가로 답할 것.
          source,
          entityTypes: ontology.entities.map((e) => ({
            type: e.type,
            description: e.description,
            // 필터 가능 속성 = structured_query 화이트리스트와 동일 출처(ontology.entities[].properties)
            filterableProperties: (e.properties ?? []).map((p) => ({
              name: p.name, dataType: p.dataType, unit: p.unit, description: p.description,
            })),
          })),
          relationTypes: ontology.relations.map((r) => ({
            subject: r.subject, relation: r.relation, object: r.object, description: r.description,
          })),
        });
      },
    ),
    safeTool(
      'graphrag_bind_ontology',
      '데이터셋을 온톨로지에 바인딩한다(멱등). 표 데이터셋 매핑 추론·투영의 사전 조건. 관리/구축 목적으로만 사용.',
      {
        datasetId: z.number().describe('바인딩할 데이터셋 ID'),
        ontologyId: z.number().describe('바인딩 대상 온톨로지 ID(graphrag_list_ontologies 로 확인)'),
      },
      async (args: { datasetId: number; ontologyId: number }) => {
        // 존재하지 않는 온톨로지에 바인딩해 뒤늦게 실패하지 않도록 사전 검증한다.
        // (백엔드도 검증하지만, 여기서 막으면 에이전트가 같은 턴에 목록으로 정정할 수 있다.)
        const ontologies = await apiClient.listOntologies();
        const target = ontologies.find((o) => o.id === args.ontologyId);
        if (!target) {
          throw new Error(
            `온톨로지 id=${args.ontologyId} 가 없습니다. 사용 가능: `
              + `${ontologies.map((o) => `${o.id}(${o.domain})`).join(', ') || '없음'}`,
          );
        }
        await apiClient.bindDatasetOntology(args.datasetId, args.ontologyId);
        return jsonResult({
          datasetId: args.datasetId, ontologyId: args.ontologyId, domain: target.domain, bound: true,
        });
      },
    ),
    safeTool(
      'graphrag_activate_mapping',
      'draft 상태의 표→그래프 매핑을 활성화(active)한다. 활성화 후에야 graphrag_project_table 로 투영할 수 있다. '
        + '활성 매핑은 그래프 적재 기준이므로 사용자 확인 후 실행할 것. 관리/구축 목적으로만 사용.',
      { datasetId: z.number().describe('매핑을 활성화할 TABLE 데이터셋 ID') },
      async (args: { datasetId: number }) => {
        // 이미 active 면 백엔드 상태 전이가 무의미하므로 조기 반환한다(중복 활성화 노이즈 방지).
        const existing = await apiClient.getDatasetMapping(args.datasetId);
        if (existing.status === 'active') {
          return jsonResult({ datasetId: args.datasetId, status: 'active', alreadyActive: true });
        }
        // conformance 재검증은 백엔드가 수행 — 위반 시 400 이 safeTool 로 표면화된다.
        const activated = await apiClient.activateDatasetMapping(args.datasetId);
        return jsonResult({ ...activated, alreadyActive: false });
      },
    ),
    safeTool(
      'graphrag_ingest_history',
      '지식 그래프 적재 현황을 조회한다. datasetId 를 주면 해당 데이터셋의 적재 이력(시점·노드/엣지 수·실패 수), '
        + '생략하면 온톨로지 스키마가 올라가 재적재가 필요한(stale) 데이터셋 목록을 반환한다.',
      { datasetId: z.number().optional().describe('적재 이력을 볼 데이터셋 ID(생략 시 stale 목록)') },
      async (args: { datasetId?: number }) => {
        if (args.datasetId == null) {
          const stale = await apiClient.listStaleGraphIngests();
          return jsonResult({ mode: 'stale', staleDatasets: stale, count: stale.length });
        }
        const history = await apiClient.listGraphIngests(args.datasetId);
        return jsonResult({ mode: 'history', datasetId: args.datasetId, history, count: history.length });
      },
    ),
    safeTool(
      'graphrag_list_review_items',
      'AI 검수 인박스 항목을 조회한다(동의어 병합·속성 정규화·저신뢰 엔티티/관계). '
        + '각 항목은 타입별로 정규화된 summary 를 포함하므로 그대로 사용자에게 요약해 제시하라. '
        + '승인/거부는 항목마다 사용자 확인이 필요하다 — 목록을 보고 임의로 일괄 처리하지 말 것.',
      {
        status: z.enum(['pending', 'approved', 'rejected']).optional().describe('상태 필터(기본 pending)'),
        itemType: z.enum(['synonym', 'property', 'entity', 'relation']).optional().describe('항목 타입 필터'),
        limit: z.number().min(1).max(100).optional().describe('반환 최대 건수(기본 30)'),
      },
      async (args: { status?: string; itemType?: string; limit?: number }) => {
        const items = await apiClient.listReviewItems(args.status, args.itemType);
        const limit = args.limit ?? 30;
        return jsonResult({
          total: items.length,
          returned: Math.min(items.length, limit),
          // 절단 사실을 숨기면 모델이 "전부 처리했다"고 답하게 된다 — 명시적으로 알린다.
          truncated: items.length > limit,
          items: items.slice(0, limit).map(summarizeReviewItem),
        });
      },
    ),
    safeTool(
      'graphrag_review_evidence',
      '검수 항목이 유래한 원문 청크 스니펫을 조회한다. 승인/거부를 사용자에게 제안하기 전 근거로 인용하라.',
      { id: z.number().describe('검수 항목 ID') },
      async (args: { id: number }) => {
        const chunks = await apiClient.getReviewItemEvidence(args.id);
        return jsonResult({ id: args.id, evidence: chunks, empty: chunks.length === 0 });
      },
    ),
    safeTool(
      'graphrag_approve_review_item',
      '검수 항목을 승인해 지식 그래프에 실제로 반영한다(엔티티 병합·속성 설정·엔티티/관계 적재). '
        + '⚠️ 그래프 변경은 되돌릴 수 없으며 거부(reject)로 취소되지 않는다. '
        + '항목마다 사용자의 별도 확인을 받은 뒤 1건씩 호출하라 — 여러 건 일괄 승인 금지. '
        + 'itemType=property 항목은 정정값(correctedValue)이 반드시 필요하다.',
      {
        id: z.number().describe('승인할 검수 항목 ID'),
        correctedValue: z.string().optional().describe('속성 정규화 항목의 정정값(itemType=property 에서 필수)'),
      },
      async (args: { id: number; correctedValue?: string }) => {
        // 항목 타입을 **먼저 확정**한 뒤 인자 정합성을 본다.
        // 조건부로만 검사하면, correctedValue 를 들고 synonym 항목을 승인하는 경우
        // 백엔드가 그 값을 조용히 무시한 채 엔티티를 병합해 버린다(비가역 오적재).
        const pending = await apiClient.listReviewItems('pending');
        const target = pending.find((i) => i.id === args.id);
        if (!target) {
          throw new Error(
            `대기 중(pending) 검수 항목 ${args.id} 를 찾을 수 없습니다(이미 처리되었거나 존재하지 않음). `
              + 'graphrag_list_review_items 로 현재 목록을 다시 확인하세요.',
          );
        }
        const hasCorrected = args.correctedValue != null && args.correctedValue.trim() !== '';
        // 백엔드도 property+정정값 누락을 400 으로 막지만, 여기서 먼저 걸러야
        // 모델이 "무엇이 부족한지"를 같은 턴에 알고 사용자에게 정정값을 물을 수 있다.
        if (target.itemType === 'property' && !hasCorrected) {
          const summary = summarizeReviewItem(target);
          throw new Error(
            `속성 정규화 승인에는 정정값(correctedValue)이 필요합니다. 원문: "${summary.rawText ?? ''}" `
              + `(${summary.summary}). 사용자에게 정정값을 확인한 뒤 다시 호출하세요.`,
          );
        }
        if (target.itemType !== 'property' && hasCorrected) {
          throw new Error(
            `itemType=${target.itemType} 항목은 정정값(correctedValue)을 받지 않습니다`
              + '(백엔드가 값을 무시한 채 그대로 반영하므로 오적재 위험). 정정값 없이 다시 호출하세요.',
          );
        }
        const decided = await apiClient.approveReviewItem(args.id, args.correctedValue);
        return jsonResult(summarizeReviewItem(decided));
      },
    ),
    safeTool(
      'graphrag_reject_review_item',
      '검수 항목을 거부한다(그래프는 변경되지 않고 상태만 rejected). 항목마다 사용자 확인 후 1건씩 호출하라.',
      { id: z.number().describe('거부할 검수 항목 ID') },
      async (args: { id: number }) => jsonResult(summarizeReviewItem(await apiClient.rejectReviewItem(args.id))),
    ),
    safeTool(
      'graphrag_query',
      '엔티티 간 관계/연결/공통점/경로를 묻는 질문에 지식 그래프로 답한다. '
        + '반환된 subgraph 노드·관계와 sourceChunks의 fileName을 반드시 인용해 답하라.',
      {
        query: z.string().describe('관계·연결을 묻는 자연어 질문'),
        topK: z.number().min(1).max(20).optional().describe('시드 문서 검색 수(기본 8)'),
      },
      async (args: { query: string; topK?: number }) => {
        // 벡터검색(searchDocuments)을 retriever의 deps 규약으로 어댑팅해 시드 청크를 확보하고,
        // 그 청크에서 유래한 엔티티를 1~2홉 확장한 서브그래프+출처를 조립한다.
        const result = await retrieve(
          {
            searchDocuments: (q, ids, k, mode) => apiClient.searchDocuments(
              q, ids, k, mode as 'SEMANTIC' | 'KEYWORD' | 'HYBRID' | undefined,
            ),
          },
          args.query, { topK: args.topK },
        );
        // Neo4j 연결 불가 등은 retrieve에서 throw → safeTool이 isError로 감싸 폴백 메시지 제공.
        return jsonResult({
          subgraph: { nodes: result.nodes, relations: result.relations },
          sourceChunks: result.sourceChunks,
        });
      },
    ),
    safeTool(
      'graphrag_structured_query',
      '지식 그래프의 엔티티를 온톨로지 속성값 술어로 필터·열거한다("~이상/이하/포함"). '
        + '반환된 entities(이름+속성값)를 인용해 답하라. 관계·경로 질문은 graphrag_query 를 쓸 것. '
        + '필터 가능한 entityType·property 는 온톨로지마다 다르므로 하드코딩하지 말고 '
        + 'graphrag_describe_ontology 로 확인한 값만 사용하라(정형 데이터셋 컬럼 조건은 SQL 경로가 정답).',
      {
        ontologyId: z.number().optional()
          .describe('속성 화이트리스트로 쓸 온톨로지 id(생략 시 기본 온톨로지). 대상이 기본이 아닌 온톨로지에 바인딩된 경우 반드시 지정'),
        entityType: z.string().describe('필터할 엔티티 타입(graphrag_describe_ontology 의 entityTypes[].type)'),
        filters: z.array(z.object({
          property: z.string().describe('온톨로지에 정의된 속성명(graphrag_describe_ontology 의 filterableProperties[].name)'),
          operator: z.enum(['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'contains']).describe('비교 연산자'),
          value: z.union([z.number(), z.string()]).describe('비교값(number 속성은 원 단위 정수)'),
        })).describe('AND 로 결합되는 술어 목록'),
      },
      async (args: { ontologyId?: number; entityType: string; filters: Array<{ property: string; operator: Operator; value: number | string }> }) => {
        // 질의 시점 온톨로지를 fetch 해 화이트리스트로 사용한다.
        // ontologyId 미지정 시 기본(id=1) 온톨로지 — 다른 온톨로지에 바인딩된 데이터셋을 질의하면
        // 정당한 속성이 "필터 불가"로 거부되므로, 그 경우 호출부가 ontologyId 를 지정해야 한다.
        const ontology = args.ontologyId == null
          ? await loadOntology(apiClient)
          : deserializeOntology(await apiClient.getOntologyById(args.ontologyId));
        // 도구 설명에 속성을 하드코딩하면 온톨로지가 바뀌어도 모델이 옛 속성만 알게 된다.
        // 대신 검증 실패 시 "지금 이 온톨로지에서 실제로 가능한 값"을 오류에 실어 1턴 내 자체 정정을 유도한다.
        const typeDef = ontology.entities.find((e) => e.type === args.entityType);
        if (!typeDef) {
          throw new Error(
            `알 수 없는 엔티티 타입: ${args.entityType}. 사용 가능: `
              + `${ontology.entities.map((e) => e.type).join(', ')}`,
          );
        }
        const allowed = (typeDef.properties ?? []).map((p) => `${p.name}(${p.dataType}${p.unit ? `, ${p.unit}` : ''})`);
        const unknown = args.filters.filter(
          (f) => !(typeDef.properties ?? []).some((p) => p.name === f.property),
        );
        if (unknown.length > 0) {
          throw new Error(
            `필터 불가 속성: ${unknown.map((f) => f.property).join(', ')}. `
              + `${args.entityType} 에서 필터 가능: ${allowed.join(', ') || '없음'}`
              + (args.ontologyId == null
                ? ' (기본 온톨로지 기준. 대상이 다른 온톨로지에 바인딩되어 있다면 ontologyId 를 지정해 재시도하라)'
                : ` (온톨로지 id=${args.ontologyId} 기준)`),
          );
        }
        const result = await structuredQuery(ontology, args.entityType, args.filters as Filter[]);
        return jsonResult(result);
      },
    ),
  ];
}
