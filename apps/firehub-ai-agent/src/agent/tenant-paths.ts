/**
 * ai-agent 가 디스크에 남기는 모든 산출물의 **테넌트 스코프 경로 단일 파생 지점**.
 *
 * <p>왜 한 곳인가: 이 서비스는 워크스페이스·트랜스크립트·첨부 세 종류를 각각 다른 모듈에서
 * `join(homedir(), '.firehub', ...)` 로 직접 조립하고 있었고, 그중 어느 것도 테넌트를 담지
 * 않았다. 파생을 흩어 두면 한 곳만 고쳐도 나머지가 조용히 전역 경로에 남는다. firehub-api 의
 * `DataSchema.current()` 가 스키마명 파생을 한 곳으로 모은 것과 같은 이유다.
 *
 * <p><b>테넌트 1 도 예외가 아니다.</b> API 쪽은 기존 데이터에 DDL 을 걸지 않으려고 테넌트 1 의
 * 물리 스키마를 `data` 로 남겼지만(신규 테넌트만 `data_t{id}`), 여기서 같은 예외를 두면 "테넌트
 * 1 이면 전역 경로" 라는 분기가 파일 경로에도 복제된다. 파일은 워크스페이스가 재생성 가능하고
 * 트랜스크립트는 지연 이관으로 보존할 수 있어 DDL 같은 비용이 없으므로, **모든 테넌트가
 * `t{id}` 세그먼트를 갖는다**. 스키마명 이중화(런북 §6-9)를 파일 경로로 번지지 않게 하는 선택이다.
 */
import { homedir, tmpdir } from 'os';
import { join } from 'path';

/** `.firehub` 루트. 종류별 디렉터리는 모두 이 아래에 테넌트 세그먼트를 끼고 놓인다. */
function firehubRoot(): string {
  return join(homedir(), '.firehub');
}

/**
 * 산출물 종류(카테고리)의 루트. 테넌트 경로와 레거시 경로가 **같은 카테고리 문자열**을
 * 공유하도록 한 곳으로 모은다 — 따로 적어 두면 카테고리 이름을 바꿀 때 한쪽만 고쳐도
 * 컴파일러가 못 잡고(디스크 I/O 라) 레거시 폴백이 조용히 엉뚱한 디렉터리를 보게 된다.
 */
function categoryRoot(category: string): string {
  return join(firehubRoot(), category);
}

/**
 * 경로 세그먼트로 쓸 수 있는 테넌트인지 판정한다. **경로 판정의 유일한 권위**다.
 *
 * <p>라우트의 400 가드가 이 술어를 재사용해야 한다 — 라우트가 더 느슨하면 나쁜 값이 통과해
 * {@link tenantSegment} 에서 나중에 터지고(챗은 SSE 헤더가 나간 뒤라 400 이 불가능하다),
 * 더 엄격하면 멀쩡한 요청을 막는다. 술어를 복제하고 주석으로 "같은 강도로 맞춰라" 라고 적는
 * 방식은 이미 한 번 어긋났다(코드리뷰 지적).
 *
 * <p>타입 가드로 둔 덕에 호출부는 `typeof` 를 따로 쓰지 않아도 `number` 로 좁혀진다.
 */
export function isValidTenantId(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

/**
 * 경로 세그먼트·표식 파일명으로 쓸 수 있는 세션 id 인지 판정한다.
 *
 * <p>여기 두는 이유: 이 규칙은 **경로 조립 입력 검증**(경로 이탈 `../` 차단)이라 경로 파생과
 * 같은 경계에 있다. 예전에는 `agent-cli.ts` 와 `session-owner.ts` 가 같은 정규식을 각자
 * 정의했는데, 보안 경계 값이 두 벌이면 한쪽만 완화됐을 때 두 모듈의 "안전한 id" 기준이 갈린다.
 */
export function isSafeSessionId(sessionId: string): boolean {
  return /^[a-zA-Z0-9_-]+$/.test(sessionId);
}

/**
 * 경로에 넣을 테넌트 세그먼트를 만든다.
 *
 * <p><b>fail-closed</b>: 테넌트가 없거나 정수 양수가 아니면 전역 경로로 폴백하지 않고 던진다.
 * 폴백을 두면 API 가 `tenantId` 전달을 빠뜨린 순간 모든 테넌트가 조용히 한 디렉터리를 공유하게
 * 되는데, 그건 이 모듈이 막으려는 상태 그 자체다 — 조용한 공유보다 시끄러운 실패가 낫다.
 */
export function tenantSegment(tenantId: unknown): string {
  if (!isValidTenantId(tenantId)) {
    throw new Error(
      `테넌트가 없어 경로를 만들 수 없다(전역 경로 폴백 금지): tenantId=${String(tenantId)}`,
    );
  }
  return `t${tenantId}`;
}

/** claude CLI 의 cwd 로 쓰이는 사용자 작업 디렉터리. */
export function workspaceDir(tenantId: number, userId: number): string {
  return join(categoryRoot('workspaces'), tenantSegment(tenantId), String(userId));
}

/** OpenCode 경로 전용 작업 디렉터리(CLI 와 별도 트리로 유지된 기존 관례를 그대로 둔다). */
export function opencodeWorkspaceDir(tenantId: number, userId: number): string {
  return join(categoryRoot('workspaces-opencode'), tenantSegment(tenantId), String(userId));
}

/** CLI 트랜스크립트(JSON) 디렉터리. */
export function transcriptDir(tenantId: number): string {
  return join(legacyTranscriptDir(), tenantSegment(tenantId));
}

/** 세션 첨부 메타데이터 사이드카 디렉터리. */
export function attachmentsDir(tenantId: number): string {
  return join(legacyAttachmentsDir(), tenantSegment(tenantId));
}

/**
 * 테넌트 세그먼트가 없던 시절의 트랜스크립트 경로.
 *
 * <p><b>읽기 + 지연 이관 전용이다 — 쓰기에 쓰면 안 된다.</b> 이 경로에 남아 있는 과거 세션을
 * 버리지 않기 위해서만 존재한다. 이관이 안전한 이유는 `/agent/history` 에 도달하기 전에
 * firehub-api 가 `verifySessionOwnership` + `ai_session` RLS 로 이미 소유권을 검증하기 때문이다 —
 * 그 세션에 도달할 수 있는 호출자는 그 세션을 소유한 테넌트의 사용자뿐이므로, 요청 테넌트를
 * 그 파일의 귀속으로 취급해도 된다.
 */
export function legacyTranscriptDir(): string {
  return categoryRoot('transcripts');
}

/** 테넌트 세그먼트가 없던 시절의 첨부 사이드카 디렉터리. 읽기 폴백 전용. */
export function legacyAttachmentsDir(): string {
  return categoryRoot('session-attachments');
}

/**
 * SDK 경로가 첨부 파일 **내용**을 내려받는 임시 디렉터리.
 *
 * <p>왜 여기에 두는가: CLI 경로는 같은 파일을 `workspaceDir()` 아래(영속 — 에이전트가 턴을
 * 넘겨 Read 해야 한다)에 두는데, SDK 경로는 요청마다 지우는 `tmpdir` 을 쓴다. 위치가 다른 것은
 * 의도된 차이지만, **한쪽만 테넌트를 담으면** 같은 논리적 산출물이 한 경로에서는 테넌트 단위로
 * 열거·삭제되고 다른 경로에서는 안 되는 비대칭이 남는다. 파생을 이 모듈로 모아 그 비대칭을 없앤다.
 *
 * @param stamp 호출자가 만드는 요청 구분자(동일 사용자의 동시 요청 충돌 방지)
 */
export function sdkChatFilesDir(tenantId: number, userId: number, stamp: string | number): string {
  return join(tmpdir(), 'firehub-chat-files', tenantSegment(tenantId), `${userId}-${stamp}`);
}

/**
 * 프로액티브 리포트(HTML + 요약)를 에이전트가 써 넣는 임시 디렉터리.
 *
 * <p>{@link sdkChatFilesDir} 와 같은 이유로 여기 모았다 — 같은 `tmpdir` 안에서 한 종류만
 * 테넌트를 담으면, 도구(Glob/Read)를 가진 에이전트가 남은 한 종류를 통째로 열거할 수 있다.
 */
export function proactiveReportDir(
  tenantId: number,
  userId: number,
  stamp: string | number,
): string {
  return join(tmpdir(), 'proactive-report', tenantSegment(tenantId), `${userId}-${stamp}`);
}
