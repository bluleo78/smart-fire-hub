import type { ReactNode } from 'react';

import { Label } from '../../components/ui/label';

/**
 * 설정 라벨 줄(라벨 + 힌트 배지) 공용 부품. 임베딩 잠금 부품은 #713 에서 탭이 편집 가능해지며 제거했다.
 */
export function SettingFieldLabel({
  htmlFor,
  badge,
  children,
}: {
  htmlFor: string;
  badge?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Label htmlFor={htmlFor}>{children}</Label>
      {badge}
    </div>
  );
}
