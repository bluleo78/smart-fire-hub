import { ChevronRight } from 'lucide-react';
import { Fragment } from 'react';
import { Link } from 'react-router-dom';

import { TruncatedTitle } from '@/components/TruncatedTitle';

/**
 * 단순 breadcrumb 컴포넌트 (#101).
 * 마지막 항목은 현재 페이지로 간주해 링크 없이 텍스트로 렌더 (aria-current="page").
 * 마지막 항목은 한 줄 말줄임하고, 잘렸을 때만 title 로 전체 이름을 보인다(WD-656) — 긴 이름이 화면 밖으로 넘치지 않는다.
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
            <Fragment key={`${item.label}-${idx}`}>
              {/* 마지막 항목은 남는 폭만 받아(flex-1 min-w-0) 상위 항목과 같은 줄에서 말줄임된다 */}
              <li className={isLast ? 'flex min-w-0 flex-1 items-center' : 'flex items-center'}>
                {item.to && !isLast ? (
                  <Link
                    to={item.to}
                    className="hover:text-foreground transition-colors"
                  >
                    {item.label}
                  </Link>
                ) : isLast ? (
                  <TruncatedTitle aria-current="page" className="text-foreground font-medium" text={item.label} />
                ) : (
                  <span>{item.label}</span>
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
