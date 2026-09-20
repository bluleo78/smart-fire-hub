/**
 * opencode CLI 가 spawn 하는 firehub MCP 자식 프로세스(stdio-server.ts)에 테넌트의 opencode
 * provider 자격증명(providerId/baseUrl/apiKey/reasoningEffort/model)을 전달하는 환경변수 키
 * 이름의 단일 출처.
 *
 * 왜 상수로 뽑았나: 쓰는 쪽(agent-opencode.ts 의 buildOpenCodeConfig 가 mcp.firehub.environment
 * 에 채운다)과 읽는 쪽(stdio-server.ts 의 resolveStdioCredentials)이 서로 다른 파일이다. 문자열
 * 리터럴을 양쪽에 복제하면 한쪽 철자만 바뀌어도 조용히 끊어진다 — 설계서가 요청 바디 필드명에
 * 대해 경고하는 것과 같은 함정이 여기서는 환경변수 이름으로 재현될 수 있다.
 *
 * `AI_CREDENTIAL_` 접두는 opencode 자체 환경변수(`OPENCODE_*`)와 이름이 겹치지 않도록 하기
 * 위함이다 — opencode CLI 는 `OPENCODE_CONFIG`/`OPENCODE_PERMISSION` 등 자체 규약을 이미 여럿
 * 갖고 있어, `OPENCODE_` 로 시작하는 새 이름을 만들면 미래의 opencode 버전과 충돌할 수 있다.
 */
export const OPENCODE_MCP_CREDENTIAL_ENV = {
  /** GraphRAG completion 이 opencode 분기를 타야 하는지 판별하는 값. 항상 `'opencode'`. */
  AGENT_TYPE: 'AI_CREDENTIAL_AGENT_TYPE',
  PROVIDER_ID: 'AI_CREDENTIAL_PROVIDER_ID',
  BASE_URL: 'AI_CREDENTIAL_BASE_URL',
  API_KEY: 'AI_CREDENTIAL_API_KEY',
  REASONING_EFFORT: 'AI_CREDENTIAL_REASONING_EFFORT',
  MODEL: 'AI_CREDENTIAL_MODEL',
} as const;
