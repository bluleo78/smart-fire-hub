/**
 * Resolve effective system prompt with optional override.
 * When override is true, the custom prompt completely replaces the base prompt (e.g., proactive agent).
 * Otherwise, the custom prompt is appended as user instructions.
 */
export function resolveSystemPrompt(
  basePrompt: string,
  systemPrompt?: string,
  override?: boolean,
): string {
  if (override && systemPrompt) return systemPrompt;
  return systemPrompt
    ? `${basePrompt}\n\n[사용자 지시사항]\n${systemPrompt}`
    : basePrompt;
}
