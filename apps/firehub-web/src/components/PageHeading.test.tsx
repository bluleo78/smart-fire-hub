import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { PageHeading } from './PageHeading';

/**
 * PageHeading 단위 테스트 (WD-910).
 * 표준 타이포그래피 네 클래스가 빠지지 않는지, 호출처가 넘긴 className 이 표준을 지우지 않고 함께 남는지,
 * id·aria-* 같은 나머지 속성이 h1 에 그대로 전달되는지 확인한다.
 */
const STANDARD = ['text-[28px]', 'leading-[36px]', 'font-semibold', 'tracking-tight'];

describe('PageHeading', () => {
  it('level 1 heading 이고 표준 네 클래스를 모두 가진다', () => {
    render(<PageHeading>제목</PageHeading>);
    const h1 = screen.getByRole('heading', { level: 1, name: '제목' });
    for (const cls of STANDARD) expect(h1).toHaveClass(cls);
  });

  it('레이아웃 클래스는 남고, 타이포 클래스를 넘겨도 표준이 이긴다 (특히 leading-[36px])', () => {
    render(
      <PageHeading className="min-w-0 wrap-anywhere text-lg leading-6 font-bold tracking-normal">제목</PageHeading>,
    );
    const h1 = screen.getByRole('heading', { level: 1 });
    expect(h1).toHaveClass('min-w-0', 'wrap-anywhere', ...STANDARD);
    for (const cls of ['text-lg', 'leading-6', 'font-bold', 'tracking-normal']) expect(h1).not.toHaveClass(cls);
  });

  it('id·aria-describedby 가 h1 에 전달된다', () => {
    render(
      <PageHeading id="page-title" aria-describedby="page-desc">
        제목
      </PageHeading>,
    );
    const h1 = screen.getByRole('heading', { level: 1 });
    expect(h1).toHaveAttribute('id', 'page-title');
    expect(h1).toHaveAttribute('aria-describedby', 'page-desc');
  });
});
