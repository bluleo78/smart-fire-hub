import { AlertTriangle } from 'lucide-react';

import { InlineBanner } from '@/components/ui/inline-banner';
import { mappingUsageMessage } from '@/lib/ontology-mapping-usage';
import type { ElementMappingUsage } from '@/types/ontology';

interface Props {
  // 문장 주어 — "이 타입은" / "이 속성은" / "이 관계는".
  subject: string;
  // 이 요소의 매핑 사용처. null 이면(사용처 없음·모름) 아무것도 그리지 않는다(일반 삭제 확인 창 그대로).
  usage: ElementMappingUsage | null;
}

/**
 * WD-129: 삭제 확인 창 안의 "데이터셋 매핑에서 사용 중" 안내. 매핑은 요소를 이름으로 참조하므로 지우면 매핑이 깨진다 —
 * 서버가 409 로 막기 전에 확정 단계에서 어느 데이터셋인지 먼저 보여 준다(확인 버튼 비활성화는 호출부가 함께 한다).
 * 디자인 시스템 06 인라인 배너의 destructive variant — 삭제 영향 안내(AnalyticsDeleteConfirmDialog)와 같은 모양.
 */
export default function MappingUsageWarning({ subject, usage }: Props) {
  if (!usage) return null;
  return (
    <InlineBanner
      variant="destructive"
      icon={<AlertTriangle />}
      // break-keep: 한국어 데이터셋 이름이 어절 중간에서 잘리지 않게 한다.
      className="break-keep text-left"
      data-testid="delete-mapping-usage"
    >
      {mappingUsageMessage(subject, usage)}
    </InlineBanner>
  );
}
