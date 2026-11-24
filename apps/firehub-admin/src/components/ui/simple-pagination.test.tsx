import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { SimplePagination } from './simple-pagination';

/**
 * 페이지 사이즈 콤보박스 접근 이름 시험 (WD-1066 리뷰 후속, WCAG 2.5.3 Label in Name).
 *
 * 관리 콘솔 사본(simple-pagination.tsx)은 웹 사본과 따로 고쳐지므로 이 사본의 접근 이름도
 * 시험이 지켜야 한다(리뷰 글 1번). 관리 콘솔 화면에는 `onPageSizeChange` 를 넘기는 곳이 없어
 * 사이즈 콤보박스 자체가 화면에 뜨지 않으므로(ShowSizeSelector 조건), E2E 로는 이 컴포넌트의
 * 접근 이름을 띄울 수 없어 단위 시험이 두 사본을 잇는 자리다.
 */
describe('SimplePagination 페이지 사이즈 콤보박스 접근 이름', () => {
  it('접근 가능한 이름은 보이는 라벨 「페이지당」과 정확히 같다 (WD-1066)', () => {
    render(
      <SimplePagination
        page={0}
        totalPages={3}
        onPageChange={vi.fn()}
        totalElements={30}
        pageSize={10}
        onPageSizeChange={vi.fn()}
      />,
    );
    // 양끝 일치 정규식 — RTL 의 문자열 name 은 이미 정확 일치라(정규식은 의도만 명시) 「페이지당」만 골라 낸다
    expect(screen.getByRole('combobox', { name: /^페이지당$/ })).toBeInTheDocument();
    // 보이는 라벨 문구도 그대로 남아 접근 이름을 덮지 않음을 함께 본다(RTL 은 기본이 정확 일치)
    expect(screen.getByText('페이지당')).toBeInTheDocument();
  });
});
