import { Lock, ShieldCheck } from 'lucide-react';

import { levelTone } from '../../lib/security-level';
import { cn } from '../../lib/utils';
import type { SecurityLevelSummary } from '../../types/security-level';
import { Badge } from './badge';
import { TruncatedText } from './truncated-text';

interface SecurityLevelBadgeProps {
  level: SecurityLevelSummary;
  className?: string;
  /**
   * 목록 표 칸·설정 등급 행(WD-861)·AI 인사이트 실행·리포트 목록(WD-864)에서만 켠다 — 이름 말줄임 + 전체 이름 title.
   * 끄면(기본) 부모 폭 안에서 줄바꿈하며 title 을 두지 않는다(WD-658).
   * 켜면 배지가 부모 폭 안에서 줄어들 수 있게(min-w-0 max-w-full shrink) 스스로 잡는다 — Badge 기본 shrink-0 이면 말줄임이 걸리지 않는다.
   * 더 좁은 상한은 className(예: max-w-[5.25rem])으로 덮는다.
   */
  truncate?: boolean;
}

/**
 * 보안 등급 배지 — 아이콘+이름 항상 병기(색만으로 의미 전달 X, 스펙 §5). 허용 목록 등급은 Lock, 그 외는 ShieldCheck.
 */
export function SecurityLevelBadge({ level, className, truncate = false }: SecurityLevelBadgeProps) {
  const tone = levelTone(level);
  const Icon = tone === 'caution' ? Lock : ShieldCheck;
  return (
    <Badge
      variant={tone}
      className={cn(
        truncate
          ? 'min-w-0 max-w-full shrink'
          : // WD-864: 상세·대화상자는 등급을 비교해 고르므로 자르지 않고 부모 폭 안에서 줄바꿈한다(공백 없는 이름도 글자 단위).
            // overflow-visible: Badge 의 overflow-hidden 은 flex 최소 폭을 0 으로 만들어 좁은 행에서 줄바꿈 대신 잘리게 한다.
            // rounded-xl: 한 줄에서는 rounded-full 과 같고 여러 줄에서도 글자 모서리가 잘리지 않는다.
            'max-w-full shrink overflow-visible whitespace-normal [overflow-wrap:anywhere] items-start text-left rounded-xl',
        className,
      )}
      data-testid="security-level-badge"
      data-tone={tone}
    >
      {/* 기본 모드(여러 줄 가능)는 items-start 라 아이콘을 첫 줄 높이에 맞춘다 */}
      <Icon aria-hidden="true" className={cn('shrink-0', !truncate && 'mt-0.5')} />
      {/* 빈 이름은 예전처럼 빈칸(fallback="") */}
      {truncate ? (
        <TruncatedText value={level.name} fallback="" className="min-w-0" />
      ) : (
        <span className="min-w-0">{level.name}</span>
      )}
    </Badge>
  );
}
