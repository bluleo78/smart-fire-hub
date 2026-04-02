import { X } from 'lucide-react';
import { type KeyboardEvent,useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface EmailTagInputProps {
  emails: string[];
  onChange: (emails: string[]) => void;
  disabled?: boolean;
}

export default function EmailTagInput({ emails, onChange, disabled }: EmailTagInputProps) {
  const [inputValue, setInputValue] = useState('');
  const [error, setError] = useState('');

  const addEmail = (raw: string) => {
    const email = raw.trim();
    if (!email) return;
    if (!EMAIL_RE.test(email)) {
      setError('올바른 이메일 형식이 아닙니다');
      return;
    }
    if (emails.includes(email)) {
      setError('이미 추가된 이메일입니다');
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

  const handleRemove = (email: string) => {
    onChange(emails.filter((e) => e !== email));
  };

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-1.5 min-h-8">
        {emails.map((email) => (
          <Badge key={email} variant="secondary" className="flex items-center gap-1 text-xs">
            {email}
            {!disabled && (
              <button
                type="button"
                onClick={() => handleRemove(email)}
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
