import { useLocation, useNavigate } from 'react-router-dom';

/**
 * 뒤로 가기 — 이 탭의 첫 화면(새 탭·이메일 링크·붙여 넣은 주소)이면 돌아갈 기록이 없다(WD-347·WD-652).
 * React Router 는 첫 진입 위치의 key 를 'default' 로 둔다. 그때 navigate(-1) 은 무반응이거나 앱 밖(about:blank)으로
 * 나가므로 fallback 목록으로 보낸다. hasHistory 는 버튼 이름을 「뒤로」/「목록」으로 바꿔 어디로 가는지 알릴 때 쓴다.
 */
export function useGoBack(fallback: string) {
  const navigate = useNavigate();
  const location = useLocation();
  const hasHistory = location.key !== 'default';
  const goBack = () => (hasHistory ? navigate(-1) : navigate(fallback));
  return { hasHistory, goBack };
}
