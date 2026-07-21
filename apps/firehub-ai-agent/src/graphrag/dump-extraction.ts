// 합성 샘플 문서들을 extractor로 돌려 결과 JSON을 stdout에 덤프한다.
// 목적: Neo4j 배선 전에 추출 품질을 사람이 눈으로 검수(엔티티 해소·타입 준수)한다.
// LLM 호출은 인증된 claude CLI 헤드리스 실행을 사용한다(로컬은 macOS 키체인, prod는 CLAUDE_CODE_OAUTH_TOKEN).
// 실행: cd apps/firehub-ai-agent && npx tsx src/graphrag/dump-extraction.ts
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { extractGraph } from './extractor.js';
import { createCliCompleter } from './llm-cli.js';
import { CORE_ONTOLOGY } from './ontology.js';

async function main() {
  const dir = resolve(process.cwd(), '../../docs/superpowers/fixtures/graphrag-samples');
  const complete = createCliCompleter();
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.md'))) {
    const text = readFileSync(resolve(dir, file), 'utf8');
    const result = await extractGraph(text, { complete, ontology: CORE_ONTOLOGY });
    console.log(`\n===== ${file} =====`);
    console.log(JSON.stringify(result, null, 2));
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
