import { useLocation, useNavigate } from 'react-router-dom';

/**
 * 이 탭에서 앱 안 기록이 한 칸이라도 쌓였는가(WD-661).
 * React Router 는 history.state 에 이 탭의 몇 번째 기록인지(idx)를 남긴다 — 첫 화면은 0 이고 push 마다 1 씩 늘며
 * replace 는 그대로 둔다. location.key 로 판정하면 로그인 리다이렉트(replace)만 거쳐도 key 가 바뀌어
 * 기록이 없는데 「있음」으로 오판했다(이메일 링크를 새 탭으로 열고 로그인한 경우).
 */
function hasAppHistory(): boolean {
  const idx = (window.history.state as { idx?: number } | null)?.idx;
  return typeof idx === 'number' && idx > 0;
}

/**
 * 뒤로 가기 — 이 탭의 첫 화면(새 탭·이메일 링크·붙여 넣은 주소)이면 돌아갈 기록이 없다(WD-347·WD-652).
 * 그때 navigate(-1) 은 무반응이거나 앱 밖(about:blank)으로 나가므로 fallback 으로 보낸다.
 * hasHistory 는 버튼 이름을 「뒤로」/목적지 이름으로 바꿔 어디로 가는지 알릴 때 쓴다.
 */
export function useGoBack(fallback: string) {
  const navigate = useNavigate();
  // history.state 는 반응형 값이 아니다 — useLocation 구독으로 이동할 때마다 다시 그려 판정을 새로 읽는다
  useLocation();
  const hasHistory = hasAppHistory();
  const goBack = () => (hasHistory ? navigate(-1) : navigate(fallback));
  return { hasHistory, goBack };
}
