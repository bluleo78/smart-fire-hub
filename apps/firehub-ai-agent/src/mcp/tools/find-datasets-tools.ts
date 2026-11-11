import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';
import { type SearchMode, withSearchGuard } from '../api-client/platform-db-api.js';

/** 데이터셋 discovery 단일 진입점: 키워드+의미 하이브리드 검색. */
export function registerFindDatasetTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    // 사용자 질문 → 대상 데이터셋 매칭(정형 TABLE/비정형 DOCUMENT/파일 FILE 통합)의 1차 진입점
    safeTool(
      'find_datasets',
      '사용자 질문에서 대상 데이터셋을 찾는 1차 진입점. 키워드+의미 하이브리드로 매칭하며 정형(TABLE)·비정형 문서(DOCUMENT)·파일 오브젝트(FILE)를 구분 없이 검색한다. 반환된 각 후보의 storageType으로 후속 도구를 선택하라(TABLE→get_data_schema/execute_analytics_query, DOCUMENT→search_documents, FILE→list_dataset_files/summarize_dataset_files/get_dataset_file_url). FILE 데이터셋에는 SQL 도구를 쓰지 마라(물리 테이블이 없어 실패한다). score가 낮거나 결과가 비면 임의 데이터셋을 고르지 말고 "데이터셋을 찾지 못했다"고 답하거나 사용자에게 되물어라.',
      {
        query: z
          .string()
          .describe('사용자 질문에서 추출한 검색 질의(자연어/키워드). 의미+키워드 하이브리드로 매칭'),
        mode: z
          .enum(['SEMANTIC', 'KEYWORD', 'HYBRID'])
          .optional()
          .describe('검색 모드. 생략 시 HYBRID. 정확한 코드·약어는 KEYWORD가 유리'),
        topK: z
          .number()
          .int()
          .min(1)
          .max(20)
          .optional()
          .describe('반환할 최대 후보 수. 생략 시 백엔드 기본값(10)'),
        storageType: z
          .enum(['TABLE', 'DOCUMENT', 'FILE'])
          .optional()
          .describe('유형 한정 시 지정(TABLE=정형·DOCUMENT=문서·FILE=파일 오브젝트). 생략 시 전체 검색'),
      },
      async (args: {
        query: string;
        mode?: SearchMode;
        topK?: number;
        storageType?: 'TABLE' | 'DOCUMENT' | 'FILE';
      }) => {
        // WD-495: 벡터·트라이그램 검색이 둘 다 꺼진 설치본이면 API 를 부르지 않고 안내문을 정상 결과로 돌려준다
        // (isError 아님 — 모델이 재시도하지 않게). 하나만 꺼졌으면 search_documents 와 같은 규칙으로 켜진 기능의
        // 단일 모드로 바꿔 부르고(HYBRID 는 두 기능 모두 필요), 바꾼 사실을 결과 끝에 덧붙인다.
        return withSearchGuard(() => apiClient.getPlatformDbCapabilities(), args.mode, async (mode) => {
          const hits = await apiClient.searchDatasets(args.query, mode, args.topK, args.storageType);
          // 응답은 후보 메타만이라 크기 가드(clamp) 불필요.
          return jsonResult(hits);
        });
      },
    ),
  ];
}
