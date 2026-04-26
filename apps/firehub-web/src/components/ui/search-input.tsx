import { Search, X } from 'lucide-react';

import { cn } from '@/lib/utils';

import { Input } from './input';

interface SearchInputProps {
  placeholder?: string;
  /** 스크린리더용 접근 가능 이름. 미지정 시 placeholder를 대신 사용 */
  'aria-label'?: string;
  value: string;
  onChange: (value: string) => void;
  className?: string;
}

export function SearchInput({ placeholder = '검색...', 'aria-label': ariaLabel, value, onChange, className }: SearchInputProps) {
  // 값이 있을 때만 노출되는 X(clear) 버튼 — 한 번에 검색어를 비울 수 있게 한다.
  // 검색 결과 0건 화면(EmptyState)에서도 동일한 onChange('')로 초기화된다.
  const hasValue = value.length > 0;

  return (
    <div className={cn('relative flex-1 max-w-sm', className)}>
      <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
      <Input
        placeholder={placeholder}
        // aria-label: 스크린리더에서 입력 목적을 인식할 수 있도록 aria-label 전달.
        // placeholder는 VoiceOver/NVDA에서 accessible name으로 처리되지 않으므로 명시 필요.
        aria-label={ariaLabel ?? placeholder}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={cn('pl-9', hasValue && 'pr-9')}
      />
      {hasValue && (
        <button
          type="button"
          onClick={() => onChange('')}
          aria-label="검색어 지우기"
          className="absolute right-2 top-1/2 -translate-y-1/2 inline-flex h-6 w-6 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      )}
    </div>
  );
}
