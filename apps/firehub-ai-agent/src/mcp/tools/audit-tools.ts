import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';
import {
  MAX_AUDIT_FILTER_LENGTH,
  MAX_SEARCH_QUERY_LENGTH,
  clampSearchQuery,
} from '../api-client/request-limits.js';
import { KST_UTC_OFFSET } from './kst-time.js';

/** WD-1131: 검색어 상한을 설명·안내 문구에 쓰는 표기("1,000") — 상수에서 만들어 문구와 실제 상한이 어긋나지 않게 한다 */
const SEARCH_LIMIT_TEXT = MAX_SEARCH_QUERY_LENGTH.toLocaleString('en-US');

/**
 * 감사 조회 경계에 붙일 오프셋(WD-11).
 * 채팅에는 사용자 시간대가 실려 오지 않으므로, 다른 도구(트리거·프로액티브 기본값)와 같은 관례로 Asia/Seoul(KST)을 쓴다.
 * KST 고정, 서머타임 없음 — 그래서 상수 오프셋이면 충분하다. 사용자별 시간대가 생기면 다시 일반화한다.
 */
export const AUDIT_QUERY_UTC_OFFSET = KST_UTC_OFFSET;

const HAS_OFFSET = /(Z|[+-]\d{2}:?\d{2})$/i;
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;
const LOCAL_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/;

/**
 * LLM 이 쓴 감사 경계 시각에 사용자 시간대 오프셋을 붙인다(WD-11).
 *
 * 왜: 서버는 오프셋 없는 값을 저장 TZ 벽시계로 그대로 쓰는데, 운영 저장 TZ 는 UTC 라 KST 로 묻는 사용자에게 결과가
 * 9시간 밀린다. 오프셋이 이미 있으면 그 순간을 존중해 그대로 두고, 날짜만 오면 하루 전체(시작 00:00:00, 종료 23:59:59 —
 * 종료는 서버에서 이하 조건)로 편 뒤 붙인다. 형식을 알 수 없으면 그대로 보내 서버가 400 으로 알리게 한다.
 * `+` 는 axios 기본 params 직렬화가 %2B 로 인코딩한다(날것의 + 는 서버에서 공백이 되어 400).
 */
export function withUserZoneOffset(
  value: string | undefined,
  boundary: 'start' | 'end',
): string | undefined {
  if (value === undefined) return undefined;
  const v = value.trim();
  if (HAS_OFFSET.test(v)) return v;
  const wall = DATE_ONLY.test(v) ? `${v}T${boundary === 'start' ? '00:00:00' : '23:59:59'}` : v;
  if (!LOCAL_DATE_TIME.test(wall) || Number.isNaN(Date.parse(`${wall}Z`))) return value;
  return `${wall}${AUDIT_QUERY_UTC_OFFSET}`;
}

/**
 * 감사 로그 조회 MCP 도구 등록.
 * audit:read 권한이 있는 세션 사용자에게만 노출된다.
 */
export function registerAuditTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_audit_logs',
      '시스템 감사 로그를 조회합니다. 사용자 활동, 리소스 변경, 실패 이벤트를 검색·필터링할 수 있습니다. 최신 항목부터 정렬됩니다.',
      {
        search: z
          .string()
          .optional()
          .describe(
            `사용자명 또는 설명 검색어 (최대 ${SEARCH_LIMIT_TEXT}자 — 넘으면 앞 ${SEARCH_LIMIT_TEXT}자로 잘라 검색하고 결과에 searchTruncated 로 알린다)`,
          ),
        userId: z
          .number()
          .optional()
          .describe(
            '사용자 ID 정확 일치 필터 (동명이인/오타 노이즈 없이 특정 사용자만 조회). userId가 주어진 조회는 search 대신 이 파라미터를 우선 사용한다.',
          ),
        actionType: z.string().max(MAX_AUDIT_FILTER_LENGTH).optional().describe('액션 유형 필터 (CREATE, UPDATE, DELETE, LOGIN, LOGOUT 등)'),
        resource: z.string().max(MAX_AUDIT_FILTER_LENGTH).optional().describe('리소스 유형 필터 (dataset, pipeline, user, trigger, role, api_connection 등)'),
        result: z.string().max(MAX_AUDIT_FILTER_LENGTH).optional().describe('결과 상태 필터 (SUCCESS, FAILURE)'),
        startDate: z
          .string()
          .optional()
          .describe(
            '조회 시작 일시 (ISO 8601, 사용자 시간대 오프셋 포함, 예: 2026-09-01T00:00:00+09:00). 날짜만 주어진 질의는 해당 날짜의 00:00:00+09:00으로 지정한다. 오프셋을 빼면 한국 시간(+09:00)으로 보고 붙인다.',
          ),
        endDate: z
          .string()
          .optional()
          .describe(
            '조회 종료 일시 (ISO 8601, 사용자 시간대 오프셋 포함, 예: 2026-09-05T23:59:59+09:00). endDate는 이하(lessOrEqual) 조건이므로 날짜만 주어진 질의는 해당 날짜를 포함하려면 23:59:59+09:00으로 지정한다. 오프셋을 빼면 한국 시간(+09:00)으로 보고 붙인다.',
          ),
        page: z.number().optional().describe('페이지 번호 (0부터 시작, 기본 0)'),
        size: z.number().optional().describe('페이지 크기 (기본 20, 최대 100)'),
      },
      async (args) => {
        // WD-11: 경계 시각은 사용자 시간대 순간으로 보낸다(서버가 저장 TZ 로 바꾼다). 
        // withUserZoneOffset 은 undefined 를 그대로 돌려주고, axios 는 undefined 파라미터를 직렬화하지 않는다.
        const params = {
          ...args,
          startDate: withUserZoneOffset(args.startDate, 'start'),
          endDate: withUserZoneOffset(args.endDate, 'end'),
        };
        const result = await apiClient.listAuditLogs(params);
        // WD-1131: api-client 가 search 를 1,000자로 잘라 보내므로, 잘렸으면 결과에 알려 LLM 이
        // 전체 검색어 기준 결과로 오해하지 않게 한다. 안 잘렸으면 api 결과를 그대로 돌려준다.
        // WD-1143: 판정은 실제 자르기(clampSearchQuery)의 결과로 한다 — 지금은 길이 비교와 같지만, 자르기 규칙이 바뀌어도
        //   안내가 실제로 보낸 검색어와 어긋나지 않게 한 곳(clampSearchQuery)만 기준으로 삼는다.
        const truncated =
          args.search !== undefined && clampSearchQuery(args.search) !== args.search;
        return jsonResult(
          truncated
            ? {
                ...result,
                searchTruncated: true,
                searchNotice:
                  `검색어가 ${SEARCH_LIMIT_TEXT}자를 넘어 앞 ${SEARCH_LIMIT_TEXT}자로만 검색했습니다 — 전체 검색어 기준 결과가 아닙니다.`,
              }
            : result,
        );
      },
    ),
  ];
}
