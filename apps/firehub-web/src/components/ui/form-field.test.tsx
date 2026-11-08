import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { FormField } from './form-field';

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
