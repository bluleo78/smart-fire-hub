import { z } from 'zod/v4';
import type { FireHubApiClient, ObjectItem } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

// FILE(오브젝트) 데이터셋은 개별 파일을 DB 행으로 관리하지 않는다. 따라서 "이 데이터셋에 뭐가
// 들었나"(개수·용량·구성)를 알려면 S3 ListObjects 를 페이지 단위로 순회해 실시간 집계해야 한다.
// 무한 순회/토큰 폭증을 막기 위해 스캔 상한을 둔다. 상한을 넘겨 전수 집계가 아닐 때는 응답에
// capped=true 를 실어, LLM 이 부분 합계를 "정확한 총합"으로 오인해 환각하지 않도록 한다.
const SUMMARY_PAGE_SIZE = 200; // 백엔드 페이지 크기 클램프 상한
const SUMMARY_MAX_PAGES = 10; // 최대 10페이지
const SUMMARY_SCAN_CAP = SUMMARY_PAGE_SIZE * SUMMARY_MAX_PAGES; // 최대 2000개까지 스캔

/** 파일명(상대경로)에서 확장자를 소문자로 추출한다. 확장자가 없으면 '(확장자 없음)'. */
function extensionOf(name: string): string {
  const seg = name.split('/').pop() ?? name; // 마지막 경로 세그먼트(=파일명)
  const dot = seg.lastIndexOf('.');
  // 선행 점(.gitignore)·후행 점은 확장자로 보지 않는다.
  if (dot <= 0 || dot === seg.length - 1) return '(확장자 없음)';
  return seg.slice(dot + 1).toLowerCase();
}

/** 바이트 수를 사람이 읽기 쉬운 단위 문자열로 변환한다. */
function humanSize(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

/**
 * FILE(오브젝트 스토리지) 데이터셋 전용 MCP 도구를 등록한다.
 *
 * FILE 데이터셋은 물리 테이블이 없어 execute_analytics_query/get_data_schema/search_documents
 * 대상이 아니다. 대신 이 도구들로 (1) 파일 목록 열람, (2) 라이브 매니페스트 요약,
 * (3) 다운로드/미리보기 링크 발급을 제공한다. 바이트는 프록시하지 않고 presigned URL 로 전달한다.
 */
export function registerFileObjectTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    // FILE 데이터셋의 파일 목록을 한 페이지 조회 (브라우징용)
    safeTool(
      'list_dataset_files',
      'FILE(오브젝트) 데이터셋에 저장된 파일 목록을 한 페이지 조회한다. 각 항목은 key(전체 키)·name(표시 경로)·size(바이트)·lastModified 를 가진다. 대상은 보통 find_datasets 로 찾은 storageType === "FILE" 후보의 datasetId 다. 페이지네이션: 응답 hasMore=true 면 nextToken 을 cursor 로 넘겨 다음 페이지를 조회한다. 데이터셋 전체 구성(개수·용량·형식)을 알고 싶으면 목록을 직접 순회하지 말고 summarize_dataset_files 를 사용하라.',
      {
        datasetId: z.number().describe('FILE 데이터셋 ID'),
        cursor: z
          .string()
          .optional()
          .describe('다음 페이지 조회 시 이전 응답의 nextToken 값. 첫 페이지는 생략'),
        limit: z
          .number()
          .int()
          .min(1)
          .max(200)
          .optional()
          .describe('페이지 크기 (1~200, 생략 시 50)'),
      },
      async (args: { datasetId: number; cursor?: string; limit?: number }) => {
        const res = await apiClient.listDatasetObjects(args.datasetId, args.cursor, args.limit);
        return jsonResult(res);
      },
    ),

    // FILE 데이터셋의 라이브 매니페스트 요약 (개별 파일 DB 미관리 극복)
    safeTool(
      'summarize_dataset_files',
      'FILE(오브젝트) 데이터셋의 구성 요약(매니페스트)을 만든다. 파일이 DB 에 개별 저장되지 않으므로 오브젝트 스토리지를 실시간 순회해 총 개수·총 용량·확장자별 분포·최대/최근 파일·샘플 이름을 집계한다. "이 데이터셋에 뭐가 들어있어?", "몇 개 파일이야?", "무슨 형식이야?" 류 질문에 사용한다. 스캔 상한(약 2000개)을 넘기면 capped=true 로 표시되며, 이때 scannedCount 는 부분 집계이므로 "정확히 N개"라고 단정하지 말고 countLabel 표현("≥N")을 그대로 인용하라.',
      {
        datasetId: z.number().describe('FILE 데이터셋 ID'),
      },
      async (args: { datasetId: number }) => {
        // 페이지를 순회하며 오브젝트를 수집한다(상한까지).
        const objects: ObjectItem[] = [];
        let token: string | undefined;
        let hasMore = false;
        for (let page = 0; page < SUMMARY_MAX_PAGES; page++) {
          const res = await apiClient.listDatasetObjects(args.datasetId, token, SUMMARY_PAGE_SIZE);
          objects.push(...res.objects);
          hasMore = res.hasMore;
          token = res.nextToken ?? undefined;
          if (!hasMore || !token) break;
        }
        // 상한까지 채웠는데도 더 남아 있으면 부분 집계(capped)임을 알린다.
        const capped = hasMore;

        const totalSize = objects.reduce((sum, o) => sum + (o.size ?? 0), 0);

        // 확장자별 개수·용량 집계 → 개수 내림차순 정렬
        const byExtMap = new Map<string, { count: number; size: number }>();
        for (const o of objects) {
          const ext = extensionOf(o.name);
          const cur = byExtMap.get(ext) ?? { count: 0, size: 0 };
          cur.count += 1;
          cur.size += o.size ?? 0;
          byExtMap.set(ext, cur);
        }
        const byExtension = [...byExtMap.entries()]
          .map(([ext, v]) => ({ ext, count: v.count, size: v.size, sizeHuman: humanSize(v.size) }))
          .sort((a, b) => b.count - a.count);

        // 최대 용량 상위 5개
        const largest = [...objects]
          .sort((a, b) => (b.size ?? 0) - (a.size ?? 0))
          .slice(0, 5)
          .map((o) => ({ name: o.name, size: o.size, sizeHuman: humanSize(o.size ?? 0) }));

        // 최근 수정 상위 5개 (ISO 문자열 사전식 비교 = 시간순)
        const recent = [...objects]
          .sort((a, b) => (b.lastModified ?? '').localeCompare(a.lastModified ?? ''))
          .slice(0, 5)
          .map((o) => ({ name: o.name, lastModified: o.lastModified }));

        return jsonResult({
          datasetId: args.datasetId,
          scannedCount: objects.length,
          capped,
          // LLM 이 총합을 표현할 때 그대로 인용하도록 라벨을 제공한다.
          countLabel: capped
            ? `≥${objects.length} (스캔 상한 ${SUMMARY_SCAN_CAP}개 도달 — 실제 파일 수는 더 많음)`
            : `${objects.length}`,
          totalSize,
          totalSizeHuman: humanSize(totalSize),
          byExtension,
          largest,
          recent,
          sampleNames: objects.slice(0, 10).map((o) => o.name),
        });
      },
    ),

    // 특정 파일의 다운로드/미리보기 presigned URL 발급
    safeTool(
      'get_dataset_file_url',
      'FILE 데이터셋의 특정 파일에 대한 단기 다운로드/미리보기 링크(presigned URL)를 발급한다. key 는 list_dataset_files 가 반환한 오브젝트의 전체 key 를 그대로 넘긴다. URL 은 수 분 내 만료되므로 사용 직전에 발급하고, 응답의 url 을 사용자에게 링크로 제시한다. (에이전트가 파일 바이트를 직접 읽지는 않는다 — 브라우저가 링크로 내려받는다.)',
      {
        datasetId: z.number().describe('FILE 데이터셋 ID'),
        key: z
          .string()
          .describe('오브젝트 전체 key (list_dataset_files 결과의 key 필드). name 이 아니라 key 를 넘긴다'),
      },
      async (args: { datasetId: number; key: string }) => {
        const res = await apiClient.getDatasetObjectUrl(args.datasetId, args.key);
        return jsonResult(res);
      },
    ),
  ];
}
