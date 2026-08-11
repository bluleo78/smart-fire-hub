import { Diamond, Plus } from 'lucide-react';

import { Button } from '@/components/ui/button';

interface OntologyEmptyStateProps {
  /** 편집기를 켜고 첫 타입 생성 폼까지 함께 연다(S3 Task 4) — 편집 모드만 켜지면 사용자가 아웃라인의
   * "타입 추가"를 다시 찾아야 하므로, CTA 한 번으로 생성까지 이어지게 한다. 비-ADMIN에게는 넘기지
   * 않아 CTA가 숨는다. */
  onDefine?: () => void;
}

/**
 * 엔티티 타입이 0개일 때 스키마 캔버스 자리에 놓이는 빈 상태.
 * 새로 만든 온톨로지뿐 아니라 AI 챗이 만든 초안을 열었을 때도 같은 화면을 마주친다.
 * 클래스는 06-feedback-states.md §B EmptyState 명세를 그대로 따른다.
 *
 * "AI로 초안 생성" 버튼은 넣지 않는다 — S3 설계 스펙(§빈 상태, §미확정)이 "착수 시 배관을 확인하고
 * 없으면 제외"로 미리 승인한 사항이다. firehub-web에는 ai-agent의 graphrag_infer_ontology 같은 도구를
 * 직접 트리거할 배관이 없고, 유일한 입구는 챗 UI뿐이다(project_ontology_draft_inference 참고). 배관이
 * 생기기 전까지 이 버튼을 넣으면 눌러도 아무 일이 안 일어나거나 엉뚱한 곳(챗 패널)으로 보내야 한다 —
 * 다음 사람이 "빠뜨렸나?" 하고 다시 조사하지 않도록 이 결정을 여기 남긴다.
 */
export default function OntologyEmptyState({ onDefine }: OntologyEmptyStateProps) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3 py-16 text-center">
      <Diamond className="h-10 w-10 text-muted-foreground" />
      <p className="text-sm font-medium">아직 엔티티 타입이 없습니다</p>
      <p className="max-w-sm text-xs text-muted-foreground">
        지식 모델은 무엇을 추출할지(타입)와 어떻게 이어질지(관계)로 이루어집니다.
      </p>
      {/* 예시 트리플 — 타입과 관계가 실제로 어떻게 조합되는지 설명보다 그림 한 줄이 빠르다. */}
      <p className="rounded-md border bg-muted/50 px-3 py-1.5 font-mono text-xs text-muted-foreground">
        Incident → OCCURRED_AT → Building
      </p>
      {onDefine && (
        <Button onClick={onDefine} className="gap-1.5">
          <Plus className="h-4 w-4" />
          첫 타입 만들기
        </Button>
      )}
    </div>
  );
}
