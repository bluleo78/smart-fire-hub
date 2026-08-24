import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';

import type { SettingDiff } from './build-payload';

interface SaveConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  diff: SettingDiff[];
  onConfirm: () => void;
}

/**
 * 저장 직전 확인. 상시 배너 대신 여기에 실질 방어를 둔다(D-4) — 항상 켜진 경고는 배경이 되어
 * 읽히지 않고, 정작 파괴적인 순간(테넌트 정지)의 destructive 색과 경쟁해 그 신호까지 희석한다.
 *
 * 비밀 키의 이전/새 값은 **절대 표시하지 않는다**.
 */
export function SaveConfirmDialog({ open, onOpenChange, diff, onConfirm }: SaveConfirmDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>플랫폼 기본값 저장</AlertDialogTitle>
          <AlertDialogDescription>
            {`${diff.length}개 항목을 변경합니다. 이 값은 전 테넌트에 적용되며, 해당 항목을 재정의하지 않은 모든 워크스페이스가 즉시 영향을 받습니다.`}
          </AlertDialogDescription>
        </AlertDialogHeader>

        <ul className="max-h-64 space-y-1 overflow-y-auto text-sm">
          {diff.map((d) => (
            <li key={d.key} className="flex flex-wrap items-baseline gap-1">
              {/* ai.api_key/embedding.api_key 처럼 카탈로그 라벨이 우연히 같은 키가 있어
                  탭 그룹을 함께 보여준다(리뷰 L1) — 그래야 바이트까지 같은 두 줄이 안 생긴다. */}
              <span className="text-xs text-muted-foreground">{d.group} ·</span>
              <span className="font-medium">{d.label}</span>
              <span className="text-muted-foreground">
                {d.secret ? d.after : `${d.before || '(비어 있음)'} → ${d.after || '(비어 있음)'}`}
              </span>
            </li>
          ))}
        </ul>

        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm}>저장</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
