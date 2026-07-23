// GraphRAG 적재/질의 MCP 도구. register 규약: (apiClient, safeTool, jsonResult) => Tool[]
import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';
import { ingestDataset } from '../../graphrag/ingest.js';
import { extractGraph } from '../../graphrag/extractor.js';
import { createCliCompleter } from '../../graphrag/llm-cli.js';
import { loadGraph } from '../../graphrag/loader.js';
import { bootstrapConstraints } from '../../graphrag/neo4j-client.js';
import { retrieve } from '../../graphrag/retriever.js';
// 추출 시점 온톨로지는 api(DB 소유)에서 fetch하고 실패 시 번들 CORE_ONTOLOGY 로 폴백한다.
import { loadOntology } from '../../graphrag/ontology-source.js';
import { structuredQuery, Filter, Operator } from '../../graphrag/structured-query.js';
import { link as semanticLink } from '../../graphrag/semantic-link.js';

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
        const ontology = await loadOntology(apiClient); // ingest 1회 fetch(실패 시 폴백)
        const summary = await ingestDataset(
          {
            listChunks: (id) => apiClient.listDocumentChunks(id),
            extract: (text) => extractGraph(text, { complete, ontology }),
            load: (graph, chunkId, schemaVersion) => loadGraph(graph, chunkId, schemaVersion),
            // 데이터셋 전역 시맨틱 엔티티 해소(semantic-resolver.ts)용 임베딩 — firehub-api 활성 provider 에 위임.
            embed: (texts) => apiClient.embed(texts),
            // 임베딩 임계값 미달 근접쌍(코사인 0.5~0.78)을 LLM으로 재판단해 의미적 동의어를 추가 병합.
            link: (a, b, type) => semanticLink(complete, a, b, type),
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
        return jsonResult(summary);
      },
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
      '엔티티를 속성값 술어로 필터·열거한다(예: "피해액 1억 넘는 사건 전부", "~이상/이하"). '
        + '반환된 entities(이름+속성값)를 인용해 답하라. 관계·경로 질문은 graphrag_query 를 쓸 것. '
        + '필터 가능 속성: Incident.피해액(number, 원). (온톨로지에 정의된 속성만 필터 가능)',
      {
        entityType: z.string().describe('필터할 엔티티 타입(예: Incident)'),
        filters: z.array(z.object({
          property: z.string().describe('온톨로지에 정의된 속성명(예: 피해액)'),
          operator: z.enum(['gt', 'gte', 'lt', 'lte', 'eq', 'neq', 'contains']).describe('비교 연산자'),
          value: z.union([z.number(), z.string()]).describe('비교값(number 속성은 원 단위 정수)'),
        })).describe('AND 로 결합되는 술어 목록'),
      },
      async (args: { entityType: string; filters: Array<{ property: string; operator: Operator; value: number | string }> }) => {
        // 질의 시점 온톨로지를 fetch(실패 시 폴백)해 화이트리스트로 사용한다.
        const ontology = await loadOntology(apiClient);
        const result = await structuredQuery(ontology, args.entityType, args.filters as Filter[]);
        return jsonResult(result);
      },
    ),
  ];
}
