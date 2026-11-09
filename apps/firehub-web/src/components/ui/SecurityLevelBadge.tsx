import { Lock, ShieldCheck } from 'lucide-react';

import { levelTone } from '../../lib/security-level';
import type { SecurityLevelSummary } from '../../types/security-level';
import { Badge } from './badge';
import { TruncatedText } from './truncated-text';

interface SecurityLevelBadgeProps {
  level: SecurityLevelSummary;
  className?: string;
  /** 목록 표 칸에서만 켠다 — 이름 말줄임 + 전체 이름 title (WD-658: 표 밖에는 title 을 두지 않는다) */
  truncate?: boolean;
}

/**
 * 보안 등급 배지 — 아이콘+이름 항상 병기(색만으로 의미 전달 X, 스펙 §5). 허용 목록 등급은 Lock, 그 외는 ShieldCheck.
 */
export function SecurityLevelBadge({ level, className, truncate = false }: SecurityLevelBadgeProps) {
  const tone = levelTone(level);
  const Icon = tone === 'caution' ? Lock : ShieldCheck;
  return (
    <Badge variant={tone} className={className} data-testid="security-level-badge" data-tone={tone}>
      <Icon aria-hidden="true" className="shrink-0" />
      {/* 빈 이름은 예전처럼 빈칸(fallback="") */}
      {truncate ? <TruncatedText value={level.name} fallback="" className="min-w-0" /> : level.name}
    </Badge>
  );
}
