// 출처: apps/firehub-web/src/components/ui/form-field.tsx 에서 복사(P7-c2a, R-2).
// 공유 패키지로 추출하지 않는 것이 확정 결정이다. 원본을 고칠 일이 생기면 양쪽을 함께 본다.
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
}

export function FormField({ label, htmlFor, error, required, children, className }: FormFieldProps) {
  return (
    <div className={cn('space-y-2', className)}>
      <Label htmlFor={htmlFor}>
        {label}
        {required && <span className="text-destructive ml-0.5">*</span>}
      </Label>
      {children}
      {error && (
        <p className="text-sm text-destructive">{error}</p>
      )}
    </div>
  );
}
