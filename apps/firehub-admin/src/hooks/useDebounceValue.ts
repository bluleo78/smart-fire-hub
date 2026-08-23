// 출처: apps/firehub-web/src/hooks/useDebounceValue.ts 에서 복사(P7-c2a, R-2).
import { useEffect,useState } from 'react';

export function useDebounceValue<T>(value: T, delay: number = 300): T {
  const [debouncedValue, setDebouncedValue] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  return debouncedValue;
}
