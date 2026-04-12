// apps/firehub-web/src/components/analytics/recharts/CandlestickChartView.tsx
// OHLC 데이터를 캔들스틱으로 표시. open>close=빨간색, open<=close=녹색.
// ResizeObserver로 컨테이너 크기 감지 후 커스텀 SVG 렌더링.
import { useEffect, useRef, useState } from 'react';

import type { ChartViewProps } from '../chart-view-props';

const MARGIN = { top: 20, right: 20, bottom: 40, left: 60 };
const CANDLE_RATIO = 0.5;

export function CandlestickChartView({ data, config, height = 300 }: ChartViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [dims, setDims] = useState({ width: 0, height: 0 });

  useEffect(() => {
    if (!containerRef.current) return;
    const obs = new ResizeObserver(entries => {
      const { width, height: h } = entries[0].contentRect;
      setDims({ width, height: h });
    });
    obs.observe(containerRef.current);
    return () => obs.disconnect();
  }, []);

  const openKey = config.open ?? 'open';
  const highKey = config.high ?? 'high';
  const lowKey = config.low ?? 'low';
  const closeKey = config.close ?? 'close';

  const candles = data.map(d => ({
    label: String(d[config.xAxis] ?? ''),
    open: Number(d[openKey] ?? 0),
    high: Number(d[highKey] ?? 0),
    low: Number(d[lowKey] ?? 0),
    close: Number(d[closeKey] ?? 0),
  }));

  const containerH = height ?? dims.height;
  const plotW = dims.width - MARGIN.left - MARGIN.right;
  const plotH = containerH - MARGIN.top - MARGIN.bottom;

  const allVals = candles.flatMap(c => [c.high, c.low]);
  // 빈 데이터: Math.min(...[]) = Infinity 로 SVG 좌표가 NaN이 되는 것을 방지
  if (allVals.length === 0) {
    return (
      <div ref={containerRef} style={{ width: '100%', height: containerH }}
        className="flex items-center justify-center text-sm text-muted-foreground">
        데이터 없음
      </div>
    );
  }
  const yMin = Math.min(...allVals);
  const yMax = Math.max(...allVals);
  const yRange = yMax - yMin || 1;
  const toY = (v: number) => plotH - ((v - yMin) / yRange) * plotH;

  const step = plotW / (candles.length || 1);
  const candleW = step * CANDLE_RATIO;
  const toX = (i: number) => step * i + step / 2;

  const yTicks = Array.from({ length: 5 }, (_, i) => yMin + (yRange * i) / 4);

  return (
    <div ref={containerRef} style={{ width: '100%', height: containerH }}>
      {dims.width > 0 && (
        <svg width={dims.width} height={containerH}>
          <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
            {/* Y축 그리드 + 눈금 */}
            {yTicks.map(t => (
              <g key={t} transform={`translate(0,${toY(t)})`}>
                <line x1={0} x2={plotW} stroke="hsl(var(--border))" strokeDasharray="3 3" />
                <text x={-8} textAnchor="end" fontSize={10} fill="hsl(var(--muted-foreground))" dy="0.32em">
                  {t.toFixed(2)}
                </text>
              </g>
            ))}
            {/* 캔들 */}
            {candles.map((c, i) => {
              const cx = toX(i);
              const bullish = c.close >= c.open;
              const color = bullish ? 'hsl(142 72% 45%)' : 'hsl(0 72% 55%)';
              const bodyTop = toY(Math.max(c.open, c.close));
              const bodyBottom = toY(Math.min(c.open, c.close));
              const bodyH = Math.max(bodyBottom - bodyTop, 1);
              return (
                <g key={i}>
                  {/* 위 꼬리 */}
                  <line x1={cx} y1={toY(c.high)} x2={cx} y2={bodyTop} stroke={color} strokeWidth={1.5} />
                  {/* 아래 꼬리 */}
                  <line x1={cx} y1={bodyBottom} x2={cx} y2={toY(c.low)} stroke={color} strokeWidth={1.5} />
                  {/* 몸통 */}
                  <rect
                    x={cx - candleW / 2} y={bodyTop}
                    width={candleW} height={bodyH}
                    fill={color}
                    stroke={color}
                    strokeWidth={1}
                    fillOpacity={bullish ? 0.2 : 1}
                  />
                  {/* X 라벨 */}
                  <text x={cx} y={plotH + 20} textAnchor="middle" fontSize={10}
                    fill="hsl(var(--muted-foreground))">{c.label}</text>
                </g>
              );
            })}
          </g>
        </svg>
      )}
    </div>
  );
}
