import { Search, X } from 'lucide-react';
import { type ChangeEvent, type CompositionEvent, useEffect, useRef, useState } from 'react';

import { cn } from '@/lib/utils';

import { Input } from './input';

interface SearchInputProps {
  placeholder?: string;
  /** 스크린리더용 접근 가능 이름. 미지정 시 placeholder를 대신 사용 */
  'aria-label'?: string;
  value: string;
  onChange: (value: string) => void;
  className?: string;
  /**
   * 입력이 이만큼(ms) 멈춘 뒤에 onChange 를 한 번만 부른다(WD-513). 생략하면 매 입력마다 즉시 부른다.
   * 목록 검색처럼 onChange 가 서버 요청·페이지 초기화·URL 갱신으로 이어지는 곳에서 쓴다 —
   * 검색어만 따로 늦추면 페이지·탭 초기화가 옛 검색어로 요청을 한 번 더 보내므로, 부모에 전달하는 시점 자체를 늦춘다.
   * 지우기(X)는 기다리지 않고 바로 전달한다.
   */
  debounceMs?: number;
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
export function SearchInput({ placeholder = '검색...', 'aria-label': ariaLabel, value, onChange, className, debounceMs }: SearchInputProps) {
  // 조합 중 표시되는 임시 글자를 담는 내부 상태. 부모 value와 분리해 IME 끊김을 방지한다.
  const [draft, setDraft] = useState(value);
  // 직전 렌더에서 받은 외부 value. 외부 value 변동을 렌더 중에 감지하기 위한 비교 기준.
  const [lastExternal, setLastExternal] = useState(value);
  // 한글 IME 조합 진행 여부.
  const [isComposing, setIsComposing] = useState(false);

  // 늦춰 둔 onChange 타이머(WD-513). 새 입력·지우기·외부 값 변경·언마운트 때 취소한다.
  const pendingTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelPending = () => {
    if (pendingTimer.current !== null) {
      clearTimeout(pendingTimer.current);
      pendingTimer.current = null;
    }
  };
  useEffect(() => cancelPending, []);

  // 외부 value 변경을 draft 에 즉시 반영(렌더 중 setState — React 권장 패턴).
  // 조합 중에는 무시해 자모 분리를 방지한다.
  if (!isComposing && value !== lastExternal) {
    setLastExternal(value);
    setDraft(value);
  }
  // 부모가 값을 바꿨으면(탭 전환으로 검색 초기화 등) 늦춰 둔 옛 입력이 뒤늦게 덮어쓰지 않게 취소한다(WD-513).
  // 렌더 중에는 ref 를 바꾸지 않도록 effect 에서 한다.
  useEffect(() => {
    cancelPending();
  }, [value]);

  /** 부모에 값을 전달한다 — debounceMs 가 있으면 입력이 멈춘 뒤 마지막 값으로 한 번만 */
  const emit = (next: string) => {
    cancelPending();
    if (!debounceMs) {
      onChange(next);
      return;
    }
    pendingTimer.current = setTimeout(() => {
      pendingTimer.current = null;
      onChange(next);
    }, debounceMs);
  };

  const hasValue = draft.length > 0;

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setDraft(next);
    // 조합 중이면 onChange 발화 보류 — compositionend 에서 최종값을 한 번에 전달한다.
    if (!isComposing) {
      emit(next);
    }
  };

  const handleCompositionStart = () => {
    setIsComposing(true);
  };

  const handleCompositionEnd = (e: CompositionEvent<HTMLInputElement>) => {
    setIsComposing(false);
    const next = e.currentTarget.value;
    setDraft(next);
    emit(next);
  };

  const handleClear = () => {
    // 지우기는 사용자가 결과를 바로 되돌리려는 동작이라 기다리지 않는다(늦춰 둔 입력도 취소)
    cancelPending();
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
