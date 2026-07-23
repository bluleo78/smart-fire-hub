// LLM-as-judge — 원문 근거로 두 답변(A/B)을 정확성·근거성 1~5 채점 + 승자. 위치편향은 호출부 순서 스왑으로 완화.
import type { CompleteFn } from '../llm-cli.js';
import { JudgeVerdict } from './types.js';

export function buildJudgePrompt(
  question: string,
  sourceDocs: string,
  answerA: string,
  answerB: string,
): string {
  return `너는 엄격한 QA 평가자다. 아래 [원문]을 기준으로 질문에 대한 두 답변을 채점하라.
정확성(원문과 일치)과 근거성(원문으로 뒷받침)을 각각 고려해 1~5로 종합 점수를 매긴다.

[원문]
${sourceDocs}

[질문] ${question}
[답변 A] ${answerA}
[답변 B] ${answerB}

다음 JSON 코드블록만 출력하라(설명 금지):
\`\`\`json
{"scoreA":<1-5>,"scoreB":<1-5>,"winner":"A|B|tie","rationale":"한 줄 근거"}
\`\`\``;
}

// JSON 코드블록을 파싱. 실패 시 안전하게 tie 폴백(비결정 LLM 방어).
export function parseJudgeVerdict(text: string): JudgeVerdict {
  const m = text.match(/```json\s*([\s\S]*?)```/);
  const raw = m ? m[1] : text;
  try {
    const o = JSON.parse(raw.trim());
    const scoreA = Number(o.scoreA);
    const scoreB = Number(o.scoreB);
    const winner = o.winner === 'A' || o.winner === 'B' ? o.winner : 'tie';
    if (!Number.isFinite(scoreA) || !Number.isFinite(scoreB)) throw new Error('점수 없음');
    return { scoreA, scoreB, winner, rationale: String(o.rationale ?? '') };
  } catch {
    return { scoreA: 0, scoreB: 0, winner: 'tie', rationale: '판정 파싱 실패' };
  }
}

export async function judge(
  complete: CompleteFn,
  question: string,
  sourceDocs: string,
  answerA: string,
  answerB: string,
): Promise<JudgeVerdict> {
  // complete()는 userText를 CLI stdin으로 전달하는데 빈 문자열은 CLI가 거부한다(-p 헤드리스는
  // stdin/prompt 인자 중 하나가 반드시 필요) — question(항상 비어있지 않음)을 그대로 전달한다.
  return parseJudgeVerdict(
    await complete(buildJudgePrompt(question, sourceDocs, answerA, answerB), question),
  );
}
