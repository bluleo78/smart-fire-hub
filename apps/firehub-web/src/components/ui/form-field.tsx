import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react';

import { cn } from '@/lib/utils';

import { type AriaInputProps, linkedAriaProps, type LinkedIds } from './form-field-aria';
import { Label } from './label';

interface FormFieldProps {
  label: string;
  htmlFor?: string;
  error?: string;
  required?: boolean;
  children: ReactNode;
  className?: string;
  /** 오류 문구 바로 뒤(같은 줄)에 붙일 후속 행동(예: 상세 링크). 오류가 있을 때만 그린다. */
  errorAction?: ReactNode;
  /** 라벨 뒤(같은 줄)에 붙일 요소(예: 「기본값」 배지)(WD-643). 라벨 요소 밖에 그려 접근 가능한 이름에 섞이지 않는다. */
  labelAddon?: ReactNode;
  /** 늘 보이는 설명 문구(WD-643). id `${htmlFor}-desc` 로 그리고 입력의 aria-describedby 에 늘 잇는다. */
  description?: ReactNode;
  /** 상황에 따라 붙는 안내 문구(WD-643, 예: 「현재 공급자에서는 적용되지 않습니다」). 줄 때만 id `${htmlFor}-hint` 로 그리고 잇는다. */
  hint?: ReactNode;
  /**
   * 오류 문구를 role=alert 로 즉시 낭독할지(WD-643, 기본 true — 기존 동작). 여러 칸이 한꺼번에 틀릴 수 있는 폼은
   * false 로 끄고, 대신 첫 오류 칸으로 포커스를 옮겨 연결된 오류 문구를 읽게 한다(경고가 겹쳐 읽히지 않게).
   */
  announceError?: boolean;
}

/**
 * 라벨 줄 — 라벨(필수 표시 포함)과, 줄 때만 라벨 옆 덧붙임(배지 등)을 가로 한 줄(flex)로 감싼다(WD-643).
 * WD-660: FormField 를 쓰지 못하는 칸(설정 화면의 모델 칸 등)도 같은 마크업을 쓰도록 내보낸다.
 * 덧붙임은 라벨 요소 밖에 그려 접근 가능한 이름에 섞이지 않는다. 덧붙임이 없으면 감싸지 않는다 — 기존 호출부 마크업 그대로.
 */
export function FormFieldLabelRow({
  htmlFor,
  required,
  addon,
  children,
}: {
  htmlFor?: string;
  required?: boolean;
  /** 라벨 옆에 붙일 요소. 없으면(undefined 등) 라벨만 그린다. */
  addon?: ReactNode;
  children: ReactNode;
}) {
  const labelEl = (
    <Label htmlFor={htmlFor}>
      {children}
      {required && <span className="text-destructive ml-0.5">*</span>}
    </Label>
  );
  if (!addon) return labelEl;
  return (
    <div className="flex flex-wrap items-center gap-2">
      {labelEl}
      {addon}
    </div>
  );
}

/**
 * 오류·설명·안내 문구 id 를 입력의 aria-describedby 에 이어 붙인다(이미 있는 설명 id 는 지킨다).
 * 규칙(순서·aria-invalid)은 {@link linkedAriaProps} 하나에 둔다. 설명·안내가 없는 기존 호출부는
 * 예전과 똑같이 "원래 id 뒤에 오류 id" 가 된다.
 */
function linkDescriptionsToInput(children: ReactNode, htmlFor: string | undefined, linked: LinkedIds): ReactNode {
  // 이을 문구가 없을 때도 늘 Children.map 을 거친다 — 오류가 생기고 사라질 때 자식 key 가 바뀌면 입력이 다시 마운트돼 포커스를 잃는다.
  // 최상위 자식 중 그 입력(id === htmlFor)만 연결한다 — 입력 옆의 안내 문구 등은 그대로 두고,
  // 입력을 div 로 감싼 화면은 여기서 찾지 못하니 화면이 직접 연결해야 한다.
  return Children.map(children, (child) => {
    if (!htmlFor || !isValidElement<AriaInputProps>(child) || child.props.id !== htmlFor) {
      return child;
    }
    // 이을 문구(오류·설명·안내)가 하나도 없으면 원래 자식 그대로 — 화면이 넣은 aria 속성을 건드리지 않는다(WD-660).
    if (!linked.error && !linked.desc && !linked.hint) return child;
    // 화면이 이미 같은 id 를 넣어 둔 경우(예: 회원 추가 대화상자의 오류 id)는 linkedAriaProps 가 겹쳐 넣지 않는다.
    return cloneElement(child as ReactElement<AriaInputProps>, linkedAriaProps(linked, child.props));
  });
}

/**
 * 라벨 + 입력 + 오류 문구 묶음.
 * 오류가 있으면 문구(id `${htmlFor}-error`)를 role=alert 로 알리고, 입력에 aria-describedby·aria-invalid 로 연결한다(WD-515) —
 * 예전에는 연결을 화면마다 맡겨 대부분 빠져 있어, 스크린 리더가 어느 칸의 오류인지 알 수 없었다.
 *
 * WD-643: 선택 prop 을 더했다(모두 생략하면 기존 동작 그대로).
 *  - `labelAddon` — 라벨 옆 배지 등. 줄 때만 라벨과 함께 가로 한 줄(flex)로 감싼다.
 *  - `description`(id `${htmlFor}-desc`, 늘 연결)·`hint`(id `${htmlFor}-hint`, 줄 때만 연결) — 오류 문구 아래에 그린다.
 *  - `announceError={false}` — 오류 문구의 role=alert 를 끈다(여러 칸이 한꺼번에 틀릴 수 있는 폼용).
 */
export function FormField({
  label,
  htmlFor,
  error,
  required,
  children,
  className,
  errorAction,
  labelAddon,
  description,
  hint,
  announceError = true,
}: FormFieldProps) {
  const errorId = htmlFor ? `${htmlFor}-error` : undefined;
  const descId = htmlFor && description ? `${htmlFor}-desc` : undefined;
  const hintId = htmlFor && hint ? `${htmlFor}-hint` : undefined;
  return (
    <div className={cn('space-y-2', className)}>
      {/* 덧붙일 요소가 있을 때만 감싼다 — 기존 호출부의 마크업은 그대로 둔다 */}
      <FormFieldLabelRow htmlFor={htmlFor} required={required} addon={labelAddon}>
        {label}
      </FormFieldLabelRow>
      {/* 오류는 있을 때만 입력에 잇는다. 설명·안내는 그려질 때 잇는다 */}
      {linkDescriptionsToInput(children, htmlFor, { error: error ? errorId : undefined, desc: descId, hint: hintId })}
      {error && (
        <p id={errorId} role={announceError ? 'alert' : undefined} className="text-sm text-destructive">
          {error}
          {errorAction && <> {errorAction}</>}
        </p>
      )}
      {description && (
        <p id={descId} className="text-sm text-muted-foreground">
          {description}
        </p>
      )}
      {hint && (
        <p id={hintId} className="text-sm text-muted-foreground">
          {hint}
        </p>
      )}
    </div>
  );
}
