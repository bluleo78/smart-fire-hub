import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { FormField, FormFieldLabelRow } from './form-field';
import { linkedAriaProps } from './form-field-aria';

/**
 * 공용 FormField 단위 테스트(WD-515·WD-643).
 *
 * WD-643 에서 선택 prop(labelAddon·description·hint·announceError)을 더했다. 쓰는 곳이 많아(11개 화면) 기존 호출부의
 * 동작이 그대로인지(오류 없으면 연결 없음, 오류 있으면 기존 describedby 뒤에 오류 id, role=alert)를 먼저 고정하고,
 * 새 prop 의 연결 순서(오류 → 설명 → 안내)와 role=alert 끄기를 검증한다.
 */
describe('FormField — 기존 동작(하위 호환)', () => {
  it('오류가 없으면 입력에 aria-describedby·aria-invalid 를 붙이지 않는다', () => {
    render(
      <FormField label="이름" htmlFor="name">
        <input id="name" />
      </FormField>,
    );
    const input = screen.getByLabelText('이름');
    expect(input).not.toHaveAttribute('aria-describedby');
    expect(input).not.toHaveAttribute('aria-invalid');
  });

  it('오류가 있으면 role=alert 문구를 그리고, 입력이 원래 가진 설명 id 뒤에 오류 id 를 잇는다', () => {
    render(
      <FormField label="이름" htmlFor="name" error="이름을 입력하세요">
        <input id="name" aria-describedby="name-own" />
      </FormField>,
    );
    const input = screen.getByLabelText('이름');
    expect(input).toHaveAttribute('aria-describedby', 'name-own name-error');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveAttribute('id', 'name-error');
  });
});

describe('FormField — 설명·안내·라벨 덧붙임·오류 낭독 끄기(WD-643)', () => {
  it('설명은 늘, 안내는 줄 때만 잇고 순서는 기존 id → 오류 → 설명 → 안내다', () => {
    const { rerender } = render(
      <FormField label="턴" htmlFor="turns" description="최대 반복 횟수" hint="적용되지 않습니다" error="범위 밖">
        <input id="turns" aria-describedby="turns-own" />
      </FormField>,
    );
    const input = screen.getByLabelText('턴');
    expect(input).toHaveAttribute('aria-describedby', 'turns-own turns-error turns-desc turns-hint');
    expect(screen.getByText('최대 반복 횟수')).toHaveAttribute('id', 'turns-desc');
    expect(screen.getByText('적용되지 않습니다')).toHaveAttribute('id', 'turns-hint');

    // 오류·안내가 없으면 설명만 잇고, 오류가 아니므로 aria-invalid 도 없다
    rerender(
      <FormField label="턴" htmlFor="turns" description="최대 반복 횟수">
        <input id="turns" />
      </FormField>,
    );
    expect(screen.getByLabelText('턴')).toHaveAttribute('aria-describedby', 'turns-desc');
    expect(screen.getByLabelText('턴')).not.toHaveAttribute('aria-invalid');
    expect(document.getElementById('turns-hint')).toBeNull();
  });

  it('announceError={false} 면 오류 문구에 role=alert 가 없지만 입력과의 연결은 그대로다', () => {
    render(
      <FormField label="턴" htmlFor="turns" error="범위 밖" announceError={false}>
        <input id="turns" />
      </FormField>,
    );
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText('범위 밖')).toHaveAttribute('id', 'turns-error');
    expect(screen.getByLabelText('턴')).toHaveAttribute('aria-describedby', 'turns-error');
    expect(screen.getByLabelText('턴')).toHaveAttribute('aria-invalid', 'true');
  });

  it('labelAddon 은 라벨 뒤에 그리고 라벨의 접근 가능한 이름에는 섞이지 않는다', () => {
    render(
      <FormField label="턴" htmlFor="turns" labelAddon={<span>기본값</span>}>
        <input id="turns" />
      </FormField>,
    );
    expect(screen.getByText('기본값')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '턴' })).toBeInTheDocument();
  });
});

describe('공유 도우미 — linkedAriaProps·FormFieldLabelRow(WD-660)', () => {
  it('linkedAriaProps 는 FormField 와 같은 순서(기존 id → 오류 → 설명 → 안내)로 잇고, aria-invalid 는 오류일 때만 둔다', () => {
    expect(linkedAriaProps({ error: 'e', desc: 'd', hint: 'h' }, { 'aria-describedby': 'own d' })).toEqual({
      'aria-describedby': 'own d e h',
      'aria-invalid': true,
    });
    expect(linkedAriaProps({ desc: 'd' })).toEqual({ 'aria-describedby': 'd' });
    expect(linkedAriaProps({})).toEqual({ 'aria-describedby': undefined });
    // 화면이 직접 정한 aria-invalid 는 지킨다
    expect(linkedAriaProps({ error: 'e' }, { 'aria-invalid': 'false' })['aria-invalid']).toBe('false');
  });

  it('FormFieldLabelRow 는 덧붙임이 없으면 감싸지 않고, 있으면 라벨과 한 줄(flex)로 감싼다', () => {
    const { container, rerender } = render(<FormFieldLabelRow htmlFor="x">모델</FormFieldLabelRow>);
    expect(container.firstElementChild?.tagName).toBe('LABEL');
    rerender(
      <FormFieldLabelRow htmlFor="x" addon={<span>기본값</span>}>
        모델
      </FormFieldLabelRow>,
    );
    expect(container.firstElementChild).toHaveClass('flex');
    expect(container.querySelector('label')).toHaveTextContent(/^모델$/);
  });
});

describe('FormField — 필수 표시(WD-908)', () => {
  it('required 면 htmlFor 입력에 aria-required="true" 를 붙인다', () => {
    render(
      <FormField label="이름" htmlFor="name" required>
        <input id="name" />
      </FormField>,
    );
    const input = screen.getByRole('textbox', { name: '이름' });
    expect(input).toHaveAttribute('aria-required', 'true');
    expect(input).not.toHaveAttribute('required');
  });

  it('라벨의 * 는 접근 이름에서 빠지고 텍스트로는 보인다', () => {
    const { container } = render(
      <FormField label="이름" htmlFor="name" required>
        <input id="name" />
      </FormField>,
    );
    expect(screen.getByText('*')).toHaveAttribute('aria-hidden', 'true');
    expect(container.querySelector('label')?.textContent).toBe('이름*');
  });

  it('required 가 아니면 aria-required 를 붙이지 않는다', () => {
    render(
      <FormField label="이름" htmlFor="name">
        <input id="name" />
      </FormField>,
    );
    expect(screen.getByRole('textbox')).not.toHaveAttribute('aria-required');
  });

  it('화면이 넣은 aria-required 를 덮어쓰지 않는다', () => {
    render(
      <FormField label="이름" htmlFor="name" required>
        <input id="name" aria-required={false} />
      </FormField>,
    );
    expect(screen.getByRole('textbox')).toHaveAttribute('aria-required', 'false');
  });

  it('오류와 함께면 셋 다 붙는다', () => {
    render(
      <FormField label="이름" htmlFor="name" required error="이름을 입력하세요">
        <input id="name" />
      </FormField>,
    );
    const input = screen.getByRole('textbox');
    expect(input).toHaveAttribute('aria-required', 'true');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input.getAttribute('aria-describedby')).toContain('name-error');
  });

  it('오류가 생겼다 사라져도 입력 노드가 그대로다', () => {
    const field = (error?: string) => (
      <FormField label="이름" htmlFor="name" required error={error}>
        <input id="name" />
      </FormField>
    );
    const { rerender } = render(field());
    const node = screen.getByRole('textbox');
    rerender(field('오류'));
    rerender(field());
    expect(screen.getByRole('textbox')).toBe(node);
  });

  it('FormFieldLabelRow 단독 required 는 * 를 감추지 않고, markerHidden 이면 aria-hidden 으로 감춘다', () => {
    const { rerender } = render(
      <FormFieldLabelRow htmlFor="m" required>
        모델
      </FormFieldLabelRow>,
    );
    expect(screen.getByText('*')).not.toHaveAttribute('aria-hidden');
    rerender(
      <FormFieldLabelRow htmlFor="m" required markerHidden>
        모델
      </FormFieldLabelRow>,
    );
    expect(screen.getByText('*')).toHaveAttribute('aria-hidden', 'true');
  });

  // WD-908 code-review: 감싼 입력은 FormField 가 aria-required 를 붙이지 못하니 별표를 남겨 필수 정보를 잃지 않게 한다.
  it('감싼 입력이면 별표를 감추지 않는다', () => {
    render(
      <FormField label="이름" htmlFor="name" required>
        <div>
          <input id="name" />
        </div>
      </FormField>,
    );
    expect(screen.getByText('*')).not.toHaveAttribute('aria-hidden');
    expect(screen.getByLabelText('이름*')).toBeInTheDocument();
  });
});
