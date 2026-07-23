// 평가용 통제 답변 생성 — 제공된 컨텍스트 블록만 근거로 답하게 한다(양 경로 동일 프롬프트·모델 → 공정 비교).
import type { CompleteFn } from '../llm-cli.js';

// 근거-한정 시스템 프롬프트 조립. contextBlocks = 검색 경로별로 만든 컨텍스트(서브그래프+청크 or 청크).
export function buildAnswerPrompt(contextBlocks: string[]): string {
  const ctx = contextBlocks.map((b, i) => `[근거 ${i + 1}]\n${b}`).join('\n\n');
  return `너는 화재조사 문서 QA 도우미다. 아래 제공된 근거만 사용해 질문에 답하라.
근거에 없으면 "근거 없음"이라고 답하라. 추측 금지.

[제공된 컨텍스트]
${ctx}`;
}

// 질문 + 컨텍스트로 답변을 생성한다.
export async function answerFromContext(
  complete: CompleteFn, question: string, contextBlocks: string[],
): Promise<string> {
  return complete(buildAnswerPrompt(contextBlocks), question);
}
