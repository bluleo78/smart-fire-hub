import { Lock, ShieldCheck } from 'lucide-react';

import { cn } from '@/lib/utils';

import { levelTone } from '../../lib/security-level';
import type { SecurityLevelSummary } from '../../types/security-level';
import { Badge } from './badge';
import { TruncatedText } from './truncated-text';

/**
 * 보안 등급 배지 — 아이콘+이름 항상 병기(색만으로 의미 전달 X, 스펙 §5).
 * 허용 목록 등급은 Lock, 그 외는 ShieldCheck.
 * WD-650: 등급 이름은 관리자가 정해 길이 제한이 없다. 호출부가 className 으로 max-w 상한을 주면 아이콘은 그대로 두고
 * 이름만 말줄임되며 전체 이름은 title 로 보인다(상한이 없으면 지금처럼 다 보인다).
 */
export function SecurityLevelBadge({ level, className }: { level: SecurityLevelSummary; className?: string }) {
  const tone = levelTone(level);
  const Icon = tone === 'caution' ? Lock : ShieldCheck;
  return (
    <Badge variant={tone} className={cn(className)} data-testid="security-level-badge" data-tone={tone}>
      <Icon aria-hidden="true" className="shrink-0" />
      {/* 상한이 있을 때만 말줄임 — 공용 TruncatedText 로 전체 이름을 title 에 둔다 */}
      <TruncatedText value={level.name} className="min-w-0" />
    </Badge>
  );
}
