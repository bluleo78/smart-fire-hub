import { Lock, ShieldCheck } from 'lucide-react';

import { cn } from '@/lib/utils';

import { levelTone } from '../../lib/security-level';
import type { SecurityLevelSummary } from '../../types/security-level';
import { Badge } from './badge';

/**
 * 보안 등급 배지 — 아이콘+이름 항상 병기(색만으로 의미 전달 X, 스펙 §5).
 * 허용 목록 등급은 Lock, 그 외는 ShieldCheck.
 */
export function SecurityLevelBadge({ level, className }: { level: SecurityLevelSummary; className?: string }) {
  const tone = levelTone(level);
  const Icon = tone === 'caution' ? Lock : ShieldCheck;
  return (
    <Badge variant={tone} className={cn(className)} data-testid="security-level-badge" data-tone={tone}>
      <Icon aria-hidden="true" />
      {level.name}
    </Badge>
  );
}
