// apps/firehub-web/src/components/analytics/chart-view-props.ts
// 모든 신규 차트 컴포넌트가 구현해야 하는 공통 props 계약.
// 라이브러리 교체 시 이 인터페이스만 유지하면 ChartRenderer 수정 불필요.
import type { ChartConfig } from '../../types/analytics';

export interface ChartViewProps {
  data: Record<string, unknown>[];
  config: ChartConfig;
  height?: number; // undefined이면 fillParent 모드 (h-full)
}
