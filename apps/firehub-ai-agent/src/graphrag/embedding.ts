// Ollama bge-m3 임베딩 클라이언트 — 텍스트 배열을 벡터 배열로 변환하고, 코사인 유사도를 계산한다.
// entity resolution의 시맨틱 클러스터링(semantic-resolver.ts)에서 사용된다.
import axios from 'axios';

export interface EmbeddingOptions { baseUrl?: string; model?: string; }

const DEFAULT_BASE_URL = process.env.OLLAMA_BASE_URL ?? 'http://localhost:11434';
const DEFAULT_MODEL = process.env.EMBEDDING_MODEL ?? 'bge-m3';

// Ollama /api/embeddings는 요청 1건당 prompt 1개만 받는다 → 입력 텍스트마다 순차 호출해
// 순서를 보존한 벡터 배열을 만든다.
export async function embedTexts(texts: string[], opts: EmbeddingOptions = {}): Promise<number[][]> {
  const baseUrl = opts.baseUrl ?? DEFAULT_BASE_URL;
  const model = opts.model ?? DEFAULT_MODEL;
  const vectors: number[][] = [];
  for (const text of texts) {
    const resp = await axios.post<{ embedding: number[] }>(
      `${baseUrl}/api/embeddings`,
      { model, prompt: text },
      { headers: { 'content-type': 'application/json' }, timeout: 60_000 },
    );
    vectors.push(resp.data.embedding);
  }
  return vectors;
}

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
