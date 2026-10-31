/**
 * 이메일 수신자 계산 규칙(WD-299) — 편집 화면의 중복 검사와 「N명에게 전달됩니다」가 같은 규칙을 쓰게 한 곳에 둔다.
 * 서버 발송(EmailDeliveryChannel.distinctAddresses)도 같은 규칙으로 한 사람에게 한 통만 보낸다.
 */

/** 이메일 주소 비교용 정규화 — 메일 주소는 대소문자를 가리지 않으므로 앞뒤 공백을 빼고 소문자로 맞춘다 */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

/**
 * 이메일 채널의 실제 받는 사람 수 — 등록 사용자 수에, 등록 사용자 주소와 겹치지 않는 외부 이메일 수를 더한다.
 * 외부 이메일끼리도 대소문자만 다르면 한 사람으로 센다.
 */
export function countEmailRecipients(
  userIds: number[],
  externalEmails: string[],
  registeredEmails: string[],
): number {
  const seen = new Set(registeredEmails.map(normalizeEmail));
  let external = 0;
  for (const email of externalEmails) {
    const key = normalizeEmail(email);
    if (seen.has(key)) continue;
    seen.add(key);
    external += 1;
  }
  return userIds.length + external;
}
