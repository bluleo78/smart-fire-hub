import { cn } from '@/lib/utils';

interface TruncatedTextProps {
  /** 표시할 값 — 비어 있으면(null·undefined·빈 문자열) fallback 을 보이고 title 은 두지 않는다 */
  value: string | null | undefined;
  /** 값이 비었을 때 보일 문구 (기본: "-") */
  fallback?: string;
  /** 폭 상한 클래스 — 상한이 있어야 말줄임이 걸린다 */
  className?: string;
}

/**
 * 표 칸의 긴 값 말줄임 (WD-559, WD-634)
 * - 규칙: 표는 자동 레이아웃 그대로 두고, 긴 값에만 `max-w-[min(Nrem,calc((100cqw_-_var(--k))_*_몫))]` 상한을 건다.
 *   표 wrapper 에 `@container` 와 `--k`(짧은 칸 선언 폭 w-[..] 의 합 + 긴 값 칸 여백)를 두고, 한 표 안 몫의 합은 1 이하로 한다.
 *   상한을 넘는 값만 말줄임되고 전체 값은 title 로 보인다.
 * - 이유: 상한의 합이 언제나 짧은 칸을 뺀 남은 폭 안이라, 긴 값이 한꺼번에 와도 표가 넘치지 않으면서 짧은 값은 내용 폭만 차지한다.
 */
export function TruncatedText({ value, fallback = '-', className }: TruncatedTextProps) {
  return (
    <span className={cn('block truncate', className)} title={value || undefined}>
      {value || fallback}
    </span>
  );
}
