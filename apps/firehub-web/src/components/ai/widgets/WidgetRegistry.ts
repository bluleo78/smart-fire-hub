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
};

export function getWidget(toolName: string): WidgetEntry | undefined {
  const cleanName = toolName.replace(/^mcp__firehub__/, '');
  return WIDGET_REGISTRY[cleanName];
}
