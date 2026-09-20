/**
 * opencode CLI 자식 프로세스에 넘길 환경변수를 **허용 목록(allowlist)** 으로 조립한다 (이슈 #696).
 *
 * <p><b>왜 allowlist 인가.</b> #693 은 여기를 denylist(알려진 자격증명 이름을 `delete`)로 막았다.
 * denylist 는 성질상 누락이 남는다 — 새 공급자 자격증명 관례가 생길 때마다 손으로 추가해야 하고,
 * 놓치면 플랫폼 계정으로 조용히 과금되는 사고가 새 이름으로 재발한다(`6b1c6383` 과 같은 모양).
 * 실제로 #693 진행 중에만 두 번(`ANTHROPIC_AUTH_TOKEN`/`OPENAI_API_KEY`, 그리고 AWS 자격증명
 * 체인 전체) 뒤늦게 추가해야 했다. allowlist 는 모르는 이름이 기본적으로 차단된다.
 *
 * <p><b>왜 그때 하지 않았나.</b> 잘못된 allowlist 는 <b>모든 opencode 채팅을 조용히 깨뜨린다</b>.
 * 그래서 실제 opencode 바이너리로 필요한 키를 확정하기 전에는 뒤집지 않았다.
 *
 * <p><b>실측(2026-09-21, opencode v1.18.31, macOS).</b> OpenAI 호환 스트리밍 목 서버를 상대로
 * `env -i` 에서 후보 집합을 깎아내며 `opencode run ... --format json` 을 돌렸다. 결과:
 * `PATH` 하나만 있어도 정상 종료(exit 0, 약 2초)했고, `HOME`/`TMPDIR` 유무는 결과를 바꾸지
 * 않았다. 즉 <b>실측으로 확인된 필수 키는 `PATH` 뿐</b>이다.
 *
 * <p><b>그래서 아래 목록은 실측보다 넓다 — 의도적이다.</b> 내 노트북에 없는 것이 운영 파드에는
 * 있다. 프록시(`HTTP_PROXY` 등), 사내 CA(`NODE_EXTRA_CA_CERTS`/`SSL_CERT_FILE`), 사설 npm
 * 레지스트리(`npm_config_*`)가 그렇고, 이것들은 opencode 가 최초 요청에 플러그인 npm 트리를
 * 내려받을 때 실제로 필요하다. `HOME` 은 `--session` 재개가 의존하는
 * `$XDG_DATA_HOME/opencode`(기본 `$HOME/.local/share`)의 기준점이라 설계상 반드시 남긴다.
 * "측정으로 필요 없다고 나왔으니 뺀다"를 이 항목들에 적용하면 운영에서만 깨진다.
 *
 * <p><b>남는 위험.</b> 로컬 실행은 파드가 아니다. 운영 이미지에만 있는 의존(예: 특수한 로캘,
 * 사내 툴체인 변수)이 있으면 이 목록이 그것을 끊을 수 있다. 그래서 운영자가 재배포 없이 열 수
 * 있는 탈출구({@link EXTRA_ENV_VAR})를 두되, 그 탈출구로도 {@link HARD_DENIED} 의 이름은 절대
 * 통과하지 못하게 한다 — 탈출구가 이 가드를 무력화하는 문이 되면 allowlist 로 뒤집은 의미가
 * 없어진다.
 */

/** 파서가 읽는 탈출구 환경변수 이름. 쉼표로 구분한 **변수 이름** 목록이다(값이 아니다). */
export const EXTRA_ENV_VAR = 'OPENCODE_CHILD_ENV_EXTRA';

/**
 * 어떤 경로로도 자식에게 도달해선 안 되는 이름 — allowlist 보다도, 탈출구보다도 우선한다.
 *
 * <p>#693 의 denylist 를 그대로 옮겨 왔다. allowlist 가 이미 이것들을 통과시키지 않으므로
 * 이론적으로는 중복이지만, 둘 다 두는 이유가 있다: 누군가 allowlist 에 `ANTHROPIC_*` 같은
 * 패턴을 "편의상" 추가하거나 운영자가 탈출구에 이 이름을 적는 순간, 이 집합만이 사고를 막는다.
 * 방어선이 하나뿐이면 그 하나가 실수로 넓어졌을 때 아무것도 남지 않는다.
 */
export const HARD_DENIED: ReadonlySet<string> = new Set([
  // ai-agent 자신의 내부 인증 토큰 — 채팅에서 도달 가능한 유출 경로가 되면 안 된다.
  'INTERNAL_SERVICE_TOKEN',
  // Anthropic ambient 자격증명 (6b1c6383 의 원인).
  'ANTHROPIC_API_KEY',
  'ANTHROPIC_AUTH_TOKEN',
  'ANTHROPIC_BASE_URL',
  'CLAUDE_CODE_OAUTH_TOKEN',
  // opencode 자체의 전역 설정 주입 경로 — 테넌트 설정을 조용히 이길 수 있다.
  'OPENCODE_CONFIG',
  'OPENCODE_PERMISSION',
  // 다른 공급자의 ambient 자격증명.
  'OPENAI_API_KEY',
  'BEDROCK_API_KEY',
  // AWS 자격증명 체인 전체 — 정적 키만 막는 것은 무의미하다(IRSA/ECS/파일 경로가 각각 살아 있다).
  'AWS_BEARER_TOKEN_BEDROCK',
  'AWS_ACCESS_KEY_ID',
  'AWS_SECRET_ACCESS_KEY',
  'AWS_SESSION_TOKEN',
  'AWS_PROFILE',
  'AWS_WEB_IDENTITY_TOKEN_FILE',
  'AWS_ROLE_ARN',
  'AWS_CONTAINER_CREDENTIALS_RELATIVE_URI',
  'AWS_CONTAINER_CREDENTIALS_FULL_URI',
  'AWS_SHARED_CREDENTIALS_FILE',
  'AWS_CONFIG_FILE',
]);

/**
 * 이름이 정확히 일치할 때만 통과하는 목록.
 *
 * <p>`NODE_OPTIONS` 는 일부러 넣지 않았다 — `--require` 로 임의 코드를 자식에 주입할 수 있어
 * 자격증명 격리와 정면으로 어긋나고, opencode 동작에 필요하지도 않다(실측).
 * `XDG_CONFIG_HOME` 도 없다 — 그건 호출부가 프로세스 전용 임시 디렉터리로 **덮어쓴다**.
 */
const ALLOWED_EXACT: ReadonlySet<string> = new Set([
  // 프로세스 기본.
  'PATH', // 실측상 유일한 필수 키 (node/npm 을 찾는다)
  'HOME', // --session 재개가 의존하는 XDG_DATA_HOME 의 기준점
  'USER',
  'LOGNAME',
  'SHELL',
  'TMPDIR',
  'TMP',
  'TEMP',
  // 로캘·터미널 — 없어도 돌지만, 있으면 로그·출력 인코딩이 배포와 일치한다.
  'LANG',
  'LANGUAGE',
  'TZ',
  'TERM',
  // 사내 프록시 (파드에만 있고 로컬엔 없다 — 없으면 npm 부트스트랩이 막힌다).
  'HTTP_PROXY',
  'HTTPS_PROXY',
  'NO_PROXY',
  'ALL_PROXY',
  'http_proxy',
  'https_proxy',
  'no_proxy',
  'all_proxy',
  // 사내 CA (같은 이유 — 없으면 TLS 검증이 실패한다).
  'NODE_EXTRA_CA_CERTS',
  'SSL_CERT_FILE',
  'SSL_CERT_DIR',
  'CURL_CA_BUNDLE',
  // opencode 가 쓰는 나머지 XDG 경로. 설정(CONFIG)만 격리하고 데이터·캐시는 살린다
  // (세션 재개가 데이터 디렉터리에 의존한다 — 이슈 #697 참고).
  'XDG_DATA_HOME',
  'XDG_CACHE_HOME',
  'XDG_STATE_HOME',
]);

/**
 * 접두사가 일치하면 통과하는 규칙.
 *
 * <p>`npm_config_` 는 사설 레지스트리·프록시·CA 설정이 전부 이 형태로 들어오기 때문이다
 * (deploy.md 가 `npm_config_registry` 를 명시적으로 권한다). `LC_` 는 로캘 변수군이다.
 *
 * <p>접두사 규칙은 위험하다 — 넓히면 모르는 이름이 딸려 들어온다. 그래서 두 개뿐이고,
 * {@link HARD_DENIED} 가 그 위에서 한 번 더 걸러낸다.
 */
const ALLOWED_PREFIXES: readonly string[] = ['npm_config_', 'LC_'];

function isAllowedName(name: string): boolean {
  if (HARD_DENIED.has(name)) return false;
  if (ALLOWED_EXACT.has(name)) return true;
  return ALLOWED_PREFIXES.some((prefix) => name.startsWith(prefix));
}

/**
 * 탈출구가 지정한 추가 이름들. 존재하지 않는 변수나 {@link HARD_DENIED} 의 이름은 조용히 무시한다
 * — 무시된 사실은 호출부가 로그로 남긴다.
 */
export function parseExtraEnvNames(raw: string | undefined): {
  allowed: string[];
  rejected: string[];
} {
  const allowed: string[] = [];
  const rejected: string[] = [];
  for (const piece of (raw ?? '').split(',')) {
    const name = piece.trim();
    if (!name) continue;
    if (HARD_DENIED.has(name)) rejected.push(name);
    else allowed.push(name);
  }
  return { allowed, rejected };
}

/**
 * 자식 env 를 **빈 객체에서 조립한다**. "부모 env 를 복사한 뒤 지운다"가 아니다 — 복사본에서
 * 지우는 방식은 지울 이름을 아는 만큼만 안전하고, 그게 바로 denylist 의 한계였다.
 *
 * @param parentEnv 보통 `process.env`.
 * @returns allowlist 를 통과한 키만 담긴 새 객체. 호출부가 `OPENCODE_CONFIG_CONTENT` 와
 *     `XDG_CONFIG_HOME` 을 여기에 덧씌운다.
 */
export function buildOpenCodeChildEnv(parentEnv: NodeJS.ProcessEnv): {
  env: NodeJS.ProcessEnv;
  rejectedExtras: string[];
} {
  const { allowed: extras, rejected: rejectedExtras } = parseExtraEnvNames(parentEnv[EXTRA_ENV_VAR]);
  const extraNames = new Set(extras);

  const env: NodeJS.ProcessEnv = {};
  for (const [name, value] of Object.entries(parentEnv)) {
    if (value === undefined) continue;
    // HARD_DENIED 는 탈출구보다 우선한다 — 순서가 이 한 줄에 담겨 있다.
    if (HARD_DENIED.has(name)) continue;
    if (isAllowedName(name) || extraNames.has(name)) {
      env[name] = value;
    }
  }
  return { env, rejectedExtras };
}
