// 출처: apps/firehub-web/src/lib/utils.ts 에서 복사(P7-c2a, R-2).
// eulReul 은 TenantDetailPage(정지 확인 문구)가 소비한다. iGa 는 admin 에 소비자가 없지만
// 원본을 통째로 유지해 나중 병합 비용을 낮춘다.
import { type ClassValue,clsx } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** 숫자 끝 글자의 한국어 읽기가 받침으로 끝나는 것: 0(영)·1(일)·3(삼)·6(육)·7(칠)·8(팔). 10·100 처럼 0 으로 끝나면 십·백·천·만도 받침. */
const DIGITS_WITH_BATCHIM = '013678';

/**
 * 목적격 조사 "을"/"를"(WD-15).
 * - 한글 음절(가~힣): 마지막 음절 받침이 있으면 "을", 없으면 "를"
 * - 숫자: 한국어 읽기의 끝소리 기준(DIGITS_WITH_BATCHIM)
 * - 그 밖(영문·기호·빈 값·한자 등): 발음을 철자로 정할 수 없어 "을(를)" — 추측이 틀리면 문장이 어색해지므로 병기한다.
 *   이전 구현은 한글이 아니면 무조건 "를" 이었고, 상한 검사가 없어 한자·전각 기호에 임의 결과를 냈다.
 */
export function eulReul(word: string): '을' | '를' | '을(를)' {
  const last = word.slice(-1);
  if (last === '') return '을(를)';
  const code = last.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 > 0 ? '을' : '를';
  if (last >= '0' && last <= '9') return DIGITS_WITH_BATCHIM.includes(last) ? '을' : '를';
  return '을(를)';
}

/** 마지막 음절 받침 유무에 따라 "이"/"가" 반환 */
export function iGa(word: string) {
  const code = word.charCodeAt(word.length - 1) - 0xac00;
  return code >= 0 && code % 28 > 0 ? '이' : '가';
}
