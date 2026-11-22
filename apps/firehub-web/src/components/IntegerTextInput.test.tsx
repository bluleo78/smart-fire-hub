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

import { IntegerDraftContext, useIntegerDrafts } from '@/hooks/useIntegerDrafts';

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

  it('min 미만은 치는 도중 커밋하지 않고 오류로 알리며 blur 때 하한으로 올려 맞춘다 (WD-906 후속)', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    const onValidityChange = vi.fn();
    function MinHarness() {
      const [value, setValue] = useState(50);
      return (
        <IntegerTextInput
          id="num"
          value={value}
          min={10}
          onValueChange={(n) => {
            onValueChange(n);
            setValue(n);
          }}
          onValidityChange={onValidityChange}
        />
      );
    }
    render(<MinHarness />);
    const box = screen.getByRole('textbox');
    await user.clear(box);
    // 비우면 표기 오류 → 무효
    expect(onValidityChange).toHaveBeenLastCalledWith(false);
    await user.type(box, '5');
    expect(box).toHaveValue('5');
    // 하한 미만 — 부모에 커밋하지 않는다
    expect(onValueChange).not.toHaveBeenCalled();
    expect(box).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('10 이상 입력하세요')).toBeInTheDocument();
    expect(box.getAttribute('aria-describedby')).toBeTruthy();
    // 계속 쳐서 하한 이상이 되면 커밋하고 유효로 돌아온다
    await user.type(box, '0');
    // 50 은 부모 값과 같아 커밋 호출은 없다(같은 값 갱신 안 함) — 유효로만 돌아온다
    expect(onValueChange).not.toHaveBeenCalled();
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
    // 다시 하한 미만으로 낮췄다가 blur → 하한으로 올려 커밋하고 유효
    await user.clear(box);
    await user.type(box, '3');
    expect(onValidityChange).toHaveBeenLastCalledWith(false);
    await user.tab();
    expect(onValueChange).toHaveBeenLastCalledWith(10);
    expect(box).toHaveValue('10');
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
    expect(screen.queryByText('10 이상 입력하세요')).toBeNull();
  });

  it('onValidityChange — 표기 오류면 false, 고치면 true, 같은 값은 반복 호출하지 않는다 (WD-906 후속)', async () => {
    const user = userEvent.setup();
    const onValidityChange = vi.fn();
    function Valid() {
      const [value, setValue] = useState(60);
      return <IntegerTextInput id="num" value={value} onValueChange={setValue} onValidityChange={onValidityChange} />;
    }
    render(<Valid />);
    expect(onValidityChange).not.toHaveBeenCalled();
    const box = screen.getByRole('textbox');
    await user.clear(box);
    await user.type(box, '1e');
    // 비움(무효) → 1(유효) → 1e(무효) — 바뀔 때만 호출된다
    expect(onValidityChange.mock.calls).toEqual([[false], [true], [false]]);
    await user.clear(box);
    await user.type(box, '45');
    expect(onValidityChange.mock.calls).toEqual([[false], [true], [false], [true]]);
  });

  it('무효인 채 칸이 사라지면 유효(true)로 되돌린다 (WD-906 후속)', async () => {
    const user = userEvent.setup();
    const onValidityChange = vi.fn();
    function Toggle() {
      const [show, setShow] = useState(true);
      return (
        <div>
          {show && <IntegerTextInput id="num" value={60} onValueChange={() => {}} onValidityChange={onValidityChange} />}
          <button type="button" onClick={() => setShow(false)}>
            숨김
          </button>
        </div>
      );
    }
    render(<Toggle />);
    await user.type(screen.getByRole('textbox'), 'e');
    expect(onValidityChange).toHaveBeenLastCalledWith(false);
    await user.click(screen.getByRole('button', { name: '숨김' }));
    expect(onValidityChange).toHaveBeenLastCalledWith(true);
  });

  it('draftKey — 무효 원문을 보관소에 맡겨 언마운트 뒤 다시 마운트되면 원문·오류를 되살리고, 고치면 지운다 (WD-906 반려 수정)', async () => {
    const user = userEvent.setup();
    /** 편집기처럼 보관소를 들고 칸을 보였다 숨겼다 하며, 맡겨진 원문 수를 보여 준다 */
    function Editor() {
      const { drafts, store } = useIntegerDrafts();
      const [show, setShow] = useState(true);
      return (
        <IntegerDraftContext.Provider value={store}>
          {show && <IntegerTextInput id="num" value={60} onValueChange={() => {}} draftKey="timeoutMs" />}
          <button type="button" onClick={() => setShow((s) => !s)}>
            토글
          </button>
          <output data-testid="count">{drafts.size}</output>
        </IntegerDraftContext.Provider>
      );
    }
    render(<Editor />);
    const count = () => screen.getByTestId('count').textContent;
    await user.type(screen.getByRole('textbox'), 'e');
    expect(count()).toBe('1');
    // 숨겨도(언마운트) 맡긴 원문은 남는다 — 편집기가 저장을 계속 막을 수 있다
    await user.click(screen.getByRole('button', { name: '토글' }));
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(count()).toBe('1');
    // 다시 보이면 원문 60e 와 칸 오류가 되살아난다
    await user.click(screen.getByRole('button', { name: '토글' }));
    expect(screen.getByRole('textbox')).toHaveValue('60e');
    expect(screen.getByText('정수를 입력하세요')).toBeTruthy();
    // 고치면 맡긴 원문이 지워진다
    await user.clear(screen.getByRole('textbox'));
    await user.type(screen.getByRole('textbox'), '45');
    expect(count()).toBe('0');
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
