// A1 GraphRAG 평가 CLI 셸 — 라이브 배선(Neo4j/API/claude CLI)만 담당하는 얇은 스크립트.
// 유닛테스트 없음(전제조건: 데이터셋 적재 + Neo4j + claude CLI OAuth). `pnpm eval:a1 <datasetId> [label]`로 실행.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createCliCompleter } from '../llm-cli.js';
import { retrieve } from '../retriever.js';
import { FireHubApiClient } from '../../mcp/api-client.js';
import { loadQuestions } from './questions.js';
import { runEval } from './orchestrator.js';
import { aggregate, renderScorecard } from './scorecard.js';

const HERE = dirname(fileURLToPath(import.meta.url));
// 리포지토리 루트 기준 합성 샘플 문서 경로(docs/superpowers/fixtures/graphrag-samples).
const FIXTURES_DIR = join(HERE, '../../../../..', 'docs/superpowers/fixtures/graphrag-samples');
const REPORT_COUNT = 6;

// report-01.md ~ report-06.md 를 이어붙여 심판 프롬프트의 [원문]으로 사용한다.
function loadSourceDocs(): string {
  const parts: string[] = [];
  for (let i = 1; i <= REPORT_COUNT; i++) {
    const num = String(i).padStart(2, '0');
    const path = join(FIXTURES_DIR, `report-${num}.md`);
    parts.push(readFileSync(path, 'utf-8'));
  }
  return parts.join('\n\n---\n\n');
}

// retrieve() 결과(서브그래프+출처 청크)를 answer 프롬프트용 컨텍스트 블록 문자열 배열로 포맷한다.
function formatGraphContext(nodes: { key: string; type: string; name: string }[],
  relations: { subject: string; type: string; object: string }[],
  sourceChunks: { chunkId: number; fileName: string; content: string }[]): string[] {
  const nodeBlock = nodes.length
    ? `[서브그래프 노드]\n${nodes.map((n) => `- (${n.type}) ${n.name}`).join('\n')}`
    : '[서브그래프 노드]\n(없음)';
  const relBlock = relations.length
    ? `[서브그래프 관계]\n${relations.map((r) => `- ${r.subject} -[${r.type}]-> ${r.object}`).join('\n')}`
    : '[서브그래프 관계]\n(없음)';
  const chunkBlocks = sourceChunks.map((c) => `[출처 청크: ${c.fileName}#${c.chunkId}]\n${c.content}`);
  return [nodeBlock, relBlock, ...chunkBlocks];
}

// searchDocuments 청크 결과를 answer 프롬프트용 컨텍스트 블록 문자열 배열로 포맷한다.
function formatVectorContext(hits: { chunkId: number; fileName: string; content: string }[]): string[] {
  return hits.map((c) => `[출처 청크: ${c.fileName}#${c.chunkId}]\n${c.content}`);
}

async function main(): Promise<void> {
  const datasetIdArg = process.argv[2];
  if (!datasetIdArg) {
    throw new Error('사용법: eval:a1 <datasetId> [label]');
  }
  const label = process.argv[3] ?? datasetIdArg;

  const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:5010/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
  // 평가 스크립트는 사용자 대행이 필요 없는 시스템 배치 실행이므로 임의 시스템 userId(0)를 사용한다.
  const apiClient = new FireHubApiClient(apiBaseUrl, internalToken, 0);

  const complete = createCliCompleter();
  const sourceDocs = loadSourceDocs();
  const questions = loadQuestions();

  const deps = {
    graphragContext: async (question: string): Promise<string[]> => {
      const result = await retrieve(
        {
          searchDocuments: (q, ids, topK, mode) =>
            apiClient.searchDocuments(q, ids, topK, mode as 'SEMANTIC' | 'KEYWORD' | 'HYBRID' | undefined),
        },
        question,
      );
      return formatGraphContext(result.nodes, result.relations, result.sourceChunks);
    },
    vectorContext: async (question: string): Promise<string[]> => {
      // graphragContext(retrieve 내부)가 datasetIds=undefined(전역 검색)로 시드를 잡으므로,
      // 검색 경로만 다르고 검색 범위는 동일해야 하는 공정 비교를 위해 벡터 경로도 전역 검색으로 맞춘다.
      const hits = await apiClient.searchDocuments(question, undefined, 8, 'HYBRID');
      return formatVectorContext(hits);
    },
    complete,
  };

  const results = await runEval(deps, questions, sourceDocs);
  const agg = aggregate(results);
  const markdown = renderScorecard(agg, results, label);

  const outDir = join(HERE, '../../../../..', 'test-results/eval');
  mkdirSync(outDir, { recursive: true });
  const outPath = join(outDir, `graphrag-a1-${label}.md`);
  writeFileSync(outPath, markdown, 'utf-8');
  console.log(`스코어카드 작성 완료: ${outPath}`);
}

main().catch((err) => {
  console.error('[eval:a1] 실행 실패:', err);
  process.exitCode = 1;
});
