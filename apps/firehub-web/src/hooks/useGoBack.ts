import { useLocation, useNavigate } from 'react-router-dom';

import { hasInAppHistory } from '@/lib/app-history';

/**
 * 이 탭에서 지금 위치 뒤로 앱 안 기록이 한 칸이라도 있는가(WD-661·WD-667).
 * NavigationHistoryTracker 가 PUSH 이동을 기록으로 센다 — 로그인 리다이렉트(replace)만 거친 첫 화면은 「없음」이다.
 * 판정 규칙과 한계(상태 없는 기록 칸으로 POP 하면 「없음」)는 lib/app-history 참고.
 */
export function useHasAppHistory(): boolean {
  // 위치가 바뀔 때마다 다시 그려 판정을 새로 읽는다
  const location = useLocation();
  return hasInAppHistory(location.key);
}

/**
 * 뒤로 가기 — 이 탭의 첫 화면(새 탭·이메일 링크·붙여 넣은 주소)이면 돌아갈 기록이 없다(WD-347·WD-652).
 * 그때 navigate(-1) 은 무반응이거나 앱 밖(about:blank)으로 나가므로 fallback 으로 보낸다.
 * hasHistory 는 버튼 이름을 「뒤로」/목적지 이름으로 바꿔 어디로 가는지 알릴 때 쓴다.
 */
export function useGoBack(fallback: string) {
  const navigate = useNavigate();
  const hasHistory = useHasAppHistory();
  const goBack = () => (hasHistory ? navigate(-1) : navigate(fallback));
  return { hasHistory, goBack };
}
