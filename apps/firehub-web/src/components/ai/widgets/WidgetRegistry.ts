import { lazy } from 'react';
import type { ComponentType } from 'react';
import type { WidgetProps } from './types';

interface WidgetEntry {
  component: React.LazyExoticComponent<ComponentType<WidgetProps<any>>>;
  label: string;
  icon: string;
}

const WIDGET_REGISTRY: Record<string, WidgetEntry> = {
  show_chart: {
    component: lazy(() => import('./ChartWidgetAdapter')),
    label: '차트 표시',
    icon: '📊',
  },
  show_dataset: {
    component: lazy(() => import('./DatasetWidget')),
    label: '데이터셋 표시',
    icon: '📦',
  },
  show_table: {
    component: lazy(() => import('./TableWidget')),
    label: '테이블 표시',
    icon: '📋',
  },
  navigate_to: {
    component: lazy(() => import('./NavigateToWidget')),
    label: '페이지 이동',
    icon: '🔗',
  },
  show_pipeline: {
    component: lazy(() => import('./PipelineStatusWidget')),
    label: '파이프라인 상태',
    icon: '⚙️',
  },
  show_dataset_list: {
    component: lazy(() => import('./DatasetListWidget')),
    label: '데이터셋 목록',
    icon: '📦',
  },
  show_pipeline_list: {
    component: lazy(() => import('./PipelineListWidget')),
    label: '파이프라인 목록',
    icon: '⚙️',
  },
  show_dashboard_summary: {
    component: lazy(() => import('./DashboardWidget')),
    label: '대시보드 현황',
    icon: '📈',
  },
  show_activity: {
    component: lazy(() => import('./ActivityWidget')),
    label: '최근 활동',
    icon: '🕐',
  },
};

export function getWidget(toolName: string): WidgetEntry | undefined {
  const cleanName = toolName.replace(/^mcp__firehub__/, '');
  return WIDGET_REGISTRY[cleanName];
}
