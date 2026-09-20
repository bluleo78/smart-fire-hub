import { isIPv4, isIPv6 } from 'node:net';
import { lookup } from 'node:dns/promises';

/**
 * OpenAI 호환 completion 호출(`openai-compat-completion-provider.ts`)의 SSRF 가드.
 *
 * <b>왜 필요한가(보안 리뷰 Fix2).</b> 자바 쪽 저장 시점 검증(`OpencodeProbeService`, 보안 리뷰
 * Fix1)이 스킴·포트·DNS 해석 결과의 사설 대역을 이미 막지만, 그 검증은 저장하는 순간의 DNS
 * 응답을 본다 — 실제 호출은 그 뒤에(분류 파이프라인이 돌 때마다) 별도로 DNS 를 다시 해석한다.
 * 그 사이에 DNS rebinding(검사 시점엔 공인 IP, 호출 시점엔 사설 IP)이 일어나면 저장 시점 검증은
 * 무력하다(`OpencodeProbeService` 클래스 javadoc "DNS TOCTOU", Ruling #23 이 이미 인정한 한계).
 * 그래서 이 completion 호출 자체가 매번 스스로 스킴·포트·주소를 다시 검사한다 — 저장 시점 검증이
 * 맞았다는 가정에 기대지 않는다.
 *
 * <b>다만 이 가드가 rebinding 창을 "닫지는" 못한다(재검토 N2).</b> 여기서 해석한 IP 로 접속하는
 * 게 아니라(가드는 호스트명만 검사하고 실제 연결은 `fetch` 가 스스로 DNS 를 <b>다시</b> 해석해
 * 맺는다) 검사 시점과 접속 시점 사이에 응답이 바뀌면 여전히 뚫린다. 저장 시점 검증만 있을 때의
 * "몇 시간~며칠"짜리 창을 "수백 ms"로 좁힌 것이지 제거한 것이 아니다 — 완전한 방어는 해석된 IP
 * 로 직접 접속하고 Host 헤더를 고정하는 방식(커스텀 agent/lookup)이며 이 파일은 그것을 하지
 * 않는다. 자바 `OpencodeProbeService.validateTarget` 의 javadoc 이 인정하는 것과 같은 한계다.
 *
 * <b>스킴·포트·차단 대역은 `OpencodeProbeService.validateTarget`(자바)와 같은 기준을 쓴다</b> —
 * https 강제, 허용 포트 443/8443(자바 `ALLOWED_PORTS` 와 같은 값), 루프백, RFC1918 사설 대역,
 * 링크로컬, CGNAT(100.64.0.0/10), IPv6 유니크로컬(fc00::/7), IPv4-매핑 IPv6(::ffff:a.b.c.d,
 * 매핑된 IPv4 로 재판정), 0.0.0.0/8. 같은 판정을 자바로 옮겨 재사용할 수는 없어(별개 런타임·언어)
 * 이 파일이 그 목록을 다시 구현한다 — 목록이 갈리면 이 주석과 자바 쪽 클래스 javadoc 을 함께
 * 갱신할 것.
 */

/**
 * 허용 포트. 자바 `OpencodeProbeService.ALLOWED_PORTS` 와 같은 값이어야 한다(한쪽만 넓히면
 * "저장은 됐는데 호출은 막힌다" 또는 그 반대가 생긴다). 임의 포트를 허용하면 이 호출이 내부망
 * 포트 스캐너(6379=Redis, 5432=Postgres, 22=SSH 배너)가 된다 — 저장 시점 검증이 생기기(Fix1)
 * 전에 저장된 행에는 임의 포트 baseURL 이 그대로 남아 있을 수 있어, 런타임에서도 다시 막는다.
 */
const ALLOWED_PORTS = new Set([443, 8443]);

/** 스킴이 https 가 아니면 던진다. 상세 사유는 노출하지 않는다(호출부가 캐치해 일반화된 메시지로 감싼다). */
class UnsafeTargetError extends Error {}

/**
 * baseUrl 이 안전한 completion 대상인지 검사한다. 통과하면 아무 것도 반환하지 않고, 막혀야 하면
 * 던진다.
 *
 * <p>주소가 여러 개로 해석되면(A/AAAA 레코드가 여러 개) <b>전부</b> 검사한다 — 하나라도 차단
 * 대역이면 전체를 거부한다(공격자가 하나는 공인 IP, 하나는 사설 IP를 내려주는 방식으로 "가끔
 * 통과"를 노릴 수 있다).
 *
 * @throws UnsafeTargetError baseUrl 형식이 잘못됐거나, 스킴이 https 가 아니거나, 포트가
 *     허용목록(443/8443) 밖이거나, DNS 해석이 실패했거나, 해석된 주소 중 하나라도 차단 대역일
 *     때. 메시지에는 호스트/IP 를 담지 않는다 —
 *     이 메시지가 호출자(분류 파이프라인)까지 전파될 수 있어, 내부 네트워크 토폴로지를 흘리지
 *     않기 위해서다(`OpencodeProbeService` 가 같은 이유로 해석된 IP 를 응답에 담지 않는 것과
 *     같은 근거).
 */
export async function assertSafeCompletionTarget(rawUrl: string): Promise<void> {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new UnsafeTargetError('[completion] baseUrl 형식이 올바르지 않습니다.');
  }

  if (url.protocol !== 'https:') {
    throw new UnsafeTargetError('[completion] https 만 허용됩니다.');
  }

  // URL.port 는 기본 포트(https=443)가 명시돼 있어도 빈 문자열이다 — 생략과 ':443' 을 같은
  // 443 으로 정규화한 뒤 허용목록과 대조한다(자바 쪽 `uri.getPort() == -1 ? 443` 과 같은 처리).
  const port = url.port === '' ? 443 : Number(url.port);
  if (!ALLOWED_PORTS.has(port)) {
    throw new UnsafeTargetError('[completion] 허용되지 않은 포트입니다.');
  }

  // URL.hostname 은 IPv6 리터럴을 대괄호로 감싸 돌려준다(예: "[::1]") — dns.lookup/주소 판정은
  // 대괄호 없는 형태를 기대하므로 벗긴다.
  const hostname = url.hostname.replace(/^\[/, '').replace(/\]$/, '');

  let addresses: string[];
  try {
    // verbatim(주소 재정렬 순서 제어)은 Node 22 부터 폐기(deprecated)됐고 이제 기본값이 항상
    // true 다 — 이 함수는 순서에 관심이 없고(모든 주소를 검사한다) 재정렬 여부와 무관하므로
    // 명시할 이유가 없다.
    const resolved = await lookup(hostname, { all: true });
    addresses = resolved.map((entry) => entry.address);
  } catch {
    throw new UnsafeTargetError('[completion] 호스트를 확인할 수 없습니다.');
  }

  for (const address of addresses) {
    if (isBlockedAddress(address)) {
      throw new UnsafeTargetError('[completion] 허용되지 않은 대상 주소입니다.');
    }
  }
}

function isBlockedAddress(address: string): boolean {
  if (isIPv4(address)) return isBlockedIPv4(address);
  if (!isIPv6(address)) {
    // 파싱 불가한 주소 형태는 판정할 수 없다 — 알 수 없으면 안전하게 차단한다(fail-closed).
    return true;
  }

  const value = ipv6ToBigInt(address);

  // ::ffff:0:0/96 — IPv4-매핑 IPv6. 상위 96비트를 오른쪽으로 밀어내면(>>32n) 정확히 0xffff 여야
  // 한다(상위 80비트가 0 이고, 그다음 16비트가 0xffff). 실제로 관측된 두 표기 모두 이 조건으로
  // 잡힌다 — 점십진 꼬리(`::ffff:10.0.0.5`, RFC5952 권장 표기)와 순수 16진 꼬리(`::ffff:a00:5`,
  // 이 실행 환경의 getaddrinfo/dns.lookup 이 실제로 돌려주는 정규화된 형태) 둘 다 같은 128비트
  // 값으로 파싱되므로 비트 연산 판정은 표기 차이에 영향받지 않는다.
  if (value >> 32n === 0xffffn) {
    return isBlockedIPv4(bigIntToIPv4(value & 0xffffffffn));
  }

  if (value === 1n) return true; // ::1 루프백
  if (value === 0n) return true; // :: 미지정 주소
  // fc00::/7 — IPv6 유니크로컬(사설 대역 대응). 상한(배타)은 다음 /7 경계인 fe00::.
  if (value >= FC00 && value < FE00) return true;
  // fe80::/10 — 링크로컬. 상한(배타)은 다음 /10 경계인 fec0::.
  if (value >= FE80 && value < FEC0) return true;
  // ff00::/8 — 멀티캐스트. 최상위 대역이라 상한이 없다.
  if (value >= FF00) return true;
  return false;
}

function isBlockedIPv4(address: string): boolean {
  const octets = address.split('.').map((part) => Number(part));
  const [a, b, c, d] = octets;
  if (a === 127) return true; // 루프백 127.0.0.0/8
  if (a === 10) return true; // RFC1918
  if (a === 172 && b >= 16 && b <= 31) return true; // RFC1918
  if (a === 192 && b === 168) return true; // RFC1918
  if (a === 169 && b === 254) return true; // 링크로컬 169.254.0.0/16
  if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT 100.64.0.0/10
  if (a === 0) return true; // 예약된 "이 네트워크" 0.0.0.0/8
  if (a >= 224 && a <= 239) return true; // 멀티캐스트 224.0.0.0/4
  if (a === 255 && b === 255 && c === 255 && d === 255) return true; // 제한 브로드캐스트
  return false;
}

// 이 파일이 직접 쓰는 리터럴 대역 상수(예: `'fc00::'`)를 128비트 정수로 바꾼다 — 항상 정규
// 형태라 `ipv6ToBigInt` 가 그대로 받는다(그 함수의 "리터럴 상수만 들어온다" 전제 참고).
const FC00 = ipv6ToBigInt('fc00::');
const FE00 = ipv6ToBigInt('fe00::');
const FE80 = ipv6ToBigInt('fe80::');
const FEC0 = ipv6ToBigInt('fec0::');
const FF00 = ipv6ToBigInt('ff00::');

function bigIntToIPv4(low32: bigint): string {
  const b0 = (low32 >> 24n) & 0xffn;
  const b1 = (low32 >> 16n) & 0xffn;
  const b2 = (low32 >> 8n) & 0xffn;
  const b3 = low32 & 0xffn;
  return `${b0}.${b1}.${b2}.${b3}`;
}

/**
 * IPv6 문자열을 128비트 정수로 바꾼다. `::` 압축과, 꼬리가 점십진 IPv4 표기(`::ffff:10.0.0.5`)인
 * 혼합 표기를 둘 다 받는다 — `dns.lookup` 이 실제로 돌려주는 형태(이 실행 환경에서는 순수 16진
 * 꼬리, 예: `::ffff:a00:5`)와 RFC5952 가 권장하는 점십진 꼬리 표기가 갈릴 수 있어 둘 다 다뤄야
 * 안전하다(위 IPv4-매핑 판정 주석 참고). `dns.lookup` 이 돌려주는 주소나 이 파일이 직접 쓰는
 * 리터럴 상수만 입력으로 들어온다는 전제라, 임의의 비정상 문자열까지 방어적으로 받아내지는 않는다.
 */
function ipv6ToBigInt(address: string): bigint {
  const halves = address.split('::');
  const expandDottedTail = (groups: string[]): string[] =>
    groups.flatMap((group) => {
      if (!group.includes('.')) return [group];
      // 마지막 그룹이 점십진 IPv4 표기다 — 16비트 16진 그룹 두 개로 바꾼다.
      const octets = group.split('.').map(Number);
      const hi = ((octets[0] << 8) | octets[1]).toString(16);
      const lo = ((octets[2] << 8) | octets[3]).toString(16);
      return [hi, lo];
    });

  let groups: string[];
  if (halves.length === 2) {
    const head = expandDottedTail(halves[0] ? halves[0].split(':') : []);
    const tail = expandDottedTail(halves[1] ? halves[1].split(':') : []);
    const missing = 8 - head.length - tail.length;
    groups = [...head, ...Array(Math.max(missing, 0)).fill('0'), ...tail];
  } else {
    groups = expandDottedTail(address.split(':'));
  }

  let result = 0n;
  for (const group of groups) {
    result = (result << 16n) | BigInt(parseInt(group || '0', 16));
  }
  return result;
}
