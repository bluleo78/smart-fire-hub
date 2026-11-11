import type { AxiosInstance } from 'axios';

/**
 * 플랫폼 DB 기능 상태(WD-495). GET /platform-db/capabilities 응답과 1:1.
 * Tibero 설치본은 벡터(pgvector)·트라이그램(pg_trgm) 검색이 둘 다 꺼져 있다 — 서버는 검색을 건너뛰고 200 빈 목록을 준다.
 */
export interface PlatformDbCapabilities {
  vectorSearch: boolean;
  trigramSearch: boolean;
}

/** 검색 모드 — 문서·데이터셋 검색 API 의 mode 값. */
export type SearchMode = 'SEMANTIC' | 'KEYWORD' | 'HYBRID';

/** 조회 실패·응답 이상 시 쓰는 값 — PG 기본(모두 켜짐)이라 기존 동작이 그대로 유지된다. */
const ALL_ENABLED: Readonly<PlatformDbCapabilities> = Object.freeze({ vectorSearch: true, trigramSearch: true });

/**
 * 조회 성공을 기억하는 시간. 설치본의 DB 종류는 실행 중에 바뀌지 않지만, api 만 다른 DB 로 다시 띄운 경우 등
 * ai-agent 재시작 없이도 언젠가는 새 값을 따라가도록 영구 캐시 대신 유한한 TTL 을 둔다.
 */
export const CAPABILITIES_SUCCESS_TTL_MS = 5 * 60_000;

/** 조회 실패를 기억하는 시간. 실패를 오래 붙들면 일시 장애 뒤에도 계속 켜짐으로 보므로 짧게 두고 다시 묻는다. */
export const CAPABILITIES_FAILURE_RETRY_MS = 30_000;

/** 둘 다 꺼진 설치본에서 검색 도구가 돌려주는 안내문(오류 아님 — 모델이 같은 도구를 되풀이하지 않게). */
export const SEARCH_DISABLED_NOTICE = '이 설치본에서는 검색 기능(벡터·키워드)이 꺼져 있어 쓸 수 없습니다.';

/**
 * 프로세스 캐시. 기능 상태는 설치본(플랫폼 DB 종류) 단위라 사용자·테넌트와 무관하다 —
 * FireHubApiClient 는 요청·사용자마다 새로 만들어지므로 인스턴스가 아니라 모듈에 둔다.
 * 성공은 CAPABILITIES_SUCCESS_TTL_MS(5분), 실패는 CAPABILITIES_FAILURE_RETRY_MS(30초) 동안만 기억한다.
 */
let cached: { value: PlatformDbCapabilities; expiresAt: number } | null = null;
/** 동시에 여러 도구가 물어도 한 번만 조회하도록 진행 중인 요청을 공유한다. */
let inFlight: Promise<PlatformDbCapabilities> | null = null;

/** 테스트 전용 — 모듈 캐시를 비운다(케이스 사이 누수 방지). */
export function resetPlatformDbCapabilitiesCache(): void {
  cached = null;
  inFlight = null;
}

/**
 * 응답을 정규화한다. 명시적으로 false 인 기능만 꺼짐으로 본다 —
 * 값이 빠졌거나 이상하면 PG 기본(켜짐)으로 간주해 기능이 잘못 숨겨지지 않게 한다.
 */
export function normalizeCapabilities(raw: unknown): PlatformDbCapabilities {
  const r = (raw ?? {}) as Partial<Record<keyof PlatformDbCapabilities, unknown>>;
  return { vectorSearch: r.vectorSearch !== false, trigramSearch: r.trigramSearch !== false };
}

/** 플랫폼 DB 기능 상태 API. */
export function createPlatformDbApi(client: AxiosInstance) {
  return {
    /**
     * 기능 상태를 돌려준다(프로세스 캐시). 절대 던지지 않는다 — 실패하면 모두 켜짐으로 간주하고 짧게만 기억한다.
     */
    async getPlatformDbCapabilities(): Promise<PlatformDbCapabilities> {
      if (cached && Date.now() < cached.expiresAt) return cached.value;
      if (inFlight) return inFlight;
      inFlight = (async () => {
        try {
          const response = await client.get('/platform-db/capabilities');
          const value = normalizeCapabilities(response.data);
          cached = { value, expiresAt: Date.now() + CAPABILITIES_SUCCESS_TTL_MS };
          return value;
        } catch (error) {
          console.error(
            `[MCP API] platform-db capabilities 조회 실패 — 검색 기능을 켜짐으로 간주한다: ${
              error instanceof Error ? error.message : String(error)
            }`,
          );
          cached = { value: ALL_ENABLED, expiresAt: Date.now() + CAPABILITIES_FAILURE_RETRY_MS };
          return ALL_ENABLED;
        } finally {
          inFlight = null;
        }
      })();
      return inFlight;
    },
  };
}

/** 벡터·트라이그램 검색이 둘 다 꺼졌는가 — 이때 검색 도구는 API 를 부르지 않고 안내만 한다. */
export function isSearchFullyDisabled(caps: PlatformDbCapabilities): boolean {
  // 명시적 false 만 꺼짐 — 목 객체 등 값이 빠진 경우는 켜짐(PG 기본)으로 둔다.
  return caps.vectorSearch === false && caps.trigramSearch === false;
}

/**
 * 요청 모드를 켜진 기능으로 쓸 수 있는 모드로 바꾼다(부분 꺼짐). 웹 availableSearchModes(usePlatformDbCapabilities.ts)와 같은 규칙이다.
 * - 모드 생략은 서버 기본 HYBRID 와 같다(HYBRID 는 벡터·트라이그램 둘 다 쓴다).
 * - 벡터 꺼짐 → KEYWORD, 트라이그램 꺼짐 → SEMANTIC.
 * 바꾸지 않아도 되면 notice 가 없고 mode 는 요청 그대로다. 둘 다 꺼진 경우는 호출 전에 걸러야 한다.
 */
export function resolveSearchMode(
  requested: SearchMode | undefined,
  caps: PlatformDbCapabilities,
): { mode: SearchMode | undefined; notice?: string } {
  const effective = requested ?? 'HYBRID';
  const needsVector = effective !== 'KEYWORD';
  const needsTrigram = effective !== 'SEMANTIC';
  if (needsVector && caps.vectorSearch === false) {
    return {
      mode: 'KEYWORD',
      notice: `이 설치본에서는 벡터(의미) 검색이 꺼져 있어 ${effective} 대신 KEYWORD(키워드) 검색으로 찾았습니다.`,
    };
  }
  if (needsTrigram && caps.trigramSearch === false) {
    return {
      mode: 'SEMANTIC',
      notice: `이 설치본에서는 키워드 검색이 꺼져 있어 ${effective} 대신 SEMANTIC(의미) 검색으로 찾았습니다.`,
    };
  }
  return { mode: requested };
}

/**
 * 검색 도구 공통 가드(WD-495). 기능 상태를 읽어
 * - 둘 다 꺼졌으면 { disabled: true, notice } — 호출자는 검색 API 를 부르지 않고 안내문만 돌려준다.
 * - 아니면 { disabled: false, mode, notice? } — 켜진 기능으로 바꾼 모드로 검색하고, 바꿨다면 notice 를 덧붙인다.
 * getCaps 는 실패해도 던지지 않고 모두 켜짐을 주므로 여기서 다시 감싸지 않는다.
 */
export type SearchGuard =
  | { disabled: true; notice: string }
  | { disabled: false; mode: SearchMode | undefined; notice?: string };

export async function guardSearch(
  getCaps: () => Promise<PlatformDbCapabilities>,
  requested: SearchMode | undefined,
): Promise<SearchGuard> {
  const caps = await getCaps();
  if (isSearchFullyDisabled(caps)) return { disabled: true, notice: SEARCH_DISABLED_NOTICE };
  return { disabled: false, ...resolveSearchMode(requested, caps) };
}

/** MCP 도구 결과 모양(첫 블록 JSON 등 텍스트 블록 목록). */
interface TextToolResult {
  content: Array<{ type: 'text'; text: string }>;
}

/**
 * 도구 결과 끝에 안내 텍스트 블록을 덧붙인다(모드 전환 안내). 첫 블록(JSON)의 모양은 그대로 둔다.
 * notice 가 없으면 결과를 그대로 돌려준다.
 */
export function appendSearchNotice<T extends TextToolResult>(result: T, notice: string | undefined): T {
  if (!notice) return result;
  return { ...result, content: [...result.content, { type: 'text' as const, text: notice }] };
}

/** 검색이 꺼진 설치본에서 도구가 돌려주는 정상 결과(isError 아님 — 모델이 같은 도구를 되풀이하지 않게). */
export function searchDisabledResult(notice: string = SEARCH_DISABLED_NOTICE): TextToolResult {
  return { content: [{ type: 'text' as const, text: notice }] };
}

/**
 * 검색 도구 공통 래퍼: 가드 → 꺼짐이면 안내문만 반환 → 아니면 (전환된) 모드로 run 실행 → 모드를 바꿨다면 안내를 덧붙인다.
 */
export async function withSearchGuard<T extends TextToolResult>(
  getCaps: () => Promise<PlatformDbCapabilities>,
  requested: SearchMode | undefined,
  run: (mode: SearchMode | undefined) => Promise<T>,
): Promise<T | TextToolResult> {
  const guard = await guardSearch(getCaps, requested);
  if (guard.disabled) return searchDisabledResult(guard.notice);
  return appendSearchNotice(await run(guard.mode), guard.notice);
}
