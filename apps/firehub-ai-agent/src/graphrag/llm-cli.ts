// GraphRAG 추출용 LLM 호출을 인증된 claude CLI(headless -p)로 위임한다.
// 이유: prod ai.api_key가 비어있고 시스템은 claude CLI OAuth(CLAUDE_CODE_OAUTH_TOKEN)로 인증한다.
// axios로 x-api-key를 직접 호출하던 기존 방식은 이제 유효하지 않다.
import { spawn } from 'node:child_process';

// (systemPrompt, userText) → LLM 응답 텍스트. extractor.ts가 이 함수를 주입받아 순수/테스트 가능하게 유지한다.
export type CompleteFn = (systemPrompt: string, userText: string) => Promise<string>;

export interface CliCompleterOptions {
  model?: string;
  command?: string;
}

// child_process 종료까지 기다리는 시간 상한(ms). 초과 시 프로세스를 죽이고 reject한다.
const DEFAULT_TIMEOUT_MS = 90_000;

// claude CLI를 headless로 스폰해 completion을 얻는 CompleteFn을 생성한다.
export function createCliCompleter(opts?: CliCompleterOptions): CompleteFn {
  const command = opts?.command ?? 'claude';
  const model = opts?.model ?? process.env.AI_CLI_MODEL;

  return (systemPrompt: string, userText: string): Promise<string> => new Promise((resolvePromise, reject) => {
    const args = ['-p', '--append-system-prompt', systemPrompt];
    if (model) args.push('--model', model);

    // env를 그대로 전달해 prod의 CLAUDE_CODE_OAUTH_TOKEN이 흘러가도록 한다.
    // 로컬에서는 CLI가 macOS 키체인 인증을 사용한다.
    const child = spawn(command, args, { env: { ...process.env } });

    let stdout = '';
    let stderr = '';
    let settled = false;

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill();
      reject(new Error(`[graphrag] claude CLI 호출이 ${DEFAULT_TIMEOUT_MS}ms 내에 끝나지 않아 종료했습니다.`));
    }, DEFAULT_TIMEOUT_MS);

    child.stdout?.on('data', (chunk: Buffer) => { stdout += chunk.toString(); });
    child.stderr?.on('data', (chunk: Buffer) => { stderr += chunk.toString(); });

    child.on('error', (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(err);
    });

    child.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (code === 0) resolvePromise(stdout);
      else reject(new Error(`[graphrag] claude CLI가 code ${code}로 종료됨: ${stderr}`));
    });

    child.stdin?.write(userText);
    child.stdin?.end();
  });
}
