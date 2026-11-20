// apps/firehub-web/src/components/analytics/recharts/BoxPlotChartView.tsx
// 카테고리별 통계(min/q1/median/q3/max)를 Box Plot으로 표시.
// ResizeObserver로 컨테이너 크기를 감지하고 SVG를 직접 렌더링한다.
// config.xAxis = 카테고리 컬럼명
// 데이터에 min, q1, median, q3, max 컬럼이 있어야 함
import { AlertTriangle } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

import { BOXPLOT_REQUIRED_COLUMNS_LABEL, findMissingBoxplotStats, toBoxStats } from '../boxplot-stats';
import type { ChartViewProps } from '../chart-view-props';
import { ChartDrawingArea } from '../ChartDrawingArea';

const MARGIN = { top: 20, right: 20, bottom: 40, left: 50 };
const BOX_WIDTH_RATIO = 0.4; // 카테고리 폭 대비 박스 폭 비율

// height 가 없으면 부모를 채운다 — ChartViewProps 참고(WD-845)
export function BoxPlotChartView({ data, config, height }: ChartViewProps) {
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

  // WD-305: 통계 컬럼이 없는 일반 쿼리면 값을 0 으로 읽어 납작한 선만 그리게 된다 — 그리지 않고 필요한 컬럼을 안내한다.
  // 행마다 키가 같으므로 첫 행의 키로 판단한다(빈 데이터는 아래 「데이터 없음」이 처리).
  const missingStats = data.length > 0 ? findMissingBoxplotStats(Object.keys(data[0])) : [];

  // WD-826: 통계 읽기는 값 대체 표(chart-a11y)와 같은 함수를 쓴다 — 표와 그림이 같은 값을 보이게
  const stats = useMemo(() => toBoxStats(data, config.xAxis), [data, config.xAxis]);

  const containerH = height ?? dims.height;
  // WD-845: 측정 컨테이너는 height 미지정(fillParent)이면 부모를 채워야 ResizeObserver 가 부모 높이를 읽는다
  const containerCss = height ?? '100%';
  const plotW = dims.width - MARGIN.left - MARGIN.right;
  const plotH = containerH - MARGIN.top - MARGIN.bottom;
  // WD-845: 측정 높이가 위·아래 여백 합 이하(아주 낮은 위젯)면 plotH 가 0 이하라 뒤집혀 그려지므로 그리지 않는다
  const canDraw = dims.width > 0 && containerH > MARGIN.top + MARGIN.bottom;

  // Y 스케일
  const allVals = stats.flatMap(s => [s.min, s.max]);
  // 빈 데이터: Math.min(...[]) = Infinity 로 SVG 좌표가 NaN이 되는 것을 방지
  if (allVals.length === 0) {
    return (
      <div ref={containerRef} style={{ width: '100%', height: containerCss }}
        className="flex items-center justify-center text-sm text-muted-foreground">
        데이터 없음
      </div>
    );
  }
  if (missingStats.length > 0) {
    // 계속 보이는 정적 안내라 role=alert 를 붙이지 않는다(ChartRenderer 의 컬럼 안내와 같은 판단).
    return (
      <div
        ref={containerRef}
        style={{ width: '100%', height: containerCss }}
        className="flex flex-col items-center justify-center gap-2 px-4 text-center"
        data-testid="boxplot-missing-stats"
      >
        <AlertTriangle className="h-5 w-5 shrink-0 text-warning" aria-hidden="true" />
        <p className="text-sm text-muted-foreground break-keep">
          박스 플롯은 쿼리 결과에 {BOXPLOT_REQUIRED_COLUMNS_LABEL} 컬럼이 있어야 그릴 수 있습니다.
        </p>
        <p className="text-xs text-muted-foreground break-keep">
          없는 컬럼: {missingStats.join(', ')} — 카테고리별 통계를 계산하는 쿼리를 쓰거나 다른 차트 타입을 고르세요.
        </p>
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
    <div ref={containerRef} data-chart-measured={dims.width > 0 ? 'true' : undefined} style={{ width: '100%', height: containerCss }}>
      {/* WD-826: 그림만 보조기술에서 숨긴다 — 값은 figure 의 대체 표가 전달하고, 안내문은 위에서 따로 반환해 읽힌다 */}
      <ChartDrawingArea>
        {canDraw && (
          <svg className="block" width={dims.width} height={containerH}>
            <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
              {/* Y축 */}
              {yTicks.map(t => (
                <g key={t} transform={`translate(0,${toY(t)})`}>
                  <line x1={0} x2={plotW} stroke="var(--border)" strokeDasharray="3 3" />
                  <text x={-8} textAnchor="end" fontSize={10} fill="var(--muted-foreground)" dy="0.32em">
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
                      stroke="var(--chart-1)" strokeWidth={1.5} />
                    <line x1={x1 + boxW * 0.2} y1={toY(s.max)} x2={x2 - boxW * 0.2} y2={toY(s.max)}
                      stroke="var(--chart-1)" strokeWidth={1.5} />
                    {/* 아래 수염 (min → q1) */}
                    <line x1={cx} y1={toY(s.q1)} x2={cx} y2={toY(s.min)}
                      stroke="var(--chart-1)" strokeWidth={1.5} />
                    <line x1={x1 + boxW * 0.2} y1={toY(s.min)} x2={x2 - boxW * 0.2} y2={toY(s.min)}
                      stroke="var(--chart-1)" strokeWidth={1.5} />
                    {/* IQR 박스 (q1 ~ q3) */}
                    <rect
                      x={x1} y={toY(s.q3)}
                      width={boxW} height={Math.max(toY(s.q1) - toY(s.q3), 1)}
                      fill="var(--chart-1)" fillOpacity={0.25}
                      stroke="var(--chart-1)" strokeWidth={1.5}
                    />
                    {/* 중앙값 선 */}
                    <line x1={x1} y1={toY(s.median)} x2={x2} y2={toY(s.median)}
                      stroke="var(--chart-1)" strokeWidth={2.5} />
                    {/* X축 라벨 */}
                    <text x={cx} y={plotH + 20} textAnchor="middle" fontSize={11}
                      fill="var(--muted-foreground)">{s.category}</text>
                  </g>
                );
              })}
            </g>
          </svg>
        )}
      </ChartDrawingArea>
    </div>
  );
}
