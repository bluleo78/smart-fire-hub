import {
  AreaChart,
  BarChart2,
  BarChart3,
  BoxSelect,
  CandlestickChart,
  Donut,
  Filter,
  Gauge,
  Grid3X3,
  LayoutGrid,
  LineChart,
  MapPin,
  PieChart,
  Radar,
  ScatterChart,
  Table2,
  TrendingDown,
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
  { type: 'MAP', label: '지도', Icon: MapPin },
  // HISTOGRAM은 분포를 표현하는 BarChart3 사용 (BAR와 시각적 구분 — refs #93)
  { type: 'HISTOGRAM', label: '히스토그램', Icon: BarChart3 },
  { type: 'BOXPLOT', label: '박스 플롯', Icon: BoxSelect },
  { type: 'HEATMAP', label: '히트맵', Icon: Grid3X3 },
  { type: 'TREEMAP', label: '트리맵', Icon: LayoutGrid },
  { type: 'FUNNEL', label: '퍼널', Icon: Filter },
  { type: 'RADAR', label: '레이더', Icon: Radar },
  { type: 'WATERFALL', label: '폭포 차트', Icon: TrendingDown },
  { type: 'GAUGE', label: '게이지', Icon: Gauge },
  { type: 'CANDLESTICK', label: '캔들스틱', Icon: CandlestickChart },
];

interface ChartTypeSelectorProps {
  value: ChartType;
  onChange: (type: ChartType) => void;
}

export function ChartTypeSelector({ value, onChange }: ChartTypeSelectorProps) {
  return (
    <TooltipProvider delayDuration={300}>
      {/* 17개 아이콘 버튼을 하나의 묶음으로 알려 화면 읽기 프로그램이 「차트 타입」 그룹으로 읽게 한다(WD-349) */}
      <div role="group" aria-label="차트 타입" className="flex items-center gap-1 flex-wrap">
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
                // 선택 상태가 색으로만 보이면 화면 읽기 프로그램 사용자는 현재 타입을 알 수 없다 — 토글 버튼으로 노출한다(WD-349)
                aria-pressed={value === type}
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
