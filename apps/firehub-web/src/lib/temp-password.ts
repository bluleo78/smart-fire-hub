/**
 * 관리자가 멤버 추가 시 쓰는 임시 비밀번호 생성기(WD-2).
 *
 * <p>왜 crypto.getRandomValues + 거부 표본추출인가: Math.random 은 예측 가능하고, `% n` 은 모듈로 편향이
 * 생긴다. 왜 혼동 문자를 빼는가: 임시 비밀번호는 관리자가 사람에게 직접(말·메신저) 전달한다.
 */

/** 서버 비밀번호 정책(가입·AddMemberRequest 와 동일): 소문자·대문자·숫자 각 1자 이상. 길이는 스키마가 따로 본다. */
export const PASSWORD_POLICY = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/;

/**
 * 서버 비밀번호 길이 상한(PasswordRules.MAX_BYTES) — UTF-8 72바이트 (WD-605).
 *
 * <p>왜 글자 수가 아니라 바이트인가: 서버가 저장·검증에 쓰는 BCrypt 는 입력의 앞 72바이트만 해시한다. 그보다 길면 뒤쪽이
 * 비밀번호 구실을 못 한다(앞 72자만 알면 로그인). 한글은 UTF-8 로 1자 3바이트라 글자 수 상한으로는 표현할 수 없다.
 */
export const PASSWORD_MAX_BYTES = 72;

/** 바이트 상한 위반 문구 — 서버 PasswordRules.MAX_BYTES_MESSAGE 와 같다. 사용자는 바이트를 모르므로 글자 수 기준을 함께 알린다. */
export const PASSWORD_MAX_BYTES_MESSAGE = '비밀번호는 72바이트(영문·숫자 72자, 한글 24자) 이하여야 합니다';

/** 비밀번호 입력 아래 규칙 안내 — 한글이 3바이트로 세어진다는 점을 미리 알린다 (WD-605). */
export const PASSWORD_HELP_TEXT = '8자 이상·72바이트 이하(한글 1자는 3바이트), 대·소문자·숫자 포함';

const utf8Encoder = new TextEncoder();

/** UTF-8 바이트 수 상한 안인지 — zod refine 에서 쓴다. 서버 PasswordRules.withinMaxBytes 와 같은 판정이다. */
export function withinPasswordMaxBytes(value: string): boolean {
  // 1자는 최소 1바이트(UTF-16 코드 유닛 수 ≤ UTF-8 바이트 수)라 길이가 넘으면 인코딩 없이 바로 거부한다.
  if (value.length > PASSWORD_MAX_BYTES) return false;
  return utf8Encoder.encode(value).length <= PASSWORD_MAX_BYTES;
}

const LOWER = 'abcdefghijkmnpqrstuvwxyz'; // l·o 제외
const UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ'; // I·O 제외
const DIGIT = '23456789'; // 0·1 제외
const ALL = LOWER + UPPER + DIGIT;

/** [0, max) 균등 난수 — 거부 표본추출로 모듈로 편향 제거. */
function randomIndex(max: number): number {
  const buf = new Uint32Array(1);
  const limit = Math.floor(0x1_0000_0000 / max) * max;
  let x: number;
  do {
    crypto.getRandomValues(buf);
    x = buf[0];
  } while (x >= limit);
  return x % max;
}

/** 서버 정책을 항상 만족하는 임시 비밀번호. 각 문자군 1자를 먼저 넣고 섞는다(Fisher–Yates). */
export function generateTemporaryPassword(length = 12): string {
  const pick = (set: string) => set[randomIndex(set.length)];
  const chars = [pick(LOWER), pick(UPPER), pick(DIGIT)];
  while (chars.length < length) chars.push(pick(ALL));
  for (let i = chars.length - 1; i > 0; i--) {
    const j = randomIndex(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}
