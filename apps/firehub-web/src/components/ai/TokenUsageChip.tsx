import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../ui/tooltip';

const COMPACTION_THRESHOLD = 100_000;

function formatTokens(tokens: number): string {
  if (tokens < 1000) return String(tokens);
  if (tokens < 10_000) return `${(tokens / 1000).toFixed(1)}K`;
  return `${Math.round(tokens / 1000)}K`;
}

interface TokenUsageChipProps {
  tokens: number | null;
}

export function TokenUsageChip({ tokens }: TokenUsageChipProps) {
  if (tokens === null) return null;

  const pct = Math.min((tokens / COMPACTION_THRESHOLD) * 100, 100);
  const isWarning = tokens >= COMPACTION_THRESHOLD * 0.5;
  const isCritical = tokens >= COMPACTION_THRESHOLD;

  const fillColor = isCritical
    ? 'bg-destructive'
    : isWarning
      ? 'bg-orange-500'
      : 'bg-primary/40';

  const textColor = isCritical
    ? 'text-destructive'
    : isWarning
      ? 'text-orange-500'
      : 'text-muted-foreground';

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <div className={`flex items-center gap-1.5 shrink-0 cursor-default ${textColor}`}>
            <span className="text-xs font-mono tabular-nums leading-none">
              {formatTokens(tokens)}
            </span>
            <div className="w-10 h-1 rounded-full bg-muted overflow-hidden">
              <div
                className={`h-full rounded-full transition-all duration-500 ease-out ${fillColor}`}
                style={{ width: `${pct}%` }}
              />
            </div>
          </div>
        </TooltipTrigger>
        <TooltipContent side="bottom" className="text-xs">
          <p>컨텍스트: {tokens.toLocaleString()} / {COMPACTION_THRESHOLD.toLocaleString()} 토큰 ({pct.toFixed(1)}%)</p>
          {isCritical && <p className="text-destructive">컴팩션이 곧 실행됩니다</p>}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
