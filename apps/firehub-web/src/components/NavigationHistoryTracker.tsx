import { useLocation, useNavigationType } from 'react-router-dom';

import { recordNavigation } from '@/lib/app-history';

/**
 * 라우터 위치가 바뀔 때마다 앱 안 이동 기록을 남긴다 (WD-667, useGoBack 의 「돌아갈 기록」 판정용).
 *
 * BrowserRouter 바로 아래, 인증·라우트보다 먼저 둔다 — 같은 렌더에서 페이지보다 먼저 기록돼야
 * 첫 PUSH 로 그려진 페이지가 자기 렌더에서 「기록 있음」을 읽는다(useEffect 로 기록하면 한 박자 늦어
 * 그 페이지의 버튼 이름이 대체 목적지로 남는다). 인증 로딩 중에도 이동을 놓치지 않게 AuthProvider 밖에 둔다.
 * 기록은 key 기준으로 멱등이라 렌더 중에 불러도 안전하다.
 */
export function NavigationHistoryTracker() {
  const location = useLocation();
  const navigationType = useNavigationType();
  recordNavigation(location.key, navigationType);
  return null;
}
