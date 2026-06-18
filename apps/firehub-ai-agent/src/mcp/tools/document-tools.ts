import { z } from 'zod/v4';
import { estimateTokens } from './analytics-tools.js';
import type { FireHubApiClient, DocumentSearchHit } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

// search_documents 응답 크기 가드.
// analytics(execute_analytics_query)와 동일한 SDK single tool_result 토큰 한도(~25K) 문제에 대응한다.
// 다만 결과 형태가 `rows` 객체가 아닌 DocumentSearchHit[] 배열이라 clampAnalyticsResult를 그대로
// 재사용할 수 없으므로(구조 불일치), 토큰 추정(estimateTokens)을 공유하는 전용 인라인 가드를 둔다.
// 문서 청크(content)는 본문이 길 수 있어 토큰 한도를 쉽게 초과할 수 있다.
const DOCUMENT_RESPONSE_MAX_TOKENS = 18_000;
// 청크 1건당 content 최대 길이 — 1차 절단 단계에서 본문이 과도하게 긴 청크를 잘라낸다.
const DOCUMENT_CONTENT_MAX_CHARS = 2_000;

/**
 * 문서 검색 결과(DocumentSearchHit[])가 SDK 토큰 한도를 넘지 않도록 절단한다.
 * - 한도 이내면 원본을 그대로 반환(소량 결과는 무변경).
 * - 1차: 각 청크 content가 너무 길면 잘라내고 truncate 마커를 덧붙인다.
 * - 2차: 그래도 초과하면 청크 수를 이진탐색식으로 줄인다(최대 16회).
 *
 * clampAnalyticsResult와 달리 truncated 메타를 동봉할 객체 래퍼가 없어,
 * content 마커 문자열로 절단 사실을 LLM에 알린다(출처 인용 시 본문 누락 인지 가능).
 */
export function clampDocumentHits(
  hits: DocumentSearchHit[],
  maxTokens: number = DOCUMENT_RESPONSE_MAX_TOKENS,
  contentMaxChars: number = DOCUMENT_CONTENT_MAX_CHARS,
): DocumentSearchHit[] {
  if (!Array.isArray(hits) || hits.length === 0) return hits;
  if (estimateTokens(JSON.stringify(hits)) <= maxTokens) return hits;

  // 1차: content가 임계치를 넘는 청크의 본문을 잘라 마커를 덧붙인다.
  let clamped: DocumentSearchHit[] = hits.map((h) =>
    typeof h.content === 'string' && h.content.length > contentMaxChars
      ? { ...h, content: `${h.content.slice(0, contentMaxChars)} …(내용 일부 생략)` }
      : h,
  );
  if (estimateTokens(JSON.stringify(clamped)) <= maxTokens) return clamped;

  // 2차: 청크 수를 절반씩 줄여 한도 이내가 될 때까지 반복(이진탐색식, 최대 16회).
  let safety = 16;
  while (clamped.length > 1 && safety-- > 0) {
    if (estimateTokens(JSON.stringify(clamped)) <= maxTokens) break;
    clamped = clamped.slice(0, Math.max(1, Math.floor(clamped.length / 2)));
  }
  return clamped;
}

/**
 * 문서 RAG 검색 MCP 도구를 등록한다.
 * analytics 도구와 동일한 (apiClient, safeTool, jsonResult) 시그니처를 따른다.
 */
export function registerDocumentTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    // 비정형 문서(DOCUMENT 데이터셋) 의미 검색
    safeTool(
      'search_documents',
      '비정형 문서(DOCUMENT 데이터셋, 보고서·매뉴얼 등)에서 유사한 내용을 검색한다. 대상 데이터셋은 보통 find_datasets로 먼저 찾은 datasetIds를 넘긴다. datasetIds 생략 시 접근 가능한 모든 문서 검색. 기본은 의미+키워드 하이브리드 검색이며, mode 로 SEMANTIC/KEYWORD 를 지정할 수 있다. 결과 청크를 출처(fileName)와 함께 인용해 답하라.',
      {
        query: z.string().describe('검색 질의 문자열'),
        datasetIds: z
          .array(z.number())
          .optional()
          .describe('검색 대상 DOCUMENT 데이터셋 ID 배열 (생략 시 접근 가능한 전체 문서)'),
        topK: z
          .number()
          .min(1)
          .max(20)
          .optional()
          .describe('반환할 최대 청크 수 (1~20, 생략 시 백엔드 기본값)'),
        mode: z
          .enum(['SEMANTIC', 'KEYWORD', 'HYBRID'])
          .optional()
          .describe('검색 모드 (생략 시 하이브리드: 의미+키워드). SEMANTIC=의미만, KEYWORD=키워드만'),
      },
      async (args: {
        query: string;
        datasetIds?: number[];
        topK?: number;
        mode?: 'SEMANTIC' | 'KEYWORD' | 'HYBRID';
      }) => {
        const hits = await apiClient.searchDocuments(args.query, args.datasetIds, args.topK, args.mode);
        // 청크 content가 길 수 있어 토큰 한도 가드를 적용한다.
        return jsonResult(clampDocumentHits(hits));
      },
    ),
  ];
}
