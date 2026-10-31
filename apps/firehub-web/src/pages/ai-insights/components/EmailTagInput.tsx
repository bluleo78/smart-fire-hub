import { X } from 'lucide-react';
import { type KeyboardEvent,useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { normalizeEmail } from '@/lib/email-recipients';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface EmailTagInputProps {
  emails: string[];
  onChange: (emails: string[]) => void;
  disabled?: boolean;
  /** 「등록 사용자」로 이미 고른 사람의 주소 — 같은 사람에게 리포트가 두 번 가지 않게 외부 이메일로 다시 넣는 것을 막는다(WD-299) */
  registeredEmails?: string[];
}

export default function EmailTagInput({
  emails,
  onChange,
  disabled,
  registeredEmails = [],
}: EmailTagInputProps) {
  const [inputValue, setInputValue] = useState('');
  const [error, setError] = useState('');

  const addEmail = (raw: string) => {
    const email = raw.trim();
    if (!email) return;
    if (!EMAIL_RE.test(email)) {
      setError('올바른 이메일 형식이 아닙니다');
      return;
    }
    // 대소문자만 다른 주소도 같은 받는 사람이다(WD-299)
    const key = normalizeEmail(email);
    if (emails.some((e) => normalizeEmail(e) === key)) {
      setError('이미 추가된 이메일입니다');
      return;
    }
    if (registeredEmails.some((e) => normalizeEmail(e) === key)) {
      setError('이미 수신자에 있습니다 — 등록 사용자로 선택된 주소입니다');
      return;
    }
    onChange([...emails, email]);
    setInputValue('');
    setError('');
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      addEmail(inputValue);
    }
  };

  // 위치로 뺀다 — 고치기 전에 같은 주소가 두 번 저장된 작업에서도 하나씩 뺄 수 있게(WD-299)
  const handleRemove = (index: number) => {
    onChange(emails.filter((_, i) => i !== index));
  };

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-1.5 min-h-8">
        {emails.map((email, index) => (
          <Badge key={`${index}-${email}`} variant="secondary" className="flex items-center gap-1 text-xs">
            {email}
            {!disabled && (
              <button
                type="button"
                onClick={() => handleRemove(index)}
                className="ml-0.5 rounded-full hover:bg-muted"
                aria-label={`${email} 제거`}
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </Badge>
        ))}
      </div>
      {!disabled && (
        <>
          <Input
            value={inputValue}
            onChange={(e) => {
              setInputValue(e.target.value);
              if (error) setError('');
            }}
            onKeyDown={handleKeyDown}
            onBlur={() => addEmail(inputValue)}
            placeholder="이메일 입력 후 Enter"
            className={error ? 'border-destructive' : ''}
          />
          {error && <p className="text-xs text-destructive">{error}</p>}
          <p className="text-xs text-muted-foreground">Enter 또는 쉼표로 추가</p>
        </>
      )}
    </div>
  );
}
