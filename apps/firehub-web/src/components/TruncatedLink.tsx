import { Link, type LinkProps } from 'react-router-dom';

import { cn } from '@/lib/utils';

interface TruncatedLinkProps {
  /** 이동할 상세 경로 */
  to: string;
  /** 링크 문구(이름) — 잘려도 title 로 전체 값을 보인다 */
  value: string;
  /** 폭 상한 클래스 — TruncatedText 와 같은 방식 */
  className?: string;
  /** 이동 시 함께 넘길 router state — 행 onClick 이동과 같은 값을 넘겨 상세가 목록 위치를 알게 한다 */
  state?: LinkProps['state'];
}

/**
 * 표 칸 안의 말줄임 상세 링크 (WD-634)
 * - 목록 표의 이름 칸을 키보드로 열 수 있는 상세 링크로 두고(WD-286·WD-396), 상한을 넘는 이름은 말줄임·title 로 보인다.
 * - 초점 표시는 ring(box-shadow)인데 고대비(forced-colors) 모드에서는 box-shadow 가 그려지지 않으므로,
 *   outline-none 대신 outline-hidden(보통은 outline 없음, 고대비에서만 투명 2px outline 을 시스템 색으로 그림)을 써 키보드 초점이 보이게 한다 (WD-944).
 * - 이 링크를 쓰는 표는 모두 행 전체에 onClick(상세 이동)이 있어, 클릭이 행으로 번져 두 번 이동하지 않게 항상 막는다.
 */
export function TruncatedLink({ to, value, className, state }: TruncatedLinkProps) {
  return (
    <Link
      to={to}
      state={state}
      className={cn(
        'inline-block max-w-full truncate align-middle rounded-sm hover:underline focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring',
        className,
      )}
      title={value}
      onClick={(e) => e.stopPropagation()}
    >
      {value}
    </Link>
  );
}
