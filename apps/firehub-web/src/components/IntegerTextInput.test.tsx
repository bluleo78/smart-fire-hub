/**
 * IntegerTextInput 단위 테스트 (WD-906)
 *
 * type="number" 칸은 `1e` 같은 덜 된 숫자를 ""로 보고해 부모가 기본값으로 바꿔 버렸다.
 * 이 공용 칸은 원문을 직접 들고 있다가, 표기가 정수일 때만 부모에 숫자로 넘기는지 검증한다.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { IntegerTextInput } from './IntegerTextInput';

/** 부모의 커밋된 정수 상태를 가진 하네스 — 부모가 value 를 바꾸는 상황도 버튼으로 흉내 낸다. */
function Harness({
  initial = 60,
  onValueChange,
  disabled,
}: {
  initial?: number;
  onValueChange: (n: number) => void;
  disabled?: boolean;
}) {
  const [value, setValue] = useState(initial);
  return (
    <div>
      <IntegerTextInput
        id="num"
        value={value}
        disabled={disabled}
        onValueChange={(n) => {
          onValueChange(n);
          setValue(n);
        }}
      />
      <button type="button" onClick={() => setValue(90)}>
        외부 변경
      </button>
    </div>
  );
}

describe('IntegerTextInput (WD-906)', () => {
  it('올바른 정수를 치면 부모에 숫자로 넘긴다', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(<Harness onValueChange={onValueChange} />);
    const box = screen.getByRole('textbox');
    await user.clear(box);
    await user.type(box, '120');
    expect(onValueChange).toHaveBeenLastCalledWith(120);
    expect(box).toHaveValue('120');
    expect(box).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByText('정수를 입력하세요')).toBeNull();
  });

  it.each(['1e', '-', '.', ''])('덜 된 숫자·빈 칸(%j)은 넘기지 않고 오류를 보인다', async (raw) => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(<Harness onValueChange={onValueChange} />);
    const box = screen.getByRole('textbox');
    // 한 글자씩 치면 `1` 이 먼저 커밋되므로, 통째로 붙여 넣어 최종 원문만 본다
    await user.clear(box);
    if (raw) await user.paste(raw);
    expect(onValueChange).not.toHaveBeenCalled();
    expect(box).toHaveAttribute('aria-invalid', 'true');
    const msg = screen.getByText('정수를 입력하세요');
    expect(box.getAttribute('aria-describedby')?.split(' ')).toContain(msg.id);
  });

  it('앞 0·전각 숫자는 정수로 받는다', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(<Harness onValueChange={onValueChange} />);
    const box = screen.getByRole('textbox');
    await user.clear(box);
    await user.type(box, '007');
    expect(onValueChange).toHaveBeenLastCalledWith(7);
    await user.clear(box);
    await user.type(box, '５００');
    expect(onValueChange).toHaveBeenLastCalledWith(500);
    expect(box).not.toHaveAttribute('aria-invalid', 'true');
  });

  it('밖에서 값이 바뀌면 원문을 맞추고 오류를 지운다', async () => {
    const user = userEvent.setup();
    render(<Harness onValueChange={vi.fn()} />);
    const box = screen.getByRole('textbox');
    await user.clear(box);
    await user.type(box, '1e');
    expect(screen.getByText('정수를 입력하세요')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '외부 변경' }));
    expect(box).toHaveValue('90');
    expect(screen.queryByText('정수를 입력하세요')).toBeNull();
    expect(box).not.toHaveAttribute('aria-invalid', 'true');
  });

  it('나머지 props 를 넘긴다', () => {
    render(<Harness onValueChange={vi.fn()} disabled />);
    const box = screen.getByRole('textbox');
    expect(box).toBeDisabled();
    expect(box).toHaveAttribute('id', 'num');
  });

  it('max 로 잘려 prop 이 그대로여도 blur 하면 칸이 저장값으로 돌아온다', async () => {
    const user = userEvent.setup();
    const onBlur = vi.fn();
    function ClampHarness() {
      const [value, setValue] = useState(10);
      return (
        <IntegerTextInput id="num" value={value} max={10} onBlur={onBlur} onValueChange={setValue} />
      );
    }
    render(<ClampHarness />);
    const box = screen.getByRole('textbox');
    await user.clear(box);
    await user.type(box, '100');
    // 입력 중에는 원문 유지
    expect(box).toHaveValue('100');
    await user.tab();
    expect(box).toHaveValue('10');
    expect(onBlur).toHaveBeenCalledTimes(1);
  });

  it('min 은 치는 도중에는 적용하지 않고 blur 때 올려 맞춘다', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    render(<IntegerTextInput id="num" value={50} min={10} onValueChange={onValueChange} />);
    const box = screen.getByRole('textbox');
    await user.clear(box);
    await user.type(box, '5');
    expect(box).toHaveValue('5');
    expect(onValueChange).toHaveBeenLastCalledWith(5);
    await user.tab();
    expect(onValueChange).toHaveBeenLastCalledWith(10);
  });

  it('blur 해도 잘못된 표기는 그대로 두고 오류를 유지한다', async () => {
    const user = userEvent.setup();
    render(<Harness onValueChange={vi.fn()} />);
    const box = screen.getByRole('textbox');
    await user.clear(box);
    await user.type(box, '1e');
    await user.tab();
    expect(box).toHaveValue('1e');
    expect(screen.getByText('정수를 입력하세요')).toBeInTheDocument();
  });

  it('blur 해도 같은 값을 가리키는 원문(007)은 바꾸지 않는다', async () => {
    const user = userEvent.setup();
    render(<Harness onValueChange={vi.fn()} />);
    const box = screen.getByRole('textbox');
    await user.clear(box);
    await user.type(box, '007');
    await user.tab();
    expect(box).toHaveValue('007');
  });
});
