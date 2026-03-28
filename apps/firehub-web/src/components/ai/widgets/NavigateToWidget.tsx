import { useEffect, useRef } from 'react';
import { ExternalLink } from 'lucide-react';
import type { WidgetProps } from './types';

interface NavigateToInput {
  type: string;
  id?: number;
  label: string;
}

const ROUTE_MAP: Record<string, { detail?: (id: number) => string; list: string }> = {
  // 데이터
  home: { list: '/' },
  dataset: { detail: (id) => `/data/datasets/${id}`, list: '/data/datasets' },
  dataset_new: { list: '/data/datasets/new' },
  category: { list: '/data/categories' },
  // 파이프라인
  pipeline: { detail: (id) => `/pipelines/${id}`, list: '/pipelines' },
  pipeline_new: { list: '/pipelines/new' },
  // 분석
  query: { detail: (id) => `/analytics/queries/${id}`, list: '/analytics/queries' },
  query_new: { list: '/analytics/queries/new' },
  chart: { detail: (id) => `/analytics/charts/${id}`, list: '/analytics/charts' },
  chart_new: { list: '/analytics/charts/new' },
  dashboard: { detail: (id) => `/analytics/dashboards/${id}`, list: '/analytics/dashboards' },
  // 관리
  settings: { list: '/admin/settings' },
  users: { detail: (id) => `/admin/users/${id}`, list: '/admin/users' },
  roles: { detail: (id) => `/admin/roles/${id}`, list: '/admin/roles' },
  audit_logs: { list: '/admin/audit-logs' },
  api_connections: { detail: (id) => `/admin/api-connections/${id}`, list: '/admin/api-connections' },
  profile: { list: '/profile' },
};

const TYPE_LABELS: Record<string, string> = {
  home: '홈',
  dataset: '데이터셋',
  dataset_new: '데이터셋 생성',
  category: '카테고리',
  pipeline: '파이프라인',
  pipeline_new: '파이프라인 생성',
  query: '쿼리',
  query_new: '쿼리 생성',
  chart: '차트',
  chart_new: '차트 생성',
  dashboard: '대시보드',
  settings: '설정',
  users: '사용자',
  roles: '역할',
  audit_logs: '감사 로그',
  api_connections: 'API 연결',
  profile: '프로필',
};

const TYPE_ICONS: Record<string, string> = {
  home: '🏠',
  dataset: '📦',
  dataset_new: '📦',
  category: '📂',
  pipeline: '⚙️',
  pipeline_new: '⚙️',
  query: '🔍',
  query_new: '🔍',
  chart: '📈',
  chart_new: '📈',
  dashboard: '📊',
  settings: '⚙️',
  users: '👥',
  roles: '🔐',
  audit_logs: '📋',
  api_connections: '🔌',
  profile: '👤',
};

export default function NavigateToWidget({ input, onNavigate }: WidgetProps<NavigateToInput>) {
  const { type, id, label } = input;
  const route = ROUTE_MAP[type];
  const path = route ? (id && route.detail ? route.detail(id) : route.list) : null;
  const navigated = useRef(false);

  useEffect(() => {
    if (path && onNavigate && !navigated.current) {
      navigated.current = true;
      onNavigate(path);
    }
  }, [path, onNavigate]);

  if (!path) return null;

  return (
    <div className="my-1 flex items-center gap-2 rounded-lg border border-border bg-muted/50 px-3 py-2 text-sm">
      <span>{TYPE_ICONS[type] || '🔗'}</span>
      <span className="text-muted-foreground">
        {TYPE_LABELS[type] || type}
      </span>
      <button
        onClick={() => onNavigate?.(path)}
        className="flex items-center gap-1 font-medium text-primary hover:underline"
      >
        {label}
        <ExternalLink className="h-3 w-3" />
      </button>
      <span className="text-xs text-muted-foreground">으로 이동했습니다</span>
    </div>
  );
}
