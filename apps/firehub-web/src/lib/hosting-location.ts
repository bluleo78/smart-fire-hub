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
