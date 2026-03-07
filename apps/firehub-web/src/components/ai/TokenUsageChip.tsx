import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../ui/tooltip';

const MAX_CONTEXT_TOKENS = 200_000;

function formatTokens(tokens: number): string {
  if (tokens < 1000) return String(tokens);
  if (tokens < 10_000) return `${(tokens / 1000).toFixed(1)}K`;
  return `${Math.round(tokens / 1000)}K`;
}

interface TokenUsageChipProps {
  tokens: number | null;
  isCompacting?: boolean;
}

export function TokenUsageChip({ tokens, isCompacting }: TokenUsageChipProps) {
  if (tokens === null && !isCompacting) return null;

  if (isCompacting) {
    return (
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger asChild>
            <div className="flex items-center gap-1.5 shrink-0 cursor-default text-orange-500">
              <div className="h-3 w-3 animate-spin rounded-full border-[1.5px] border-orange-500 border-t-transparent" />
              <span className="text-xs leading-none">요약 중</span>
            </div>
          </TooltipTrigger>
          <TooltipContent side="bottom" className="text-xs">
            <p>컨텍스트가 길어져 자동으로 요약하는 중입니다</p>
          </TooltipContent>
        </Tooltip>
      </TooltipProvider>
    );
  }

  const safeTokens = tokens ?? 0;
  const pct = Math.min((safeTokens / MAX_CONTEXT_TOKENS) * 100, 100);
  const isWarning = safeTokens >= MAX_CONTEXT_TOKENS * 0.5;
  const isCritical = safeTokens >= MAX_CONTEXT_TOKENS * 0.75;

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
              {formatTokens(safeTokens)}
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
          <p>컨텍스트: {safeTokens.toLocaleString()} / {MAX_CONTEXT_TOKENS.toLocaleString()} 토큰 ({pct.toFixed(1)}%)</p>
          {isCritical && <p className="text-destructive">컴팩션이 곧 실행됩니다</p>}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
