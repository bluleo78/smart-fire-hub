import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { formatDateOnly, formatDateTimeMinute } from '@/lib/formatters';
import { cn } from '@/lib/utils';

import { Badge } from './badge';
import { Button } from './button';
import { InlineBanner } from './inline-banner';
import { StatusBadge } from './status-badge';

describe('복사한 UI 프리미티브', () => {
  it('Button/Badge/StatusBadge/InlineBanner 가 렌더된다', () => {
    render(
      <>
        <Button variant="destructive">테넌트 정지</Button>
        <Badge variant="outline">운영자 콘솔</Badge>
        <StatusBadge type="active">활성</StatusBadge>
        <InlineBanner variant="info" title="안내">본문</InlineBanner>
      </>,
    );
    expect(screen.getByRole('button', { name: '테넌트 정지' })).toBeInTheDocument();
    expect(screen.getByText('운영자 콘솔')).toBeInTheDocument();
    expect(screen.getByText('활성')).toBeInTheDocument();
    expect(screen.getByText('안내')).toBeInTheDocument();
  });

  it('cn 이 tailwind 클래스를 병합한다', () => {
    expect(cn('p-2', 'p-4')).toBe('p-4');
  });

  it('날짜 포맷터가 고정폭 문자열을 만든다', () => {
    expect(formatDateOnly('2026-03-04T09:21:14')).toBe('2026-03-04');
    expect(formatDateTimeMinute('2026-08-19T14:02:31')).toBe('2026-08-19 14:02');
  });
});
