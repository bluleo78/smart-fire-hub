import { Copy, RefreshCw } from 'lucide-react';
import type { UseFormReturn } from 'react-hook-form';

import { Button } from '@/components/ui/button';
import { FormField } from '@/components/ui/form-field';
import { PasswordInput } from '@/components/ui/password-input';
import { copyText } from '@/lib/clipboard';
import { generateTemporaryPassword, PASSWORD_HELP_TEXT, PASSWORD_MAX_BYTES } from '@/lib/temp-password';
import type { CreateAccountFormData } from '@/lib/validations/account';

interface TemporaryPasswordFieldProps {
  /** 입력·도움말·오류 id 접두어(예: `account` → `account-password`, `account-password-help`, `account-password-error`). */
  idPrefix: string;
  /** 새 계정 폼(`createAccountSchema`)의 react-hook-form 연결. */
  form: Pick<UseFormReturn<CreateAccountFormData>, 'register' | 'setValue' | 'getValues'>;
  /** 임시 비밀번호 칸의 검증 오류 문구. */
  error?: string;
}

/**
 * 새 계정 폼의 임시 비밀번호 줄 — 입력 + 다시 만들기 + 복사 + 규칙 도움말(WD-750 에서 공용으로 뽑음).
 * 운영자 계정 생성(`CreateAccountDialog`)과 테넌트 멤버 추가 「새 계정」 탭이 함께 쓴다 — 접근성 연결(aria)과 길이 상한이 두 곳에서
 * 갈라지지 않게 한다.
 */
export function TemporaryPasswordField({ idPrefix, form, error }: TemporaryPasswordFieldProps) {
  const inputId = `${idPrefix}-password`;
  const helpId = `${inputId}-help`;
  return (
    <FormField label="임시 비밀번호" htmlFor={inputId} error={error} required>
      <div className="flex gap-2">
        <div className="min-w-0 flex-1">
          <PasswordInput
            id={inputId}
            className="font-mono"
            autoComplete="new-password"
            spellCheck={false}
            // maxLength 는 글자 수라 바이트 상한을 다 표현하지 못한다 — 72자로 1차 차단하고 한글 등 다바이트는 스키마가 막는다 (WD-605)
            maxLength={PASSWORD_MAX_BYTES}
            aria-invalid={!!error}
            // 오류 id 는 FormField 가 `${htmlFor}-error` 로 붙인다.
            aria-describedby={error ? `${helpId} ${inputId}-error` : helpId}
            {...form.register('temporaryPassword')}
          />
        </div>
        <Button
          type="button"
          variant="outline"
          onClick={() =>
            form.setValue('temporaryPassword', generateTemporaryPassword(), {
              shouldValidate: true,
              shouldDirty: true,
            })
          }
        >
          <RefreshCw className="h-4 w-4" aria-hidden="true" />
          재생성
        </Button>
        {/* aria-label 에 "임시 비밀번호" 를 넣지 않는다 — 입력란 라벨과 이름이 겹쳐 보조기기에서 구분되지 않는다. */}
        <Button
          type="button"
          variant="outline"
          size="icon"
          aria-label="비밀번호 복사"
          onClick={() => void copyText(form.getValues('temporaryPassword'))}
        >
          <Copy className="h-4 w-4" aria-hidden="true" />
        </Button>
      </div>
      <p id={helpId} className="text-[13px] text-muted-foreground break-keep">
        {PASSWORD_HELP_TEXT}. 열 때마다 자동으로 만들어집니다.
      </p>
    </FormField>
  );
}
