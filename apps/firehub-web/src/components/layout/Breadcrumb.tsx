import { ChevronRight } from 'lucide-react';
import { Fragment } from 'react';
import { Link } from 'react-router-dom';

import { TruncatedTitle } from '@/components/TruncatedTitle';
import { syncOverflowTitle } from '@/lib/overflow-title';

/**
 * 단순 breadcrumb 컴포넌트 (#101).
 * 마지막 항목은 현재 페이지로 간주해 링크 없이 텍스트로 렌더 (aria-current="page").
 * 마지막 항목은 한 줄 말줄임하고, 잘렸을 때만 title 로 전체 이름을 보인다(WD-656) — 긴 이름이 화면 밖으로 넘치지 않는다.
 * 가운데 항목도 12rem 에서 말줄임 + 잘렸을 때만 title 이다(WD-663).
 */
export interface BreadcrumbItem {
  label: string;
  to?: string; // 미지정 시 현재 페이지로 처리
}

interface BreadcrumbProps {
  items: BreadcrumbItem[];
  className?: string;
}

export function Breadcrumb({ items, className }: BreadcrumbProps) {
  if (items.length === 0) return null;
  return (
    <nav aria-label="현재 위치" className={className}>
      <ol className="flex flex-wrap items-center gap-1 text-sm text-muted-foreground">
        {items.map((item, idx) => {
          const isLast = idx === items.length - 1;
          return (
            // key 에 라벨을 넣지 않는다 — 파이프라인 편집 중 이름을 칠 때마다 마지막 항목이 다시 마운트되던 것을 막는다(WD-663).
            // 항목 목록은 호출부가 고정 순서로 넘기고 상위 두 항목의 to 가 같을 수도 있어 위치(idx)가 가장 안정된 key 다.
            <Fragment key={idx}>
              {/* 마지막 항목: 남는 폭을 우선 받되 최소 10rem 을 기준 폭으로 둔다(WD-663) — 첫 줄에 그만큼 남지 않으면
                  다음 줄로 내려가 전체 폭에서 다 보이거나 말줄임된다. 예전 flex-1(기준 폭 0)은 줄을 바꾸지 않고 몇 px 로 눌렸다.
                  가운데 항목: 최대 12rem 에서 말줄임해 긴 상위 라벨이 화면 밖으로 넘치지 않게 한다(WD-663). */}
              <li
                className={
                  isLast
                    ? 'flex min-w-0 max-w-full flex-[1_1_10rem] items-center'
                    : 'flex min-w-0 max-w-[12rem] items-center'
                }
              >
                {item.to && !isLast ? (
                  <Link
                    to={item.to}
                    className="min-w-0 hover:text-foreground transition-colors"
                    // 링크는 포커스를 받으므로 키보드 포커스 때도 안쪽 라벨의 잘림을 재서 title 을 넘겨 준다
                    onFocus={(e) => syncOverflowTitle(e.currentTarget.firstElementChild)}
                  >
                    <TruncatedTitle text={item.label} />
                  </Link>
                ) : isLast ? (
                  <TruncatedTitle aria-current="page" className="text-foreground font-medium" text={item.label} />
                ) : (
                  <TruncatedTitle text={item.label} />
                )}
              </li>
              {!isLast && (
                <li aria-hidden="true" className="flex items-center">
                  <ChevronRight className="h-3.5 w-3.5" />
                </li>
              )}
            </Fragment>
          );
        })}
      </ol>
    </nav>
  );
}
