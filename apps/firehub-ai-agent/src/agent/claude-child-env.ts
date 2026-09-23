/**
 * Claude Code 자식 프로세스(Agent SDK `query()` / `claude` CLI spawn / 검증 라우트의 execFile)에
 * 넘길 환경변수를 조립하는 **단일 출처**다 (이슈 #708, #711).
 *
 * <p><b>무엇을 보장하나.</b> AI 자격증명은 테넌트 전용이다(#706) — firehub-api 가 채팅·능동
 * 리포트·분류·검증 요청마다 자격증명을 실어 보낸다. 그러므로 자식이 쓰는 자격증명은 <b>요청이
 * 준 것 하나뿐</b>이어야 한다. 컨테이너/개발 머신의 ambient 값(`ANTHROPIC_API_KEY`,
 * `ANTHROPIC_AUTH_TOKEN`, `CLAUDE_CODE_OAUTH_TOKEN`, `ANTHROPIC_BASE_URL`, Bedrock/Vertex 전환
 * 스위치, AWS 자격증명 체인 등)이 새면 요청과 무관한 계정으로 조용히 과금·인증된다(6b1c6383).
 *
 * <p><b>왜 opencode 처럼 allowlist 가 아니라 "복사 후 차단"인가.</b> opencode 자식 env 는 실제
 * 바이너리를 `env -i` 로 깎아 내며 필수 키를 실측한 뒤에야 allowlist 로 뒤집었다
 * (opencode-child-env.ts). `claude` 자식은 그런 실측이 없고, 필요한 키(`CLAUDE_CODE_*` 동작 플래그,
 * 로캘, 프록시·CA, `HOME` 아래 `~/.claude/projects` 트랜스크립트 등)가 훨씬 많다 — 추측으로 만든
 * allowlist 는 운영에서만 모든 채팅을 조용히 깨뜨린다. 대신 차단은 opencode 의
 * {@link HARD_DENIED} 를 그대로 재사용하고, Claude Code 가 인증 경로로 읽는 **이름군 전체**를
 * 접두사로 막아 "이름 하나 누락" 사고의 폭을 줄인다.
 *
 * <p><b>`HOME`/`CLAUDE_CONFIG_DIR` 은 건드리지 않는다.</b> 옮기면 `~/.claude/projects` 의
 * 트랜스크립트와 `--resume` 이 깨진다. 그 대가로 macOS 키체인·`~/.claude` 로그인은 env 로 가릴
 * 수 없다 — 그래서 자격증명이 없으면 **spawn 하기 전에** {@link MissingAiCredentialError} 로 실패한다
 * (자식을 띄우면 그 로그인으로 조용히 인증될 수 있다).
 *
 * <p><b>Agent SDK 도 이 객체를 그대로 쓴다.</b> SDK(sdk.mjs)는 `options.env` 가 주어지면
 * `process.env` 와 병합하지 않고 그 객체를 자식 env 로 그대로 넘긴다(미지정 시에만
 * `{...process.env}`). 따라서 여기서 지운 이름은 실제로 자식에 도달하지 않는다.
 */
import { HARD_DENIED } from './opencode-child-env.js';
// 자격증명 없음 오류는 공급자 오류 정책(ai-auth-failure.ts)이 소유한다 — 코드·안내 문구를 한 곳에서 관리한다.
import { MissingAiCredentialError } from './ai-auth-failure.js';

/**
 * Claude Code 가 인증·엔드포인트 전환에 쓰는 이름군. 정확한 이름은 {@link HARD_DENIED} 가 막고,
 * 여기는 접두사로 막는다 — 새 버전이 같은 이름군에 변수를 추가해도 자동으로 차단된다.
 *
 * - `ANTHROPIC_`: API 키·Bearer 토큰·BASE_URL·CUSTOM_HEADERS(임의 인증 헤더 주입 가능)·
 *   Vertex/Bedrock/Foundry 엔드포인트 전체.
 * - `CLAUDE_CODE_OAUTH_`: OAuth 토큰과 리프레시 토큰.
 * - `CLAUDE_CODE_USE_`: `CLAUDE_CODE_USE_BEDROCK`/`_VERTEX`/`_FOUNDRY` — 켜지면 요청 자격증명
 *   대신 클라우드 공급자 자격증명 체인으로 인증한다.
 * - `CLAUDE_CODE_SKIP_`: `CLAUDE_CODE_SKIP_BEDROCK_AUTH` 등 — 위 전환과 짝을 이루는 인증 우회.
 * - `AWS_`: AWS 자격증명 체인 전체(Bedrock 경로). HARD_DENIED 보다 넓게 막는다.
 */
const DENIED_PREFIXES: readonly string[] = [
  'ANTHROPIC_',
  'CLAUDE_CODE_OAUTH_',
  'CLAUDE_CODE_USE_',
  'CLAUDE_CODE_SKIP_',
  'AWS_',
];

/** 접두사로 잡히지 않는 클라우드 공급자 자격증명·전환 이름(Vertex 경로). */
const DENIED_EXACT_EXTRA: ReadonlySet<string> = new Set([
  'GOOGLE_APPLICATION_CREDENTIALS',
  'CLOUD_ML_REGION',
  // 중첩 세션 방지 — 부모가 Claude Code 안에서 실행 중이면 자식이 "중첩 세션"으로 거부된다.
  'CLAUDECODE',
  'CLAUDE_CODE_ENTRYPOINT',
  // 재시도 횟수는 호출부가 항상 명시한다 — 호스트 값이 새면 #711 의 3분 대기가 재발한다.
  'CLAUDE_CODE_MAX_RETRIES',
]);

/** 이 이름이 자식 env 에 ambient 값으로 들어가면 안 되는지 판정한다. */
export function isDeniedClaudeChildEnvName(name: string): boolean {
  if (HARD_DENIED.has(name) || DENIED_EXACT_EXTRA.has(name)) return true;
  return DENIED_PREFIXES.some((prefix) => name.startsWith(prefix));
}

/**
 * 채팅·분류·completion 자식의 API 재시도 횟수 (#711).
 *
 * <p>Claude Code 기본값은 10회이고 cli.js 의 재시도 판정은 401 도 재시도 대상에 넣는다
 * (`if(A.status===401)return ...,!0`). 잘못된 키 하나로 지수 백오프가 약 160초 쌓여, 사용자는
 * 3분 가까이 기다린 뒤에야 실패를 본다. 0 으로 두면 429(요청 한도)·5xx(일시 장애)까지 즉시
 * 실패해 정상 사용 중에도 간헐 오류가 늘어난다 — 그래서 일시 장애는 짧게 흡수하고 인증 실패는
 * 수 초 안에 드러나는 절충값 2 를 쓴다. 검증 라우트는 사용자가 기다리는 단발 확인이라 0 을 쓴다.
 * cli.js 는 `if(process.env.CLAUDE_CODE_MAX_RETRIES)` 로 읽으므로 문자열 '0' 도 유효하다.
 */
export const CHAT_MAX_RETRIES = '2';
/** 검증 라우트(/api-key/verify, /cli-auth/verify)의 재시도 횟수 — 재시도 없이 즉시 판정한다. */
export const VERIFY_MAX_RETRIES = '0';

/** 요청 자격증명. OAuth 토큰이 API 키보다 우선한다(기존 규칙 유지). */
export interface ClaudeRequestCredentials {
  apiKey?: string;
  oauthToken?: string;
}

/**
 * 요청 자격증명 중 실제로 쓸 하나를 고른다. 공백 문자열은 "없음"으로 취급한다(프록시 쪽
 * missingCredential 판정과 같은 기준). 없으면 null — ambient 값으로 메우지 않는다.
 */
export function resolveClaudeCredential(
  credentials: ClaudeRequestCredentials | undefined,
): { kind: 'oauthToken' | 'apiKey'; value: string } | null {
  if (credentials?.oauthToken?.trim()) return { kind: 'oauthToken', value: credentials.oauthToken };
  if (credentials?.apiKey?.trim()) return { kind: 'apiKey', value: credentials.apiKey };
  return null;
}

/**
 * Claude Code 자식 env 를 만든다.
 *
 * 1. `parentEnv` 를 복사하되 {@link isDeniedClaudeChildEnvName} 에 걸리는 이름은 전부 버린다.
 * 2. 요청 자격증명 **하나만** 설정한다(OAuth 우선). 없으면 {@link MissingAiCredentialError}.
 * 3. `CLAUDE_CODE_MAX_RETRIES` 를 호출부 값으로 설정한다(#711).
 *
 * `parentEnv` 는 변경하지 않는다 — 동시 요청끼리 자격증명이 섞이지 않게 항상 새 객체를 돌려준다.
 * 호출부가 쓰는 동작 플래그(ENABLE_TOOL_SEARCH 등)는 반환값에 덧붙인다.
 */
export function buildClaudeChildEnv(
  parentEnv: NodeJS.ProcessEnv,
  credentials: ClaudeRequestCredentials | undefined,
  maxRetries: string = CHAT_MAX_RETRIES,
): NodeJS.ProcessEnv {
  const credential = resolveClaudeCredential(credentials);
  if (!credential) throw new MissingAiCredentialError();

  const env: NodeJS.ProcessEnv = {};
  for (const [name, value] of Object.entries(parentEnv)) {
    if (value === undefined || isDeniedClaudeChildEnvName(name)) continue;
    env[name] = value;
  }
  if (credential.kind === 'oauthToken') env.CLAUDE_CODE_OAUTH_TOKEN = credential.value;
  else env.ANTHROPIC_API_KEY = credential.value;
  env.CLAUDE_CODE_MAX_RETRIES = maxRetries;
  return env;
}
