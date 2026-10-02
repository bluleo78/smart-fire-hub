import type { ReactNode } from 'react';
import { Label } from './label';
import { cn } from '@/lib/utils';

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

/**
 * 라벨 + 입력 + 오류 문구 묶음. 오류 문구 id 는 `${htmlFor}-error` — 입력의 aria-describedby 로 연결할 수 있게 한다.
 */
export function FormField({ label, htmlFor, error, required, children, className, errorAction }: FormFieldProps) {
  return (
    <div className={cn('space-y-2', className)}>
      <Label htmlFor={htmlFor}>
        {label}
        {required && <span className="text-destructive ml-0.5">*</span>}
      </Label>
      {children}
      {error && (
        <p id={htmlFor ? `${htmlFor}-error` : undefined} className="text-sm text-destructive">
          {error}
          {errorAction && <> {errorAction}</>}
        </p>
      )}
    </div>
  );
}
