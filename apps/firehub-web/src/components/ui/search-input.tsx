import { Search, X } from 'lucide-react';
import { type ChangeEvent, type CompositionEvent,useState } from 'react';

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

/**
 * 검색 입력 컴포넌트.
 *
 * 한글 IME 조합(composition) 처리:
 * - 부모(예: URL searchParams 동기화)에서 매 입력마다 `value`가 round-trip 되면
 *   조합 중인 한글의 임시 글자가 외부 값으로 덮어써져 자모가 분리되는 버그가 있다.
 * - 이를 막기 위해 컴포넌트 내부에 표시용 `draft` 상태를 두고,
 *   조합 중에는 부모로 onChange를 보내지 않으며 외부 value도 무시한다.
 * - 조합이 끝나면(`compositionend`) 최종 글자를 한 번에 부모에 전달한다.
 * - IME를 쓰지 않는 영문/숫자 입력은 기존과 동일하게 매 입력마다 즉시 전달된다.
 */
export function SearchInput({ placeholder = '검색...', 'aria-label': ariaLabel, value, onChange, className }: SearchInputProps) {
  // 조합 중 표시되는 임시 글자를 담는 내부 상태. 부모 value와 분리해 IME 끊김을 방지한다.
  const [draft, setDraft] = useState(value);
  // 직전 렌더에서 받은 외부 value. 외부 value 변동을 렌더 중에 감지하기 위한 비교 기준.
  const [lastExternal, setLastExternal] = useState(value);
  // 한글 IME 조합 진행 여부.
  const [isComposing, setIsComposing] = useState(false);

  // 외부 value 변경을 draft 에 즉시 반영(렌더 중 setState — React 권장 패턴).
  // 조합 중에는 무시해 자모 분리를 방지한다.
  if (!isComposing && value !== lastExternal) {
    setLastExternal(value);
    setDraft(value);
  }

  const hasValue = draft.length > 0;

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setDraft(next);
    // 조합 중이면 onChange 발화 보류 — compositionend 에서 최종값을 한 번에 전달한다.
    if (!isComposing) {
      onChange(next);
    }
  };

  const handleCompositionStart = () => {
    setIsComposing(true);
  };

  const handleCompositionEnd = (e: CompositionEvent<HTMLInputElement>) => {
    setIsComposing(false);
    const next = e.currentTarget.value;
    setDraft(next);
    onChange(next);
  };

  const handleClear = () => {
    setDraft('');
    onChange('');
  };

  return (
    <div className={cn('relative flex-1 max-w-sm', className)}>
      <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
      <Input
        placeholder={placeholder}
        // aria-label: 스크린리더에서 입력 목적을 인식할 수 있도록 aria-label 전달.
        // placeholder는 VoiceOver/NVDA에서 accessible name으로 처리되지 않으므로 명시 필요.
        aria-label={ariaLabel ?? placeholder}
        value={draft}
        onChange={handleChange}
        onCompositionStart={handleCompositionStart}
        onCompositionEnd={handleCompositionEnd}
        className={cn('pl-9', hasValue && 'pr-9')}
      />
      {hasValue && (
        <button
          type="button"
          onClick={handleClear}
          aria-label="검색어 지우기"
          className="absolute right-2 top-1/2 -translate-y-1/2 inline-flex h-6 w-6 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      )}
    </div>
  );
}
