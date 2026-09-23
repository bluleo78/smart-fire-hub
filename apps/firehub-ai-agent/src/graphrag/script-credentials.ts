// 단독 개발 스크립트(dump-extraction.ts, eval/run-eval.ts)용 자격증명 읽기 (#708).
//
// 서버 코드는 ambient 자격증명을 절대 읽지 않는다 — 자격증명은 항상 요청(firehub-api)이 준다.
// 개발 스크립트에는 요청이 없으므로, 스크립트 진입점에서만 이 함수로 env 를 읽어 createCompleter 에
// **명시적으로** 넘긴다. 로컬 CLI 키체인 로그인은 더 이상 쓰이지 않는다(서버 경로와 같은 규칙).

import { resolveClaudeCredential } from '../agent/claude-child-env.js';

/**
 * 스크립트 실행 env 에서 Anthropic 자격증명을 읽는다. 둘 다 없으면 사용법 안내와 함께 실패한다.
 * OAuth 토큰이 API 키보다 우선한다(서버 경로와 같은 규칙).
 */
export function readScriptCredentials(env: NodeJS.ProcessEnv = process.env): {
  apiKey?: string;
  oauthToken?: string;
} {
  // 서버 경로와 같은 판정(공백은 "없음", OAuth 우선)을 쓴다.
  const credential = resolveClaudeCredential({
    oauthToken: env.CLAUDE_CODE_OAUTH_TOKEN,
    apiKey: env.ANTHROPIC_API_KEY,
  });
  if (!credential) {
    throw new Error(
      '자격증명이 없습니다 — CLAUDE_CODE_OAUTH_TOKEN 또는 ANTHROPIC_API_KEY 를 설정해 실행하세요.',
    );
  }
  return { [credential.kind]: credential.value };
}
