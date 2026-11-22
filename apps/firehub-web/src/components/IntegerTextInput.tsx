import * as React from 'react';

import { Input } from '@/components/ui/input';
import { isIntegerSyntax } from '@/lib/settings-number';
import { cn } from '@/lib/utils';

type IntegerTextInputProps = Omit<
  React.ComponentProps<typeof Input>,
  'type' | 'value' | 'onChange' | 'inputMode' | 'min' | 'max'
> & {
  /** 부모가 들고 있는 커밋된 정수 */
  value: number;
  /** 원문이 정수 표기일 때 호출된다 (결과 숫자가 value 와 다를 때만) */
  onValueChange: (n: number) => void;
  /** 상한 — 치는 도중에도 이 값으로 자른다 */
  max?: number;
  /** 하한 — 치는 도중에는 적용하지 않고 칸을 떠날 때 올려 맞춘다 (`5` → 하한으로 튀어 `50` 을 못 치는 것 방지) */
  min?: number;
  /** 표기 오류 문구의 id (없으면 자동 생성) */
  errorId?: string;
  /** 입력 칸과 오류 문구를 감싸는 요소의 클래스 */
  wrapperClassName?: string;
};

/** 전각 숫자 등을 반각으로 맞추고 앞뒤 공백을 걷은 뒤 정수 표기면 숫자로, 아니면 null — `５００` → 500 */
function parseInteger(raw: string): number | null {
  const text = raw.normalize('NFKC').trim();
  return isIntegerSyntax(text) ? Number(text) : null;
}

/**
 * 정수 입력 칸 (WD-906).
 *
 * `<input type="number">` 는 `1e` 같은 덜 된 숫자를 ""로 보고해, `Number(v) || 기본값` 형태의 부모가
 * 사용자 입력을 말없이 기본값으로 바꿔 버렸다. 그래서 type="text" 로 원문을 직접 들고 있다가
 * 정수 표기를 통과할 때만 부모에 숫자로 넘기고, 아니면 커밋하지 않고 칸 아래에 오류를 보인다.
 * min/max 는 선택이며, 범위 밖 값을 오류로 안내하는 일은 부모 몫이다.
 */
export function IntegerTextInput({
  value,
  onValueChange,
  min,
  max,
  errorId,
  wrapperClassName,
  'aria-describedby': describedBy,
  'aria-invalid': ariaInvalid,
  onBlur,
  ...rest
}: IntegerTextInputProps) {
  const autoId = React.useId();
  const messageId = errorId ?? autoId;
  const [raw, setRaw] = React.useState(String(value));
  const [syntaxError, setSyntaxError] = React.useState(false);
  const [prevValue, setPrevValue] = React.useState(value);

  // 부모 value 가 바뀌었고 지금 원문이 그 값을 가리키지 않으면(외부 변경·초기화) 원문을 맞추고 오류를 지운다.
  // 사용자가 친 `007` 처럼 같은 값을 가리키는 원문은 그대로 둬 입력 중 칸이 튀지 않게 한다.
  if (value !== prevValue) {
    setPrevValue(value);
    if (parseInteger(raw) !== value) {
      setRaw(String(value));
      setSyntaxError(false);
    }
  }

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setRaw(next);
    const n = parseInteger(next);
    if (n === null) {
      setSyntaxError(true);
      return;
    }
    setSyntaxError(false);
    // 치는 도중에는 상한만 적용한다
    const capped = max === undefined ? n : Math.min(n, max);
    if (capped !== value) onValueChange(capped);
  };

  // 포커스를 잃을 때: 유효한 원문이면 하한까지 적용한 최종 값을 커밋하고(같으면 갱신하지 않는다),
  // 칸 글자도 그 값으로 맞춘다(상한 10 에 `100` → `10`). 잘못된 표기는 오류와 함께 그대로 둔다.
  const handleBlur = (e: React.FocusEvent<HTMLInputElement>) => {
    const n = parseInteger(raw);
    if (n !== null) {
      const capped = max === undefined ? n : Math.min(n, max);
      const final = min === undefined ? capped : Math.max(capped, min);
      if (final !== value) onValueChange(final);
      if (final !== n) setRaw(String(final));
    }
    onBlur?.(e);
  };

  const describedByIds = [describedBy, syntaxError ? messageId : null].filter(Boolean).join(' ');

  return (
    <div className={cn('space-y-1', wrapperClassName)}>
      <Input
        {...rest}
        type="text"
        inputMode="numeric"
        value={raw}
        onChange={handleChange}
        onBlur={handleBlur}
        aria-describedby={describedByIds || undefined}
        aria-invalid={syntaxError || ariaInvalid || undefined}
      />
      {syntaxError && (
        <p id={messageId} className="text-sm text-destructive">
          정수를 입력하세요
        </p>
      )}
    </div>
  );
}
