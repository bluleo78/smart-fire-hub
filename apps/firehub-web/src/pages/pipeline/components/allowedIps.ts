/**
 * Java `String.trim()` 뒤 빈 문자열이 되는지 — 모든 글자가 U+0020 이하이면 그렇다. JS `trim()` 은 유니코드 공백(U+00A0·U+3000·U+FEFF 등)까지
 * 잘라 서버와 판정이 어긋나므로 쓰지 않는다. 서버(TriggerService.isBlankEntry)와 공백 판정을 맞추기 위해서다(WD-882).
 */
function isJavaBlank(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    if (value.charCodeAt(i) > 0x20) return false;
  }
  return true;
}

/**
 * 편집 창에 불러올 API 트리거 config 에서 공백뿐인 allowedIps 원소를 뺀 새 객체를 돌려준다 (WD-882).
 * 서버는 Java trim 뒤 비는 원소를 무시한다(TriggerService.isBlankEntry, WD-868) — 그런 값이 남은 옛 트리거를
 * 그대로 보이면 빈 칩이 생기고, 저장 때 그 값을 되돌려 보낸다. 남기는 원소는 trim 하지 않고 그대로 둔다.
 *
 * 문자열이 아닌 원소(null 등)가 하나라도 있으면 거르지 않고 config 를 그대로 돌려준다 — 서버는 그런 목록을 모든 호출 거절(fail closed)로
 * 다루고 저장도 거부하므로, 여기서 조용히 빼면 「모두 막힘」 트리거가 「제한 없음」으로 보이고 저장 한 번에 열려 버린다.
 * allowedIps 가 배열이 아니거나 없으면(WD-109) config 를 그대로 돌려준다.
 */
export function withoutBlankAllowedIps(config: Record<string, unknown>): Record<string, unknown> {
  const ips = config.allowedIps;
  if (!Array.isArray(ips) || !ips.every((ip): ip is string => typeof ip === 'string')) return config;
  return { ...config, allowedIps: ips.filter((ip) => !isJavaBlank(ip)) };
}
