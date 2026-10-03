// 출처: apps/firehub-web/src/lib/utils.ts 에서 복사(P7-c2a, R-2).
// eulReul 은 TenantDetailPage(정지 확인 문구)가 소비한다. iGa 는 admin 에 소비자가 없지만
// 원본을 통째로 유지해 나중 병합 비용을 낮춘다.
import { type ClassValue,clsx } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** 마지막 음절 받침 유무에 따라 "을"/"를" 반환 */
export function eulReul(word: string) {
  const code = word.charCodeAt(word.length - 1) - 0xac00;
  return code >= 0 && code % 28 > 0 ? '을' : '를';
}

/** 마지막 음절 받침 유무에 따라 "이"/"가" 반환 */
export function iGa(word: string) {
  const code = word.charCodeAt(word.length - 1) - 0xac00;
  return code >= 0 && code % 28 > 0 ? '이' : '가';
}
