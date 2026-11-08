import { ChevronRight } from 'lucide-react';
import { Fragment } from 'react';
import { Link } from 'react-router-dom';

import { TruncatedTitle } from '@/components/TruncatedTitle';

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
              {/* 마지막 항목 — 기준 폭을 min(이름 폭, 10rem) 으로 둔다(WD-669).
                  - 첫 줄에 그만큼 남으면 같은 줄에 남아 남는 폭을 다 받고(긴 이름은 말줄임), 모자라면 다음 줄로 내려가 전체 폭을 받는다.
                  - 예전 flex-[1_1_10rem] 은 짧은 이름(~90px)도 기준 폭 160px 로 재서, 첫 줄에 들어갈 이름까지 다음 줄로 밀었다.
                    flex-1(기준 폭 0, WD-663 이전)은 줄을 바꾸지 않고 몇 px 로 눌렸다.
                  - 구현: li 를 1칸 grid 로 두고 같은 칸에 두 겹을 쌓는다.
                    ① ::after — 이름을 최대 10rem 까지만 그리는 보이지 않는 0 높이 자리잡이. li 의 내용 폭(=flex 기준 폭)을 min(이름, 10rem) 으로 만든다.
                    ② 실제 이름(TruncatedTitle) — contain:inline-size 로 내용 폭 기여를 0 으로 막고, 칸 폭(li 가 늘어난 폭)을 그대로 채운다.
                    ::after 글자는 textContent·접근성 트리에 들어가지 않는다(invisible).
                  가운데 항목: 최대 12rem 에서 말줄임해 긴 상위 라벨이 화면 밖으로 넘치지 않게 한다(WD-663). */}
              <li
                data-label={isLast ? item.label : undefined}
                className={
                  isLast
                    ? 'grid min-w-0 max-w-full flex-auto grid-cols-[minmax(0,1fr)] items-center after:invisible after:col-start-1 after:row-start-1 after:h-0 after:max-w-[10rem] after:overflow-hidden after:font-medium after:whitespace-nowrap after:content-[attr(data-label)]'
                    : 'flex min-w-0 max-w-[12rem] items-center'
                }
              >
                {item.to && !isLast ? (
                  <Link to={item.to} className="min-w-0 hover:text-foreground transition-colors">
                    <TruncatedTitle text={item.label} />
                  </Link>
                ) : isLast ? (
                  <TruncatedTitle
                    aria-current="page"
                    className="col-start-1 row-start-1 text-foreground font-medium [contain:inline-size]"
                    text={item.label}
                  />
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
