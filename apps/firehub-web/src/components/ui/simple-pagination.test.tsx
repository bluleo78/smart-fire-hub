import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { SimplePagination } from './simple-pagination';

/**
 * SimplePagination 방향 버튼 가드 단위 시험 (WD-932).
 * 끝 버튼은 disabled 대신 aria-disabled 로 포커스를 지키므로, 눌러도 onPageChange 가
 * 나가지 않게 막는 건 onClick 가드뿐이다. E2E(이력 탭)는 같은 page 로 setState 해 요청이 안 나가
 * 가드 회귀를 못 잡으므로, onPageChange 호출 자체를 여기서 직접 관측한다.
 */
function setup(page: number, totalPages = 5) {
  const onPageChange = vi.fn();
  render(<SimplePagination page={page} totalPages={totalPages} onPageChange={onPageChange} />);
  return { onPageChange, user: userEvent.setup() };
}

/** 클릭·Enter·Space 세 입력 수단으로 버튼을 누른다(키보드는 포커스 후 전송). */
async function pressAll(user: ReturnType<typeof userEvent.setup>, name: string) {
  const button = screen.getByRole('button', { name });
  await user.click(button);
  button.focus();
  await user.keyboard('{Enter}');
  await user.keyboard(' ');
}

describe('SimplePagination 방향 버튼 가드', () => {
  it('첫 페이지에서 「처음 페이지」「이전 페이지」는 눌러도 onPageChange 가 나가지 않고 aria-disabled 다', async () => {
    const { onPageChange, user } = setup(0);
    for (const name of ['처음 페이지', '이전 페이지']) {
      expect(screen.getByRole('button', { name })).toHaveAttribute('aria-disabled', 'true');
      await pressAll(user, name);
    }
    expect(onPageChange).not.toHaveBeenCalled();
  });

  it('끝 페이지에서 「다음 페이지」「마지막 페이지」는 눌러도 onPageChange 가 나가지 않고 aria-disabled 다', async () => {
    const { onPageChange, user } = setup(4);
    for (const name of ['다음 페이지', '마지막 페이지']) {
      expect(screen.getByRole('button', { name })).toHaveAttribute('aria-disabled', 'true');
      await pressAll(user, name);
    }
    expect(onPageChange).not.toHaveBeenCalled();
  });

  it('끝이 아닌 방향 버튼은 aria-disabled 가 없고 기대 페이지로 onPageChange 를 부른다', async () => {
    // 첫 페이지: 다음·마지막 활성
    const first = setup(0);
    expect(screen.getByRole('button', { name: '다음 페이지' })).not.toHaveAttribute('aria-disabled');
    expect(screen.getByRole('button', { name: '마지막 페이지' })).not.toHaveAttribute('aria-disabled');
    await first.user.click(screen.getByRole('button', { name: '다음 페이지' }));
    expect(first.onPageChange).toHaveBeenLastCalledWith(1);
    await first.user.click(screen.getByRole('button', { name: '마지막 페이지' }));
    expect(first.onPageChange).toHaveBeenLastCalledWith(4);
  });

  it('끝 페이지에서 처음·이전 버튼은 활성이며 기대 페이지로 onPageChange 를 부른다', async () => {
    const { onPageChange, user } = setup(4);
    expect(screen.getByRole('button', { name: '처음 페이지' })).not.toHaveAttribute('aria-disabled');
    expect(screen.getByRole('button', { name: '이전 페이지' })).not.toHaveAttribute('aria-disabled');
    await user.click(screen.getByRole('button', { name: '이전 페이지' }));
    expect(onPageChange).toHaveBeenLastCalledWith(3);
    await user.click(screen.getByRole('button', { name: '처음 페이지' }));
    expect(onPageChange).toHaveBeenLastCalledWith(0);
  });
});

/**
 * 페이지 사이즈 콤보박스 접근 이름 단위 시험 (WD-1066, WCAG 2.5.3 Label in Name).
 * 보이는 라벨과 같은 이름으로 콤보박스를 찾을 수 있어야 음성 명령·스크린리더가
 * 사용자가 보는 라벨 그대로 불러도 맞는 컨트롤을 잡는다.
 */
describe('SimplePagination 페이지 사이즈 콤보박스 접근 이름', () => {
  it('페이지 사이즈 콤보박스의 접근 가능한 이름은 보이는 라벨 「페이지당」이다 (WD-1066, WCAG 2.5.3)', () => {
    render(
      <SimplePagination page={0} totalPages={3} onPageChange={vi.fn()} totalElements={30} pageSize={10} onPageSizeChange={vi.fn()} />
    );
    expect(screen.getByRole('combobox', { name: '페이지당' })).toBeInTheDocument();
  });
});
