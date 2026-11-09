import { useLocation, useNavigate } from 'react-router-dom';

import { hasInAppHistory } from '@/lib/app-history';
import { jobBackFallback } from '@/lib/proactive-utils';

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

/**
 * 실행 상세·리포트 보기의 뒤로 가기(WD-671) — 기록이 있으면 navigate(-1), 없으면 jobBackFallback 규칙의 목적지로 보낸다.
 * 두 화면이 같은 규칙을 쓰도록 한곳에 둔다. 버튼은 잠그지 않는다 — 결과를 모르면 언제나 안전한 리포트 목록으로 간다.
 * label 은 기록이 있으면 「뒤로」, 없으면 목적지 이름이다. 조회가 끝나지 않은 동안은 「리포트 목록」으로 그대로 있고,
 * 조회가 성공했을 때 한 번만 「작업 상세」로 바뀐다 — 멈춘 요청 동안 이름이 뒤집히지 않는다.
 * 기록 판정(useHasAppHistory)은 useGoBack 안에서 한 번만 읽는다(WD-671) — 화면이 따로 구독하지 않게.
 */
export function useJobGoBack(jobId: number | null | undefined, jobReadable: boolean) {
  const fallback = jobBackFallback(jobId, jobReadable);
  const { hasHistory, goBack } = useGoBack(fallback.path);
  return { hasHistory, goBack, label: hasHistory ? '뒤로' : fallback.label };
}
