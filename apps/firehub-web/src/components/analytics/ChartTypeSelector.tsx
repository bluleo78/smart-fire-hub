import {
  AreaChart,
  BarChart2,
  Donut,
  LineChart,
  PieChart,
  ScatterChart,
  Table2,
} from 'lucide-react';

import { cn } from '../../lib/utils';
import type { ChartType } from '../../types/analytics';
import { Button } from '../ui/button';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../ui/tooltip';

// Lucide doesn't have a Donut icon — reuse PieChart with a label
// We'll use a custom approach with existing icons

interface ChartTypeMeta {
  type: ChartType;
  label: string;
  Icon: React.ElementType;
}

const CHART_TYPES: ChartTypeMeta[] = [
  { type: 'BAR', label: '막대 차트', Icon: BarChart2 },
  { type: 'LINE', label: '선 차트', Icon: LineChart },
  { type: 'AREA', label: '영역 차트', Icon: AreaChart },
  { type: 'PIE', label: '파이 차트', Icon: PieChart },
  { type: 'DONUT', label: '도넛 차트', Icon: Donut },
  { type: 'SCATTER', label: '산점도', Icon: ScatterChart },
  { type: 'TABLE', label: '테이블', Icon: Table2 },
];

interface ChartTypeSelectorProps {
  value: ChartType;
  onChange: (type: ChartType) => void;
}

export function ChartTypeSelector({ value, onChange }: ChartTypeSelectorProps) {
  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex items-center gap-1 flex-wrap">
        {CHART_TYPES.map(({ type, label, Icon }) => (
          <Tooltip key={type}>
            <TooltipTrigger asChild>
              <Button
                variant={value === type ? 'default' : 'outline'}
                size="sm"
                className={cn(
                  'h-9 w-9 p-0',
                  value === type && 'shadow-sm'
                )}
                onClick={() => onChange(type)}
                aria-label={label}
              >
                <Icon className="h-4 w-4" />
              </Button>
            </TooltipTrigger>
            <TooltipContent side="bottom" sideOffset={4}>
              {label}
            </TooltipContent>
          </Tooltip>
        ))}
      </div>
    </TooltipProvider>
  );
}
