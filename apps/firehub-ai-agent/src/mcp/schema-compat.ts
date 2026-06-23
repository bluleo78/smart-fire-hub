/**
 * OpenCode 게이트웨이 호환을 위한 JSON Schema 정제.
 *
 * 배경(2026-06-24 실측): 일부 OpenAI-호환 게이트웨이(예: Bedrock OpenAI-compatible)는
 * JSON Schema 의 `propertyNames` 키를 거부해 tool 정의가 포함된 요청을 400 으로 반려한다.
 * `propertyNames`(키는 문자열)는 JSON 객체 키가 항상 문자열이므로 의미상 잉여이며,
 * 제거해도 동작/검증에 손실이 없다. Zod v4 의 `z.record(z.string(), ...)` 가 이 키를 내보낸다.
 *
 * Anthropic API 직결(sdk/cli) 경로는 `propertyNames` 를 받아들이므로 영향이 없다.
 * 따라서 이 정제는 OpenCode 경로에서만(stdio 서버에 OPENCODE_SCHEMA_COMPAT 주입 시) 적용한다.
 */

/** 환경변수로 OpenCode 스키마 호환 모드 여부 판단. */
export function isOpenCodeSchemaCompat(): boolean {
  return process.env.OPENCODE_SCHEMA_COMPAT === '1';
}

/**
 * 임의의 JSON 값에서 모든 `propertyNames` 키를 재귀적으로 제거한다(in-place).
 * 객체/배열을 깊이 순회하며, 다른 키는 그대로 둔다.
 */
export function stripPropertyNames(value: unknown): void {
  if (Array.isArray(value)) {
    for (const item of value) stripPropertyNames(item);
    return;
  }
  if (value && typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    if ('propertyNames' in obj) delete obj.propertyNames;
    for (const key of Object.keys(obj)) stripPropertyNames(obj[key]);
  }
}

/**
 * tools/list JSON-RPC 응답 메시지에서 각 도구의 inputSchema 를 정제한다.
 * 응답 형태가 아니면 그대로 둔다. (transport.send 래핑에서 사용)
 */
export function sanitizeOutgoingMessage(message: unknown): void {
  const msg = message as { result?: { tools?: Array<{ inputSchema?: unknown }> } };
  const tools = msg?.result?.tools;
  if (!Array.isArray(tools)) return;
  for (const tool of tools) {
    if (tool.inputSchema) stripPropertyNames(tool.inputSchema);
  }
}
