import { useCallback, useState } from 'react';

/**
 * 「유효 여부 콜백(onValidityChange)」을 받아 뒤집은 「무효 플래그」로 들고 있는 훅 (WD-906 후속).
 * 반환 [invalid, onValidityChange] — 콜백은 useCallback 으로 안정적이라 자식 훅의 콜백 ref 갱신을 부추기지 않는다.
 */
export function useInvalidFlag(): [boolean, (valid: boolean) => void] {
  const [invalid, setInvalid] = useState(false);
  const onValidityChange = useCallback((valid: boolean) => setInvalid(!valid), []);
  return [invalid, onValidityChange];
}
