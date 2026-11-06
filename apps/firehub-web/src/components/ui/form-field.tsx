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
}

/** 입력 요소에 덧붙일 접근성 속성 — FormField 가 오류를 그릴 때만 채운다. */
type AriaInputProps = {
  id?: string;
  'aria-invalid'?: boolean | 'true' | 'false';
  'aria-describedby'?: string;
};

/**
 * 오류 문구 id 를 입력의 aria-describedby 에 이어 붙인다(이미 있는 설명 id 는 지킨다).
 * aria-invalid 는 화면이 직접 정했으면(예: 중복 이름) 그대로 두고, 정하지 않았으면 오류가 있으니 true 로 둔다.
 */
function linkErrorToInput(children: ReactNode, htmlFor: string | undefined, errorId: string | undefined): ReactNode {
  // 오류가 없을 때도 늘 Children.map 을 거친다 — 오류가 생기고 사라질 때 자식 key 가 바뀌면 입력이 다시 마운트돼 포커스를 잃는다.
  // 최상위 자식 중 그 입력(id === htmlFor)만 연결한다 — 입력 옆의 안내 문구 등은 그대로 두고,
  // 입력을 div 로 감싼 화면은 여기서 찾지 못하니 화면이 직접 연결해야 한다.
  return Children.map(children, (child) => {
    if (!htmlFor || !errorId || !isValidElement<AriaInputProps>(child) || child.props.id !== htmlFor) return child;
    // 화면이 이미 오류 id 를 넣어 둔 경우(예: 회원 추가 대화상자)는 겹쳐 넣지 않는다.
    const ids = (child.props['aria-describedby'] ?? '').split(' ').filter(Boolean);
    const described = ids.includes(errorId) ? ids.join(' ') : [...ids, errorId].join(' ');
    return cloneElement(child as ReactElement<AriaInputProps>, {
      'aria-describedby': described,
      'aria-invalid': child.props['aria-invalid'] ?? true,
    });
  });
}

/**
 * 라벨 + 입력 + 오류 문구 묶음.
 * 오류가 있으면 문구(id `${htmlFor}-error`)를 role=alert 로 알리고, 입력에 aria-describedby·aria-invalid 로 연결한다(WD-515) —
 * 예전에는 연결을 화면마다 맡겨 대부분 빠져 있어, 스크린 리더가 어느 칸의 오류인지 알 수 없었다.
 */
export function FormField({ label, htmlFor, error, required, children, className, errorAction }: FormFieldProps) {
  const errorId = htmlFor ? `${htmlFor}-error` : undefined;
  // 오류가 있을 때만 입력에 연결할 오류 id 를 넘긴다
  return (
    <div className={cn('space-y-2', className)}>
      <Label htmlFor={htmlFor}>
        {label}
        {required && <span className="text-destructive ml-0.5">*</span>}
      </Label>
      {linkErrorToInput(children, htmlFor, error ? errorId : undefined)}
      {error && (
        <p id={errorId} role="alert" className="text-sm text-destructive">
          {error}
          {errorAction && <> {errorAction}</>}
        </p>
      )}
    </div>
  );
}
