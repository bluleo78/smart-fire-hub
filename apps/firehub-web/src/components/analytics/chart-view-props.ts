// apps/firehub-web/src/components/analytics/chart-view-props.ts
// 모든 신규 차트 컴포넌트가 구현해야 하는 공통 props 계약.
// 라이브러리 교체 시 이 인터페이스만 유지하면 ChartRenderer 수정 불필요.
import type { ChartConfig } from '../../types/analytics';

export interface ChartViewProps {
  data: Record<string, unknown>[];
  config: ChartConfig;
  /**
   * number = 호출 쪽이 고정한 높이(예: AI 채팅 InlineChartWidget),
   * undefined = 부모를 채움(대시보드 위젯·차트 빌더 미리보기).
   * 뷰 안에서 기본값을 두지 말 것 — WD-845.
   */
  height?: number;
}
