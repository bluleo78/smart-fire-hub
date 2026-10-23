import type { OwnerIneligibleReason } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';

/**
 * OwnerIneligibleBadge — 작성자 실행 자격이 없는 스마트 작업 표시 (WD-192).
 *
 * 작성자가 정지·제거되거나 스마트 작업 쓰기 권한을 잃으면 작업은 「활성」인 채 실행 때마다 건너뛴다(WD-166 — 권한을 다시 받으면
 * 저절로 재개되도록 자동으로 끄지 않는다). 예전엔 목록·상세가 「활성 · 다음 실행 …」으로 정상처럼 보여 아무도 알아채지 못했으므로,
 * 서버가 실은 ownerEligible=false 를 같은 문구로 내 작업 목록·상세·워크스페이스 전체 보기에 보인다.
 *
 * - inline: 목록 작업명 아래 보조 줄(「이상 탐지 미동작」과 같은 글자 스타일)
 * - badge: 상세 헤더·관리자 표의 상태 배지(「활성/비활성」 배지 옆)
 */
const LABELS: Record<OwnerIneligibleReason, string> = {
  OWNER_NO_PERMISSION: '작성자 권한 없음 — 실행되지 않음',
  OWNER_INACTIVE: '작성자 정지·제거 — 실행되지 않음',
};

const HINTS: Record<OwnerIneligibleReason, string> = {
  OWNER_NO_PERMISSION:
    '작성자에게 스마트 작업 쓰기 권한이 없어 예약·이상 탐지 실행을 건너뜁니다. 권한을 다시 받으면 저절로 다시 실행됩니다. 필요 없으면 끄거나 삭제하세요.',
  OWNER_INACTIVE:
    '작성자가 정지됐거나 워크스페이스에서 제거돼 실행을 건너뜁니다. 다시 활성화되면 저절로 다시 실행됩니다. 필요 없으면 끄거나 삭제하세요.',
};

/** 사유 코드 → 화면 문구. 모르는 코드(향후 추가)는 권한 없음 문구로 보인다. */
function ownerIneligibleLabel(reason: OwnerIneligibleReason | null | undefined): string {
  return LABELS[reason ?? 'OWNER_NO_PERMISSION'] ?? LABELS.OWNER_NO_PERMISSION;
}

interface OwnerIneligibleBadgeProps {
  reason: OwnerIneligibleReason | null | undefined;
  variant: 'inline' | 'badge';
  testId?: string;
}

export function OwnerIneligibleBadge({ reason, variant, testId = 'owner-ineligible' }: OwnerIneligibleBadgeProps) {
  const label = ownerIneligibleLabel(reason);
  const hint = HINTS[reason ?? 'OWNER_NO_PERMISSION'] ?? HINTS.OWNER_NO_PERMISSION;
  if (variant === 'inline') {
    return (
      <span className="text-xs text-destructive font-medium" title={hint} data-testid={testId}>
        {label}
      </span>
    );
  }
  return (
    <Badge variant="warning" title={hint} data-testid={testId}>
      {label}
    </Badge>
  );
}
