// GraphRAG 엔티티 해소용 순수 벡터 유틸 — 두 임베딩 벡터의 코사인 유사도를 계산한다.
// 임베딩 생성 자체는 firehub-api 의 활성 provider(system_settings 기반, OLLAMA/OPENAI)에 위임하므로
// 이 파일은 더 이상 Ollama 를 직접 호출하지 않는다. (semantic-resolver.ts 의 시맨틱 클러스터링에서 사용)

// 두 벡터의 코사인 유사도 = 내적 / (노름의 곱). 차원이 다르거나 영벡터면 0을 반환한다.
export function cosineSimilarity(a: number[], b: number[]): number {
  const len = Math.min(a.length, b.length);
  let dot = 0, normA = 0, normB = 0;
  for (let i = 0; i < len; i += 1) {
    dot += a[i] * b[i];
    normA += a[i] * a[i];
    normB += b[i] * b[i];
  }
  if (normA === 0 || normB === 0) return 0;
  return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}
