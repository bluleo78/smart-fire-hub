import { ArrowLeft } from 'lucide-react';

import { Button } from '@/components/ui/button';

/**
 * 안내 화면(찾을 수 없음·잘못된 요청·등급 차단·권한 없음) 아래의 공용 「돌아가기」 버튼(WD-652·WD-661).
 * 실행 상세와 리포트 보기가 같은 버튼을 써 모양이 어긋나지 않게 한다.
 * 뒤로 가기 동작은 화면이 useGoBack 으로 한 번 만들어 넘긴다(WD-667) — 버튼마다 같은 대체 경로로 훅을 다시 부르면
 * 화면의 대체 경로가 바뀔 때(리포트 보기: 작업을 읽을 수 없으면 리포트 목록) 버튼만 옛 경로로 남을 수 있다.
 */
export function BackButton({
  onBack,
  disabled,
}: {
  onBack: () => void;
  /** 목적지를 확인하는 동안 잠근다(리포트 보기의 작업 확인, WD-667) */
  disabled?: boolean;
}) {
  return (
    <Button variant="ghost" size="sm" onClick={onBack} disabled={disabled}>
      <ArrowLeft className="h-4 w-4" />
      돌아가기
    </Button>
  );
}
