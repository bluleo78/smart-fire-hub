import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react';

import { cn } from '@/lib/utils';

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

/** 입력 요소에 덧붙일 접근성 속성 — FormField 가 오류를 그릴 때만 채운다. */
type AriaInputProps = {
  id?: string;
  'aria-invalid'?: boolean | 'true' | 'false';
  'aria-describedby'?: string;
};

/** 입력에 이을 문구 id — 실제로 그려지는 문구만 담는다(없으면 undefined). */
type LinkedIds = { error?: string; desc?: string; hint?: string };

/**
 * 오류·설명·안내 문구 id 를 입력의 aria-describedby 에 이어 붙인다(이미 있는 설명 id 는 지킨다).
 * 순서: 입력이 원래 가진 id → 오류 → 설명 → 안내(WD-643 — 오류를 먼저 읽게 한다). 설명·안내가 없는 기존 호출부는
 * 예전과 똑같이 "원래 id 뒤에 오류 id" 가 된다.
 * aria-invalid 는 오류가 있을 때만 다룬다 — 화면이 직접 정했으면(예: 중복 이름) 그대로 두고, 정하지 않았으면 true 로 둔다.
 */
function linkDescriptionsToInput(children: ReactNode, htmlFor: string | undefined, linked: LinkedIds): ReactNode {
  const extra = [linked.error, linked.desc, linked.hint].filter((id): id is string => Boolean(id));
  // 이을 문구가 없을 때도 늘 Children.map 을 거친다 — 오류가 생기고 사라질 때 자식 key 가 바뀌면 입력이 다시 마운트돼 포커스를 잃는다.
  // 최상위 자식 중 그 입력(id === htmlFor)만 연결한다 — 입력 옆의 안내 문구 등은 그대로 두고,
  // 입력을 div 로 감싼 화면은 여기서 찾지 못하니 화면이 직접 연결해야 한다.
  return Children.map(children, (child) => {
    if (!htmlFor || extra.length === 0 || !isValidElement<AriaInputProps>(child) || child.props.id !== htmlFor) {
      return child;
    }
    // 화면이 이미 같은 id 를 넣어 둔 경우(예: 회원 추가 대화상자의 오류 id)는 겹쳐 넣지 않는다.
    const ids = (child.props['aria-describedby'] ?? '').split(' ').filter(Boolean);
    const described = [...ids, ...extra.filter((id) => !ids.includes(id))].join(' ');
    return cloneElement(child as ReactElement<AriaInputProps>, {
      'aria-describedby': described,
      ...(linked.error ? { 'aria-invalid': child.props['aria-invalid'] ?? true } : {}),
    });
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
  const labelEl = (
    <Label htmlFor={htmlFor}>
      {label}
      {required && <span className="text-destructive ml-0.5">*</span>}
    </Label>
  );
  return (
    <div className={cn('space-y-2', className)}>
      {/* 덧붙일 요소가 있을 때만 감싼다 — 기존 호출부의 마크업은 그대로 둔다 */}
      {labelAddon ? (
        <div className="flex flex-wrap items-center gap-2">
          {labelEl}
          {labelAddon}
        </div>
      ) : (
        labelEl
      )}
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
