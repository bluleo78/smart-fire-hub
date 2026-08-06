import { Diamond } from 'lucide-react';

import { Button } from '@/components/ui/button';

interface OntologyEmptyStateProps {
  /** 편집기를 연다. 비-ADMIN에게는 넘기지 않아 CTA가 숨는다. */
  onDefine?: () => void;
}

/**
 * 엔티티 타입이 0개일 때 스키마 캔버스 자리에 놓이는 빈 상태.
 * 새로 만든 온톨로지뿐 아니라 AI 챗이 만든 초안을 열었을 때도 같은 화면을 마주친다.
 * 클래스는 06-feedback-states.md §B EmptyState 명세를 그대로 따른다.
 */
export default function OntologyEmptyState({ onDefine }: OntologyEmptyStateProps) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 py-16 text-center">
      <Diamond className="h-10 w-10 text-muted-foreground" />
      <p className="text-sm font-medium">아직 엔티티 타입이 없습니다</p>
      <p className="text-xs text-muted-foreground">
        엔티티 타입과 관계를 정의하면 여기에 스키마 그래프가 그려집니다.
      </p>
      {onDefine && <Button onClick={onDefine}>엔티티 타입 정의하기</Button>}
    </div>
  );
}
