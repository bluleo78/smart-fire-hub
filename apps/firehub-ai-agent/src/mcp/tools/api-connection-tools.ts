import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * 명백한 더미·placeholder 자격증명 토큰 패턴.
 * 모델이 "인증 없는 공개 API"를 처리할 때 임의로 합성하는 값을 차단하기 위한 서버측 가드.
 * 회귀 이슈 #255 (api-connection-manager 가짜 자격증명 합성) 참조.
 */
const PLACEHOLDER_AUTH_VALUES = new Set([
  'none',
  'null',
  'nil',
  'undefined',
  'dummy',
  'placeholder',
  'todo',
  'tbd',
  'n/a',
  'na',
  'xxx',
  'xxxx',
  'test',
  'sample',
  'example',
  'changeme',
  'change-me',
  'change_me',
  'your-token',
  'your_token',
  'your-api-key',
  'your_api_key',
  'fake',
  'no-auth',
  'noauth',
  'x-no-auth',
  '0',
  '000',
  '0000',
]);

/**
 * 더미/placeholder 값이 접두사로 포함된 변형 패턴.
 * (#619) 정확일치(Set) 검사만으로는 "dummyvalue12345", "testkey123"처럼
 * 더미 단어 뒤에 다른 문자가 붙은 변형이 그대로 통과해, LLM의 prompt-level
 * 판단(비결정적)에만 의존하게 되는 문제를 서버측에서 보강한다.
 * 접두사 매칭만 사용해 "attestation-key-xyz" 같은 정상 값의 중간에
 * 우연히 단어가 섞이는 오탐(false positive)은 배제한다.
 */
const PLACEHOLDER_PREFIXES = [
  'dummy',
  'placeholder',
  'test',
  'sample',
  'example',
  'fake',
  'mock',
  'changeme',
  'change-me',
  'change_me',
  'todo',
  'tbd',
  'fixme',
  'your-token',
  'your_token',
  'your-api-key',
  'your_api_key',
  'xxx',
];

/**
 * 반복/순차 패턴 문자열 여부를 판별한다 (예: "aaaaaaaa", "000000000", "123456789").
 * 실제 발급된 키는 통계적으로 균일하게 무작위이므로 이런 패턴은 거의 나오지 않는다 (#619).
 */
function isRepeatedOrSequentialPattern(value: string): boolean {
  if (value.length < 4) return false;
  // 단일 문자 반복 (예: "xxxxxxxx", "0000000000")
  if (/^(.)\1+$/.test(value)) return true;
  // 오름차순 연속 숫자 (예: "0123456789", "123456")
  const digits = '0123456789';
  if (/^\d+$/.test(value) && digits.includes(value)) return true;
  return false;
}

/**
 * authConfig가 명백히 placeholder/더미 값을 포함하는지 검증한다.
 * 빈 문자열·공백만·`none`/`dummy` 등 placeholder는 거부.
 * authType별 필수 필드(API_KEY: apiKey+headerName, BEARER: token)도 함께 확인.
 *
 * 회귀 방지: 모델이 사용자에게 더미 값을 권유하거나 자가 보정으로 빈 문자열을
 * 채워 호출하는 경로를 MCP 스키마 레벨에서 차단한다 (#255).
 */
export function assertAuthConfigNotPlaceholder(
  authType: string | undefined,
  authConfig: Record<string, string> | undefined,
): void {
  if (!authConfig) return; // update에서 authConfig 미제공 시는 통과

  // 1) 빈/공백/placeholder 값 거부 — 모든 필드 공통
  for (const [field, value] of Object.entries(authConfig)) {
    if (typeof value !== 'string') {
      throw new Error(
        `authConfig.${field}는 문자열이어야 합니다. 사용자에게 실제 인증 정보를 다시 요청하세요. (#255 더미 자격증명 합성 방지)`,
      );
    }
    const trimmed = value.trim();
    if (trimmed.length === 0) {
      throw new Error(
        `authConfig.${field}가 빈 값입니다. "인증 없는 공개 API"는 지원되지 않습니다 — 사용자에게 실제 API_KEY 또는 BEARER 토큰을 요구하세요. 빈 문자열/더미 값으로 우회 등록 금지 (#255).`,
      );
    }
    const normalized = trimmed.toLowerCase();
    if (PLACEHOLDER_AUTH_VALUES.has(normalized)) {
      throw new Error(
        `authConfig.${field}="${value}"는 명백한 placeholder 값입니다. 사용자에게 실제 인증 정보를 받기 전에는 등록할 수 없습니다 — 더미 권유·자가 보정 금지 (#255).`,
      );
    }
    // 접두사 기반 변형 패턴 차단 — "dummyvalue12345", "testkey123" 등 (#619)
    const matchedPrefix = PLACEHOLDER_PREFIXES.find((p) => normalized.startsWith(p));
    if (matchedPrefix) {
      throw new Error(
        `authConfig.${field}="${value}"는 더미 패턴("${matchedPrefix}"로 시작)으로 보입니다. 사용자에게 실제 인증 정보를 받기 전에는 등록할 수 없습니다 — 더미 권유·자가 보정 금지 (#619).`,
      );
    }
    // 반복/순차 패턴 차단 — "xxxxxxxx", "000000000", "123456789" 등 (#619)
    if (isRepeatedOrSequentialPattern(normalized)) {
      throw new Error(
        `authConfig.${field}="${value}"는 반복/순차 패턴으로 실제 발급된 키로 보이지 않습니다. 사용자에게 실제 인증 정보를 재확인하세요 (#619).`,
      );
    }
    // 너무 짧은 값(< 3자) — 의도된 더미일 가능성 높음
    if (trimmed.length < 3) {
      throw new Error(
        `authConfig.${field}가 너무 짧습니다(${trimmed.length}자). 실제 인증 값인지 사용자에게 재확인하세요 (#255).`,
      );
    }
  }

  // 2) authType별 필수 필드 확인 (수정 시 authType이 같이 제공된 경우만 강제)
  if (authType === 'API_KEY') {
    if (!authConfig.apiKey || !authConfig.headerName) {
      throw new Error(
        'API_KEY 인증은 authConfig.{apiKey, headerName} 두 필드가 모두 필요합니다.',
      );
    }
  } else if (authType === 'BEARER') {
    if (!authConfig.token) {
      throw new Error('BEARER 인증은 authConfig.token이 필요합니다.');
    }
  }
}

export function registerApiConnectionTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool('list_api_connections', '저장된 API 연결 목록을 조회합니다', {}, async () => {
      const result = await apiClient.listApiConnections();
      return jsonResult(result);
    }),

    safeTool(
      'get_api_connection',
      'API 연결 상세 정보를 조회합니다 (인증 값은 마스킹됨)',
      {
        id: z.number().describe('API 연결 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.getApiConnection(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'create_api_connection',
      '새 API 연결을 생성합니다. baseUrl은 필수, healthCheckPath는 선택. 인증 정보는 AES-256-GCM 암호화되어 저장됩니다.',
      {
        name: z.string().describe('연결 이름 (예: Make.com API)'),
        description: z.string().optional().describe('연결 설명'),
        authType: z.enum(['API_KEY', 'BEARER']).describe('인증 유형'),
        authConfig: z
          .record(z.string(), z.string())
          .describe(
            '인증 설정 — API_KEY: { placement, headerName/paramName, apiKey }, BEARER: { token }',
          ),
        baseUrl: z
          .string()
          .url()
          .describe('호출 대상 서비스의 기본 URL (예: https://api.make.com/v2, trailing slash 불필요)'),
        healthCheckPath: z
          .string()
          .regex(/^\//)
          .optional()
          .describe('주기적 상태 점검 경로 (예: /health). 생략 시 점검 미수행.'),
      },
      async (args) => {
        // 빈/더미 자격증명 합성 차단 (#255 회귀 방지)
        assertAuthConfigNotPlaceholder(args.authType, args.authConfig);
        const result = await apiClient.createApiConnection(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'update_api_connection',
      'API 연결을 수정합니다. authConfig를 제공하면 인증 정보가 갱신됩니다. baseUrl/healthCheckPath도 변경 가능.',
      {
        id: z.number().describe('API 연결 ID'),
        name: z.string().optional().describe('연결 이름'),
        description: z.string().optional().describe('연결 설명'),
        authType: z.string().optional().describe('인증 유형 (API_KEY 또는 BEARER)'),
        authConfig: z.record(z.string(), z.string()).optional().describe('인증 설정'),
        baseUrl: z.string().url().optional().describe('기본 URL 변경'),
        healthCheckPath: z
          .string()
          .regex(/^\//)
          .optional()
          .describe('상태 점검 경로 변경 (/ 로 시작)'),
      },
      async (args) => {
        const { id, ...data } = args;
        // authConfig가 제공된 경우 더미 값 차단 (#255 회귀 방지)
        assertAuthConfigNotPlaceholder(data.authType, data.authConfig);
        const result = await apiClient.updateApiConnection(id, data);
        return jsonResult(result);
      },
    ),

    safeTool(
      'test_api_connection',
      '저장된 API 연결의 상태를 즉시 점검합니다. healthCheckPath(또는 baseUrl)로 GET 요청 후 2xx 여부로 UP/DOWN 판정합니다. 결과는 DB에 반영되어 이후 list에 노출됩니다.',
      {
        id: z.number().describe('API 연결 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.testApiConnection(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'delete_api_connection',
      'API 연결을 삭제합니다',
      {
        id: z.number().describe('API 연결 ID'),
      },
      async (args: { id: number }) => {
        const result = await apiClient.deleteApiConnection(args.id);
        return jsonResult(result);
      },
    ),

    safeTool(
      'get_api_connection_references',
      '이 API 연결을 사용하는 파이프라인(API_CALL 스텝의 api_connection_id 참조)을 조회합니다. delete_api_connection 호출 전 영향 범위 확인 필수 — totalCount>0이면 반드시 참조 파이프라인 이름/개수를 사용자에게 고지할 것 (#605).',
      { id: z.number().describe('API 연결 ID') },
      async (args: { id: number }) => {
        const result = await apiClient.getApiConnectionReferences(args.id);
        return jsonResult(result);
      },
    ),
  ];
}
