import * as React from 'react';

import { Input } from '@/components/ui/input';
import { useIntegerDraftStore } from '@/hooks/useIntegerDrafts';
import { useReportValidity } from '@/hooks/useReportValidity';
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
  /**
   * 하한 — 치는 도중 하한 미만 값은 부모에 커밋하지 않고 칸 오류(「N 이상 입력하세요」)로 알리며,
   * 칸을 떠날 때 하한으로 올려 맞춘다 (`5` → 하한으로 튀어 `50` 을 못 치는 것 방지)
   */
  min?: number;
  /**
   * 칸이 유효하지 않게 되거나(표기 오류·하한 미만) 다시 유효해질 때만 호출된다 (WD-906 후속).
   * 오류가 보이는 채 부모가 저장되는 일을 막도록, 부모는 이 신호로 저장 경로를 잠근다.
   * 칸이 사라질 때(언마운트)도 유효(true)로 되돌려 저장이 영영 막히지 않게 한다.
   */
  onValidityChange?: (valid: boolean) => void;
  /**
   * 무효 원문을 맡길 키 (WD-906 후속 반려 수정). 가까운 IntegerDraftContext 가 있으면 무효인 동안 원문을 거기 맡기고,
   * 칸이 언마운트됐다 다시 마운트되면 맡긴 원문(예: 1e)으로 시작해 오류를 다시 보인다. 언마운트해도 맡긴 원문은 지우지 않는다 —
   * 보이지 않는 오류 칸 때문에 저장이 계속 막혀야 하기 때문이다(옛 값이 말없이 저장되는 것 방지).
   */
  draftKey?: string;
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
 * min/max 는 선택이다. 하한 미만도 표기 오류와 같은 「유효하지 않음」으로 보고 커밋하지 않으며,
 * 유효성 변화는 onValidityChange 로 부모에 알린다(저장 차단용).
 */
export function IntegerTextInput({
  value,
  onValueChange,
  onValidityChange,
  draftKey,
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
  const draftStore = useIntegerDraftStore();
  // 맡겨 둔 무효 원문이 있으면 그것으로 시작한다(다시 마운트된 오류 칸이 원문과 오류를 되살린다)
  const [raw, setRaw] = React.useState(() => (draftKey ? draftStore?.get(draftKey) : undefined) ?? String(value));
  const [prevValue, setPrevValue] = React.useState(value);

  // 부모 value 가 바뀌었고 지금 원문이 그 값을 가리키지 않으면(외부 변경·초기화) 원문을 맞춘다(오류는 원문에서 계산되므로 따라 지워진다).
  // 사용자가 친 `007` 처럼 같은 값을 가리키는 원문은 그대로 둬 입력 중 칸이 튀지 않게 한다.
  if (value !== prevValue) {
    setPrevValue(value);
    if (parseInteger(raw) !== value) {
      setRaw(String(value));
    }
  }

  // 상한만 적용한 값 — 치는 도중에도 적용한다
  const capMax = (n: number) => (max === undefined ? n : Math.min(n, max));

  // 오류 종류를 원문에서 계산한다: 정수 표기가 아니면 'syntax', 상한 적용 뒤에도 하한 미만(이고 부모 값과 다름)이면 'min'.
  // 하한 미만은 커밋하지 않고 오류로 둔다 — 부모 상태에 범위 밖 값이 들어가 저장되는 것을 막는다.
  // 부모 값과 같으면 오류로 보지 않는다(저장돼 있던 값이 하한보다 작아도 칸을 건드리기 전엔 오류를 띄우지 않는다).
  const parsed = parseInteger(raw);
  const error: 'syntax' | 'min' | null =
    parsed === null
      ? 'syntax'
      : min !== undefined && capMax(parsed) < min && capMax(parsed) !== value
        ? 'min'
        : null;
  const invalid = error !== null;

  // 유효성이 바뀔 때만(그리고 사라질 때 유효로 되돌려) 부모에 알린다
  useReportValidity(!invalid, onValidityChange);

  // 무효인 동안 원문을 보관소에 맡기고, 유효해지면 지운다. 언마운트 정리는 일부러 하지 않는다(위 draftKey 설명).
  React.useEffect(() => {
    if (!draftKey || !draftStore) return;
    draftStore.set(draftKey, invalid ? raw : null);
  }, [draftStore, draftKey, invalid, raw]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setRaw(next);
    const n = parseInteger(next);
    // 표기 오류는 커밋하지 않는다(오류 표시는 원문에서 계산). 치는 도중에는 상한만 적용하고 하한 미만도 커밋하지 않는다.
    if (n === null) return;
    const capped = capMax(n);
    if (min !== undefined && capped < min) return;
    if (capped !== value) onValueChange(capped);
  };

  // 포커스를 잃을 때: 유효한 원문이면 하한까지 적용한 최종 값을 커밋하고(같으면 갱신하지 않는다),
  // 칸 글자도 그 값으로 맞춘다(상한 10 에 `100` → `10`). 잘못된 표기는 오류와 함께 그대로 둔다.
  const handleBlur = (e: React.FocusEvent<HTMLInputElement>) => {
    const n = parseInteger(raw);
    if (n !== null) {
      const capped = capMax(n);
      const final = min === undefined ? capped : Math.max(capped, min);
      if (final !== value) onValueChange(final);
      if (final !== n) setRaw(String(final));
    }
    onBlur?.(e);
  };

  const describedByIds = [describedBy, invalid ? messageId : null].filter(Boolean).join(' ');

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
        aria-invalid={invalid || ariaInvalid || undefined}
        data-integer-invalid={invalid || undefined}
      />
      {invalid && (
        <p id={messageId} className="text-sm text-destructive">
          {error === 'syntax' ? '정수를 입력하세요' : `${min} 이상 입력하세요`}
        </p>
      )}
    </div>
  );
}
