import { AlertTriangle, Archive } from 'lucide-react';

import { Button } from '@/components/ui/button';
import { InlineBanner } from '@/components/ui/inline-banner';
import { useOntologyStatusTransition } from '@/hooks/queries/useOntology';
import { useAuth } from '@/hooks/useAuth';
import type { OntologySummary } from '@/types/ontology';

interface OntologyStatusBannerProps {
  ontology: OntologySummary;
}

/**
 * 비-active 온톨로지의 상태 안내 배너.
 * 툴바에 버튼을 얹는 대신 배너로 둔 이유: draft·archived는 버튼이 아니라 설명이 필요한 상태이고,
 * 툴바에 조건부 버튼을 얹으면 선택에 따라 컨트롤 개수가 흔들려 레이아웃이 재배치된다.
 * 배너의 설명 문구는 비-ADMIN에게도 유용하므로("왜 데이터셋에 연결할 수 없는지") 그대로 노출하되,
 * 상태 전이 액션(활성화/복귀)은 ADMIN 전용 — 스펙상 비-ADMIN에게 배너 액션을 노출하지 않는다.
 */
export default function OntologyStatusBanner({ ontology }: OntologyStatusBannerProps) {
  const { isAdmin } = useAuth();
  const { transition, isPending } = useOntologyStatusTransition();

  // active는 정상 상태 — 배너를 띄우지 않는다.
  if (ontology.status === 'active') return null;

  // draft→활성화, archived→복귀. 목적지는 둘 다 active이고 라벨만 다르다.
  const isDraft = ontology.status === 'draft';
  const actionLabel = isDraft ? '활성화' : '복귀';

  // 엔티티 0개 활성화는 400 — 서버 메시지("엔티티 타입은 최소 1개…")를 실패 문구로 그대로 보여준다.
  const run = () =>
    transition({
      id: ontology.id,
      status: 'active',
      successMessage: `온톨로지가 ${actionLabel}되었습니다.`,
      failureMessage: `온톨로지 ${actionLabel}에 실패했습니다.`,
    });

  return (
    <InlineBanner
      data-testid="ontology-status-banner"
      variant={isDraft ? 'warning' : 'info'}
      icon={isDraft ? <AlertTriangle /> : <Archive />}
      className="mb-4"
      actions={
        isAdmin ? (
          <Button size="sm" onClick={run} disabled={isPending}>
            {actionLabel}
          </Button>
        ) : undefined
      }
    >
      {isDraft
        ? '이 온톨로지는 초안입니다. 데이터셋에 바인딩할 수 없습니다.'
        : '은퇴한 온톨로지입니다. 신규 바인딩은 할 수 없고, 기존 적재 데이터는 그대로 조회됩니다.'}
    </InlineBanner>
  );
}
