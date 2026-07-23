// 임베딩 임계값(0.78) 미달이지만 완전히 무관하지는 않은 근접쌍(0.5~0.78)을 LLM으로 재판단해
// 표기 변형을 넘어선 의미적 동의어(예: "전기적 요인" ↔ "분전반의 누전")를 병합 후보로 승격한다.
// judge.ts와 동일하게 CLI 기반 CompleteFn을 감싼다.
import type { CompleteFn } from './llm-cli.js';
import type { EntityType } from './ontology.js';

export function buildLinkPrompt(nameA: string, nameB: string, entityType: EntityType): string {
  return `너는 화재조사 도메인 지식그래프의 엔티티 해소 판정자다.
아래 두 이름이 같은 ${entityType}를 가리키는지 판단하라. 표현이 달라도 의미가 같으면(동일 원인/설비/규정을
다르게 표현) "같다"로, 실제로 다른 대상이면(둘 다 그럴듯해 보여도 별개 사실) "다르다"로 판단한다.

[이름 A] ${nameA}
[이름 B] ${nameB}

다음 JSON 코드블록만 출력하라(설명 금지):
\`\`\`json
{"same":<true|false>,"rationale":"한 줄 근거"}
\`\`\``;
}

// JSON 코드블록을 파싱. 실패 시 false(병합 안 함)로 안전 폴백 —
// 잘못된 병합(서로 다른 엔티티를 하나로 합침)이 병합 누락보다 더 나쁜 실패라서 보수적으로 간다.
export function parseLinkVerdict(text: string): boolean {
  const m = text.match(/```json\s*([\s\S]*?)```/);
  const raw = m ? m[1] : text;
  try {
    const o = JSON.parse(raw.trim());
    return o.same === true;
  } catch {
    return false;
  }
}

export type LinkFn = (nameA: string, nameB: string, entityType: EntityType) => Promise<boolean>;

export async function link(
  complete: CompleteFn,
  nameA: string,
  nameB: string,
  entityType: EntityType,
): Promise<boolean> {
  try {
    // complete()는 userText를 CLI stdin으로 전달하며 빈 문자열은 CLI가 거부한다
    // (judge.ts에서 실측된 버그 클래스) — nameA(항상 비어있지 않음)를 그대로 전달한다.
    return parseLinkVerdict(
      await complete(buildLinkPrompt(nameA, nameB, entityType), nameA),
    );
  } catch {
    // LLM 호출 자체 실패(CLI 에러 등) — 이 쌍만 병합 안 함으로 처리하고 나머지는 계속 진행.
    return false;
  }
}
