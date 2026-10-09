/** 공급자 호스팅 위치(S3 §3). api 의 ProviderHosting 과 같은 값. */
export type HostingLocation = 'EXTERNAL' | 'SELF_HOSTED';

/** 자체 호스팅을 선언할 수 없는 자격증명 유형 — Claude 계열은 항상 외부다(api 가 SELF_HOSTED 선언을 400 으로 거부). */
export const EXTERNAL_ONLY_AGENT_TYPES: ReadonlySet<string> = new Set(['sdk', 'cli', 'cli-api']);

/** 자체 호스팅 선언에 필요한 권한(스펙 §2.6). api `HostingDeclarationPolicy` 가 보는 것과 같은 코드다. */
export const HOSTING_DECLARE_PERMISSION = 'security:settings';

/**
 * 저장·화면 값 → 호스팅 위치. 모르는 값·빈 값은 외부로 본다 — api 의 "없으면 외부" 규칙과 같고, 실수의 방향이
 * "덜 허용"이 되게 한다(fail-safe).
 */
export function normalizeHosting(raw: string | null | undefined): HostingLocation {
  return raw === 'SELF_HOSTED' ? 'SELF_HOSTED' : 'EXTERNAL';
}

/** 전송 대상 비교용 정규화 — api `UrlUtils.normalizeBaseUrl(raw.trim())` 과 같게 앞뒤 공백과 끝 슬래시 하나를 뗀다. */
function normalizeTarget(raw: string | null | undefined): string {
  return (raw ?? '').trim().replace(/\/$/, '');
}

/**
 * 두 전송 대상 값(공급자 id·기본 URL)이 같은 목적지인가. 화면이 서버와 같은 규칙으로 "전송 대상이 바뀌었는가"를
 * 판정해야 자체 호스팅 선언 강등(대상이 바뀌면 선언은 유지되지 않는다)을 저장 전에 미리 보여 줄 수 있다.
 */
export function isSameTransportTarget(a: string | null | undefined, b: string | null | undefined): boolean {
  return normalizeTarget(a) === normalizeTarget(b);
}

const PRIVATE_V4 = [/^10\./, /^192\.168\./, /^172\.(1[6-9]|2\d|3[01])\./, /^127\./];

/**
 * 엔드포인트가 공용 인터넷 주소로 보이는가 — 자체 호스팅 선언 확인 창의 경고용 휴리스틱(스펙 §7.6 "공용 엔드포인트 경고").
 * 사설 IPv4·localhost·점 없는 호스트(컨테이너 이름)·.internal/.local/.lan/.corp 는 사설로 본다. 판정이 아니라 경고라
 * 틀려도 저장을 막지 않는다(판정 책임은 관리자의 선언).
 */
export function isLikelyPublicEndpoint(url: string): boolean {
  let host: string;
  try {
    host = new URL(url).hostname.toLowerCase();
  } catch {
    return false;
  }
  if (host === 'localhost' || !host.includes('.')) return false;
  if (PRIVATE_V4.some((re) => re.test(host))) return false;
  if (/\.(internal|local|lan|corp)$/.test(host)) return false;
  return true;
}

/**
 * 공용 호스팅이 <b>확실한</b> AI SaaS 호스트(스펙 §7.6 — 자체 호스팅 오선언 방지). 여기 있는 주소를 자체 호스팅으로 선언하면 api 가
 * 400 으로 거부한다(api `KnownPublicAiHosts.HOSTS`). 두 언어에 한 곳으로 정의할 수 없어, 양쪽 테스트(hosting-location.test.ts ·
 * KnownPublicAiHostsTest)가 같은 집합·같은 사례 표로 일치를 고정한다 — 한쪽만 고치면 그 표가 깨진다.
 */
export const KNOWN_PUBLIC_AI_HOSTS: ReadonlySet<string> = new Set([
  'api.openai.com',
  'api.anthropic.com',
  'generativelanguage.googleapis.com',
  'api.mistral.ai',
  'api.cohere.com',
  'api.cohere.ai',
  'api.groq.com',
  'openrouter.ai',
  'api.together.xyz',
  'api.deepseek.com',
  'api.voyageai.com',
  'api.fireworks.ai',
  'api.perplexity.ai',
  'api.x.ai',
]);

/**
 * URL 의 호스트가 확실한 공용 AI SaaS 인가 — api `KnownPublicAiHosts.isKnownPublic` 과 같은 규칙. 호스트 정확 일치(소문자·끝 점 제거)이고
 * 접미사 와일드카드는 쓰지 않는다(고객 소유 하위 도메인을 공용으로 오판하지 않게). 파싱 실패는 false(형식 오류는 URL 검증이 따로 막는다).
 */
export function isKnownPublicAiHost(url: string): boolean {
  let host: string;
  try {
    host = new URL(url.trim()).hostname.toLowerCase();
  } catch {
    return false;
  }
  // 끝 점(FQDN 표기 "api.openai.com.")을 떼야 같은 호스트가 목록을 우회하지 못한다 — api 와 같은 처리.
  host = host.replace(/\.+$/, '');
  return KNOWN_PUBLIC_AI_HOSTS.has(host);
}
