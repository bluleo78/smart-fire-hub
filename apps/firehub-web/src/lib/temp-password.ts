/**
 * 관리자가 멤버 추가 시 쓰는 임시 비밀번호 생성기(WD-2).
 *
 * <p>왜 crypto.getRandomValues + 거부 표본추출인가: Math.random 은 예측 가능하고, `% n` 은 모듈로 편향이
 * 생긴다. 왜 혼동 문자를 빼는가: 임시 비밀번호는 관리자가 사람에게 직접(말·메신저) 전달한다.
 */

/** 서버 비밀번호 정책(가입·AddMemberRequest 와 동일): 소문자·대문자·숫자 각 1자 이상. 길이는 스키마가 따로 본다. */
export const PASSWORD_POLICY = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/;

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
