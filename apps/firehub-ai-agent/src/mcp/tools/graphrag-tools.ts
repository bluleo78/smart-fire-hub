// GraphRAG 적재/질의 MCP 도구. register 규약: (apiClient, safeTool, jsonResult) => Tool[]
import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';
import { ingestDataset } from '../../graphrag/ingest.js';
import { extractGraph } from '../../graphrag/extractor.js';
import { createCliCompleter } from '../../graphrag/llm-cli.js';
import { loadGraph } from '../../graphrag/loader.js';
import { embedTexts } from '../../graphrag/embedding.js';
import { bootstrapConstraints } from '../../graphrag/neo4j-client.js';
import { retrieve } from '../../graphrag/retriever.js';
// 추출 시점 온톨로지는 api(DB 소유)에서 fetch하고 실패 시 번들 CORE_ONTOLOGY 로 폴백한다.
import { loadOntology } from '../../graphrag/ontology-source.js';

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
            load: (graph, chunkId) => loadGraph(graph, chunkId),
            // 데이터셋 전역 시맨틱 엔티티 해소(semantic-resolver.ts)에 쓰이는 bge-m3 임베딩 클라이언트.
            embed: (texts) => embedTexts(texts),
          },
          args.datasetId,
          ontology,
        );
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
  ];
}
