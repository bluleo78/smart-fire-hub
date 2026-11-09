import { useCallback, useEffect, useRef } from 'react';

/**
 * 컴포넌트가 아직 화면에 붙어 있는지 알려 주는 함수를 돌려준다 (WD-646).
 * 저장·다시 불러오기처럼 응답을 기다리는 핸들러가, 그사이 화면을 떠났으면(다른 id 로 옮겨 다시 만들어진 경우 포함)
 * 토스트·이동·상태 변경을 일으키지 않도록 await 뒤에 확인한다. StrictMode 의 두 번 마운트에도 마지막 마운트 기준으로 true 가 된다.
 */
export function useIsMounted(): () => boolean {
  const mountedRef = useRef(false);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);
  return useCallback(() => mountedRef.current, []);
}
