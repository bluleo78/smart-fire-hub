import { Table2 } from 'lucide-react';

import { cn } from '../../lib/utils';
import { Button } from '../ui/button';

/**
 * 차트 「표로 보기」 토글 버튼(WD-832) — 대시보드 위젯·차트 빌더 미리보기가 같이 쓴다.
 * 라벨은 켜짐·꺼짐과 무관하게 「표로 보기」로 고정하고 상태는 aria-pressed 로만 알린다
 * (라벨이 바뀌면 스크린리더가 이름과 상태를 이중으로 읽는다). 눌림 상태는 선택 색(bg-accent)으로도 보인다.
 */
export function ChartTableToggle({
  pressed,
  onPressedChange,
}: {
  pressed: boolean;
  onPressedChange: (next: boolean) => void;
}) {
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-xs"
      className={cn('-my-1 shrink-0 text-muted-foreground print:hidden', pressed && 'bg-accent text-foreground')}
      aria-label="표로 보기"
      title="표로 보기"
      aria-pressed={pressed}
      // `-my-1`: 버튼은 24px(WCAG 2.5.8)을 지키되 세로 여백을 빼 머리 줄 높이를 제목 줄(16px)과 같게 둔다 — 안 그러면 1행 위젯의 본문 높이가 4px 줄어 그림이 사라진다
      // 머리글 끌기(react-grid-layout)로 눌림이 새지 않게 — 편집 모드에서도 클릭만 처리한다
      onClick={(e) => {
        e.stopPropagation();
        onPressedChange(!pressed);
      }}
    >
      <Table2 aria-hidden="true" />
    </Button>
  );
}
