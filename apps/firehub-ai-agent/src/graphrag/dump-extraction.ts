// 합성 샘플 문서들을 extractor로 돌려 결과 JSON을 stdout에 덤프한다.
// 목적: Neo4j 배선 전에 추출 품질을 사람이 눈으로 검수(엔티티 해소·타입 준수)한다.
// 실행: cd apps/firehub-ai-agent && ANTHROPIC_API_KEY=... npx tsx src/graphrag/dump-extraction.ts
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { extractGraph } from './extractor.js';

async function main() {
  const dir = resolve(process.cwd(), '../../docs/superpowers/fixtures/graphrag-samples');
  const model = process.env.AI_DEFAULT_MODEL ?? 'claude-haiku-4-5';
  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) throw new Error('ANTHROPIC_API_KEY 필요');
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.md'))) {
    const text = readFileSync(resolve(dir, file), 'utf8');
    const result = await extractGraph(text, { model, apiKey });
    console.log(`\n===== ${file} =====`);
    console.log(JSON.stringify(result, null, 2));
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
