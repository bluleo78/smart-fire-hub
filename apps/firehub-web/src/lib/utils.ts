import { type ClassValue,clsx } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** 숫자 끝 글자의 한국어 읽기가 받침으로 끝나는 것: 0(영)·1(일)·3(삼)·6(육)·7(칠)·8(팔). 10·100 처럼 0 으로 끝나면 십·백·천·만도 받침. */
const DIGITS_WITH_BATCHIM = '013678';
/** 숫자 끝 글자 읽기가 ㄹ 이 아닌 받침으로 끝나는 것: 0(영)·3(삼)·6(육) — "으로" 를 받는다. 1·7·8(일·칠·팔)은 ㄹ 받침이라 "로"(WD-1135). */
const DIGITS_WITH_NON_RIEUL_BATCHIM = '036';

/** 조사를 고를 마지막 글자 — 끝 공백은 발음과 무관해 건너뛴다(입력·AI 페이로드 끝 공백, WD-1135). 공백뿐이면 빈 문자열. */
function lastChar(word: string): string {
  return word.trimEnd().slice(-1);
}

/**
 * 목적격 조사 "을"/"를"(WD-15).
 * - 한글 음절(가~힣): 마지막 음절 받침이 있으면 "을", 없으면 "를"
 * - 숫자: 한국어 읽기의 끝소리 기준(DIGITS_WITH_BATCHIM)
 * - 그 밖(영문·기호·빈 값·한자 등): 발음을 철자로 정할 수 없어 "을(를)" — 추측이 틀리면 문장이 어색해지므로 병기한다.
 *   이전 구현은 한글이 아니면 무조건 "를" 이었고, 상한 검사가 없어 한자·전각 기호에 임의 결과를 냈다.
 */
export function eulReul(word: string): '을' | '를' | '을(를)' {
  const last = lastChar(word);
  if (last === '') return '을(를)';
  const code = last.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 > 0 ? '을' : '를';
  if (last >= '0' && last <= '9') return DIGITS_WITH_BATCHIM.includes(last) ? '을' : '를';
  return '을(를)';
}

/**
 * 공동격 조사 "와"/"과"(WD-1135).
 * - 한글 음절: 마지막 음절 받침이 있으면 "과", 없으면 "와"
 * - 숫자: 읽기 끝소리가 받침 있는 숫자(DIGITS_WITH_BATCHIM: 0·1·3·6·7·8)면 "과", 아니면 "와"
 * - 그 밖(영문·기호·빈 값·한자 등): 발음을 철자로 정할 수 없어 "와(과)" 병기 — eulReul 과 같은 이유
 * 화면 문구("X와 Y")가 조사 자리에서 어색해지는 것을 막기 위해 도입했다.
 */
export function waGwa(word: string): '와' | '과' | '와(과)' {
  const last = lastChar(word);
  if (last === '') return '와(과)';
  const code = last.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 > 0 ? '과' : '와';
  if (last >= '0' && last <= '9') return DIGITS_WITH_BATCHIM.includes(last) ? '과' : '와';
  return '와(과)';
}

/**
 * 도구격·방향 조사 "로"/"으로"(WD-1135).
 * - 한글 음절: 받침이 없거나 ㄹ 받침(종성 인덱스 8)이면 "로", 그 밖 받침이면 "으로"
 * - 숫자: 읽기 끝소리 기준으로 1·7·8 은 "로", 0·3·6 은 "으로", 2·4·5·9 는 "로"
 * - 그 밖(영문·기호·빈 값·한자 등): 발음을 철자로 정할 수 없어 "(으)로" 병기
 * 한글 ㄹ 받침은 "로"로 읽히기 때문에 별도 분기로 처리한다.
 */
export function euroRo(word: string): '로' | '으로' | '(으)로' {
  const last = lastChar(word);
  if (last === '') return '(으)로';
  const code = last.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) {
    const jong = (code - 0xac00) % 28;
    return jong === 0 || jong === 8 ? '로' : '으로';
  }
  if (last >= '0' && last <= '9') return DIGITS_WITH_NON_RIEUL_BATCHIM.includes(last) ? '으로' : '로';
  return '(으)로';
}

/**
 * 주격 조사 "이"/"가"(WD-1179).
 * - 한글 음절: 마지막 음절 받침이 있으면 "이", 없으면 "가"
 * - 숫자: 읽기 끝소리가 받침 있는 숫자(DIGITS_WITH_BATCHIM: 0·1·3·6·7·8)면 "이", 아니면 "가"
 * - 그 밖(영문·기호·빈 값·한자 등): 발음을 철자로 정할 수 없어 "이(가)" 병기 — eulReul·waGwa 와 같은 이유
 * 이전 구현은 마지막 글자 코드만 봐서 끝 공백·숫자 이름("데이터셋 1"가)에서 틀린 조사를 냈다.
 */
export function iGa(word: string): '이' | '가' | '이(가)' {
  const last = lastChar(word);
  if (last === '') return '이(가)';
  const code = last.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 > 0 ? '이' : '가';
  if (last >= '0' && last <= '9') return DIGITS_WITH_BATCHIM.includes(last) ? '이' : '가';
  return '이(가)';
}
