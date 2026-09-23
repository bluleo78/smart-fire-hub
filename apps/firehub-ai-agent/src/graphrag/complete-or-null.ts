// GraphRAG LLM 호출의 공통 실패 정책 (#711).
//
// extractor / mapping-inference / ontology-inference / semantic-link 는 LLM 호출이 실패해도 배치를
// 멈추지 않고 빈 결과(또는 "병합 안 함")로 계속하는 fail-soft 설계다. 단 자격증명 실패(자격증명 없음·
// 인증·결제)는 청크·쌍마다 똑같이 반복될 뿐이라 삼키면 원인이 사라지고 "추출 0건" 같은 엉뚱한 결과만
// 남는다 — 그것만은 다시 던진다. 네 호출부가 이 규칙을 각자 복제하지 않도록 한 곳에 둔다.
//
// llm-completer.ts 가 아니라 별도 파일인 이유: llm-completer 는 providers(→ Agent SDK·MCP 서버)를
// 런타임에 끌어오므로, 순수 로직 모듈인 네 호출부가 그 무거운 의존을 들이지 않게 분리한다.
import type { CompleteFn } from './llm-completer.js';
import { isAiCredentialFailure } from '../agent/ai-auth-failure.js';

/**
 * LLM 을 호출해 응답 텍스트를 돌려준다. 자격증명 실패는 다시 던지고, 그 밖의 실패는 경고 로그를 남긴 뒤
 * null 을 돌려준다 — 호출부는 null 을 받으면 자기 폴백(빈 결과 등)으로 계속한다.
 *
 * @param label 경고 로그에 남길 호출부 이름(진단용).
 */
export async function completeOrNull(
  complete: CompleteFn,
  systemPrompt: string,
  userText: string,
  label: string,
): Promise<string | null> {
  try {
    return await complete(systemPrompt, userText);
  } catch (err) {
    if (isAiCredentialFailure(err)) throw err;
    console.warn(`[graphrag] ${label} LLM 호출 실패, 폴백으로 계속:`, err);
    return null;
  }
}
