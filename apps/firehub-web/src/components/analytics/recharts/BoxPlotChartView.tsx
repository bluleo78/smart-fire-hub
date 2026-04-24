// apps/firehub-web/src/components/analytics/recharts/BoxPlotChartView.tsx
// 카테고리별 통계(min/q1/median/q3/max)를 Box Plot으로 표시.
// ResizeObserver로 컨테이너 크기를 감지하고 SVG를 직접 렌더링한다.
// config.xAxis = 카테고리 컬럼명
// 데이터에 min, q1, median, q3, max 컬럼이 있어야 함
import { useEffect, useRef, useState } from 'react';

import type { ChartViewProps } from '../chart-view-props';

const MARGIN = { top: 20, right: 20, bottom: 40, left: 50 };
const BOX_WIDTH_RATIO = 0.4; // 카테고리 폭 대비 박스 폭 비율

interface BoxStat {
  category: string;
  min: number;
  q1: number;
  median: number;
  q3: number;
  max: number;
}

export function BoxPlotChartView({ data, config, height = 300 }: ChartViewProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [dims, setDims] = useState({ width: 0, height: 0 });

  // 컨테이너 크기 감지
  useEffect(() => {
    if (!containerRef.current) return;
    const obs = new ResizeObserver(entries => {
      const { width, height: h } = entries[0].contentRect;
      setDims({ width, height: h });
    });
    obs.observe(containerRef.current);
    return () => obs.disconnect();
  }, []);

  const stats: BoxStat[] = data.map(d => ({
    category: String(d[config.xAxis] ?? ''),
    min: Number(d.min ?? d.q0 ?? 0),
    q1: Number(d.q1 ?? 0),
    median: Number(d.median ?? d.q2 ?? 0),
    q3: Number(d.q3 ?? 0),
    max: Number(d.max ?? d.q4 ?? 0),
  }));

  const containerH = height ?? dims.height;
  const plotW = dims.width - MARGIN.left - MARGIN.right;
  const plotH = containerH - MARGIN.top - MARGIN.bottom;

  // Y 스케일
  const allVals = stats.flatMap(s => [s.min, s.max]);
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

  // X 스케일 (카테고리)
  const step = plotW / (stats.length || 1);
  const boxW = step * BOX_WIDTH_RATIO;
  const toX = (i: number) => step * i + step / 2;

  // Y축 눈금
  const yTicks = Array.from({ length: 5 }, (_, i) => yMin + (yRange * i) / 4);

  return (
    <div ref={containerRef} style={{ width: '100%', height: containerH }}>
      {dims.width > 0 && (
        <svg width={dims.width} height={containerH}>
          <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
            {/* Y축 */}
            {yTicks.map(t => (
              <g key={t} transform={`translate(0,${toY(t)})`}>
                <line x1={0} x2={plotW} stroke="hsl(var(--border))" strokeDasharray="3 3" />
                <text x={-8} textAnchor="end" fontSize={10} fill="hsl(var(--muted-foreground))" dy="0.32em">
                  {t.toFixed(1)}
                </text>
              </g>
            ))}
            {/* 박스 플롯 */}
            {stats.map((s, i) => {
              const cx = toX(i);
              const x1 = cx - boxW / 2;
              const x2 = cx + boxW / 2;
              return (
                <g key={s.category}>
                  {/* 위 수염 (median → max) */}
                  <line x1={cx} y1={toY(s.q3)} x2={cx} y2={toY(s.max)}
                    stroke="#8884d8" strokeWidth={1.5} />
                  <line x1={x1 + boxW * 0.2} y1={toY(s.max)} x2={x2 - boxW * 0.2} y2={toY(s.max)}
                    stroke="#8884d8" strokeWidth={1.5} />
                  {/* 아래 수염 (min → q1) */}
                  <line x1={cx} y1={toY(s.q1)} x2={cx} y2={toY(s.min)}
                    stroke="#8884d8" strokeWidth={1.5} />
                  <line x1={x1 + boxW * 0.2} y1={toY(s.min)} x2={x2 - boxW * 0.2} y2={toY(s.min)}
                    stroke="#8884d8" strokeWidth={1.5} />
                  {/* IQR 박스 (q1 ~ q3) */}
                  <rect
                    x={x1} y={toY(s.q3)}
                    width={boxW} height={Math.max(toY(s.q1) - toY(s.q3), 1)}
                    fill="#8884d8" fillOpacity={0.25}
                    stroke="#8884d8" strokeWidth={1.5}
                  />
                  {/* 중앙값 선 */}
                  <line x1={x1} y1={toY(s.median)} x2={x2} y2={toY(s.median)}
                    stroke="#8884d8" strokeWidth={2.5} />
                  {/* X축 라벨 */}
                  <text x={cx} y={plotH + 20} textAnchor="middle" fontSize={11}
                    fill="hsl(var(--muted-foreground))">{s.category}</text>
                </g>
              );
            })}
          </g>
        </svg>
      )}
    </div>
  );
}
