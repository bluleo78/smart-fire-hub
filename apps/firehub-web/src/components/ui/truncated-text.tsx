import { cn } from '@/lib/utils';

interface TruncatedTextProps {
  /** 표시할 값 — 비어 있으면(null·undefined·빈 문자열) fallback 을 보이고 title 은 두지 않는다 */
  value: string | null | undefined;
  /** 값이 비었을 때 보일 문구 (기본: "-") */
  fallback?: string;
  /** 폭 상한 클래스(예: max-w-[16rem]) 등 — 상한이 있어야 말줄임이 걸린다 */
  className?: string;
}

/**
 * 표 칸의 긴 값 말줄임 (WD-559)
 * - 표 셀은 줄바꿈하지 않아(whitespace-nowrap) 띄어쓰기 없는 긴 값이 칸을 늘려 오른쪽 열(작업·상태)을 화면 밖으로 밀어낸다.
 * - 데이터셋 목록(WD-517)과 같은 방식으로, 호출처가 고정 폭 상한(max-w-[..])을 주면 그 폭을 넘는 값만 말줄임되고
 *   평범한 길이의 값은 그대로 다 보인다. 잘린 전체 값은 title 툴팁으로 볼 수 있다.
 */
export function TruncatedText({ value, fallback = '-', className }: TruncatedTextProps) {
  return (
    <span className={cn('block truncate', className)} title={value || undefined}>
      {value || fallback}
    </span>
  );
}
