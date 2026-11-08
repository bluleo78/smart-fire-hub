import { ArrowLeft } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { useGoBack } from '@/hooks/useGoBack';

/**
 * 안내 화면(찾을 수 없음·잘못된 요청·등급 차단·권한 없음) 아래의 공용 「돌아가기」 버튼(WD-652·WD-661).
 * 돌아갈 기록이 없으면(새 탭·붙여 넣은 링크·로그인 리다이렉트만 거친 첫 화면) fallback 으로 보낸다 —
 * 실행 상세와 리포트 보기가 같은 버튼을 써 목적지·모양이 어긋나지 않게 한다.
 */
export function BackButton({ fallback }: { fallback: string }) {
  const { goBack } = useGoBack(fallback);
  return (
    <Button variant="ghost" size="sm" onClick={goBack}>
      <ArrowLeft className="h-4 w-4" />
      돌아가기
    </Button>
  );
}
