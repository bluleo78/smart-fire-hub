import { Handle, type Node,type NodeProps, Position } from '@xyflow/react';
import { Brain, CheckCircle2, Clock,FileCode, Globe, Loader2, Plus, SkipForward, Terminal, X, XCircle } from 'lucide-react';
import { memo } from 'react';
import { useTheme } from 'next-themes';

export interface StepNodeData extends Record<string, unknown> {
  label: string;
  description?: string;
  scriptType: 'SQL' | 'PYTHON' | 'API_CALL' | 'AI_CLASSIFY';
  stepNumber: number;
  isSelected: boolean;
  hasError: boolean;
  hasOutgoingEdge: boolean;
  executionStatus?: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  readOnly?: boolean;
  onDelete?: () => void;
  onAddAfter?: () => void;
}

export type StepNodeType = Node<StepNodeData, 'step'>;

const TYPE_CONFIG_LIGHT = {
  SQL: {
    color: '#1f2937',       // gray-800
    bgHeader: 'rgba(17,24,39,0.06)',
    bgHeaderSelected: 'rgba(17,24,39,0.12)',
    label: 'SQL',
    Icon: FileCode,
  },
  PYTHON: {
    color: '#4b5563',       // gray-600
    bgHeader: 'rgba(75,85,99,0.06)',
    bgHeaderSelected: 'rgba(75,85,99,0.12)',
    label: 'Python',
    Icon: Terminal,
  },
  API_CALL: {
    color: '#0369a1',       // sky-700
    bgHeader: 'rgba(3,105,161,0.06)',
    bgHeaderSelected: 'rgba(3,105,161,0.12)',
    label: 'API',
    Icon: Globe,
  },
  AI_CLASSIFY: {
    color: '#7c3aed',       // violet-600
    bgHeader: 'rgba(124,58,237,0.06)',
    bgHeaderSelected: 'rgba(124,58,237,0.12)',
    label: 'AI 분류',
    Icon: Brain,
  },
} as const;

const TYPE_CONFIG_DARK = {
  SQL: {
    color: 'oklch(0.72 0 0)',
    bgHeader: 'oklch(1 0 0 / 5%)',
    bgHeaderSelected: 'oklch(1 0 0 / 10%)',
    label: 'SQL',
    Icon: FileCode,
  },
  PYTHON: {
    color: 'oklch(0.65 0 0)',
    bgHeader: 'oklch(1 0 0 / 4%)',
    bgHeaderSelected: 'oklch(1 0 0 / 8%)',
    label: 'Python',
    Icon: Terminal,
  },
  API_CALL: {
    color: 'oklch(0.72 0.12 225)',
    bgHeader: 'oklch(0.45 0.08 225 / 12%)',
    bgHeaderSelected: 'oklch(0.45 0.08 225 / 20%)',
    label: 'API',
    Icon: Globe,
  },
  AI_CLASSIFY: {
    color: 'oklch(0.75 0.14 285)',
    bgHeader: 'oklch(0.45 0.10 285 / 12%)',
    bgHeaderSelected: 'oklch(0.45 0.10 285 / 20%)',
    label: 'AI 분류',
    Icon: Brain,
  },
} as const;

function getTypeConfig(isDark: boolean) {
  return isDark ? TYPE_CONFIG_DARK : TYPE_CONFIG_LIGHT;
}

interface StatusStyle {
  label: string;
  Icon: React.ElementType;
  bg: string;
  color: string;
  border: string;
}

const STATUS_CONFIG_LIGHT: Record<NonNullable<StepNodeData['executionStatus']>, StatusStyle> = {
  PENDING: {
    label: '대기',
    Icon: Clock,
    bg: 'rgb(249,250,251)',
    color: 'rgb(156,163,175)',
    border: 'rgb(229,231,235)',
  },
  RUNNING: {
    label: '실행 중',
    Icon: Loader2,
    bg: 'rgb(243,244,246)',
    color: 'rgb(55,65,81)',
    border: 'rgb(209,213,219)',
  },
  COMPLETED: {
    label: '완료',
    Icon: CheckCircle2,
    bg: 'rgb(243,244,246)',
    color: 'rgb(17,24,39)',
    border: 'rgb(156,163,175)',
  },
  FAILED: {
    label: '실패',
    Icon: XCircle,
    bg: 'rgb(249,250,251)',
    color: 'rgb(75,85,99)',
    border: 'rgb(156,163,175)',
  },
  SKIPPED: {
    label: '건너뜀',
    Icon: SkipForward,
    bg: 'rgb(249,250,251)',
    color: 'rgb(156,163,175)',
    border: 'rgb(229,231,235)',
  },
};

const STATUS_CONFIG_DARK: Record<NonNullable<StepNodeData['executionStatus']>, StatusStyle> = {
  PENDING: {
    label: '대기',
    Icon: Clock,
    bg: 'oklch(1 0 0 / 5%)',
    color: 'oklch(0.6 0 0)',
    border: 'oklch(1 0 0 / 6%)',
  },
  RUNNING: {
    label: '실행 중',
    Icon: Loader2,
    bg: 'oklch(0.2 0.04 240)',
    color: 'oklch(0.7 0.13 240)',
    border: 'oklch(0.7 0.13 240 / 30%)',
  },
  COMPLETED: {
    label: '완료',
    Icon: CheckCircle2,
    bg: 'oklch(0.2 0.04 149.5)',
    color: 'oklch(0.65 0.15 149.5)',
    border: 'oklch(0.65 0.15 149.5 / 30%)',
  },
  FAILED: {
    label: '실패',
    Icon: XCircle,
    bg: 'oklch(0.704 0.191 22.216 / 12%)',
    color: 'oklch(0.704 0.191 22.216)',
    border: 'oklch(0.704 0.191 22.216 / 30%)',
  },
  SKIPPED: {
    label: '건너뜀',
    Icon: SkipForward,
    bg: 'oklch(0.2 0.04 84)',
    color: 'oklch(0.76 0.14 84)',
    border: 'oklch(0.76 0.14 84 / 30%)',
  },
};

// Execution status → card background tint (monochrome)
const STATUS_CARD_BG_LIGHT: Record<NonNullable<StepNodeData['executionStatus']>, string> = {
  PENDING: 'rgba(249,250,251,0.6)',
  RUNNING: 'rgba(243,244,246,0.6)',
  COMPLETED: 'rgba(243,244,246,0.6)',
  FAILED: 'rgba(249,250,251,0.6)',
  SKIPPED: 'rgba(249,250,251,0.6)',
};

const STATUS_CARD_BG_DARK: Record<NonNullable<StepNodeData['executionStatus']>, string> = {
  PENDING: 'oklch(1 0 0 / 3%)',
  RUNNING: 'oklch(1 0 0 / 3%)',
  COMPLETED: 'oklch(1 0 0 / 3%)',
  FAILED: 'oklch(1 0 0 / 3%)',
  SKIPPED: 'oklch(1 0 0 / 3%)',
};

export const StepNode = memo(function StepNode({ data }: NodeProps<StepNodeType>) {
  const { resolvedTheme } = useTheme();
  const isDark = resolvedTheme === 'dark';

  const showDelete = !data.readOnly && !data.executionStatus;
  const showAddAfter = !data.readOnly && !data.executionStatus && !data.hasOutgoingEdge;

  const typeConfig = getTypeConfig(isDark)[data.scriptType];
  const statusConfig = data.executionStatus
    ? (isDark ? STATUS_CONFIG_DARK : STATUS_CONFIG_LIGHT)[data.executionStatus]
    : null;
  const StatusIcon = statusConfig?.Icon;

  const statusCardBg = isDark ? STATUS_CARD_BG_DARK : STATUS_CARD_BG_LIGHT;

  const cardBg = data.executionStatus
    ? statusCardBg[data.executionStatus]
    : isDark ? 'oklch(1 0 0 / 3%)' : 'oklch(1 0 0)';

  const borderColor = data.hasError
    ? (isDark ? 'oklch(0.704 0.191 22.216)' : 'oklch(0.45 0 0)')
    : data.isSelected
      ? typeConfig.color
      : (isDark ? 'oklch(1 0 0 / 6%)' : 'oklch(0.9 0 0)');

  const outlineStyle: React.CSSProperties = data.hasError
    ? { outline: `2px solid ${isDark ? 'oklch(0.704 0.191 22.216)' : 'oklch(0.45 0 0)'}`, outlineOffset: '2px' }
    : data.isSelected
      ? { outline: `2px solid ${typeConfig.color}`, outlineOffset: '2px' }
      : {};

  return (
    <div className="relative" style={{ width: 220 }}>
      {/* Main card */}
      <div
        className="rounded-lg border shadow-sm transition-shadow duration-150 hover:shadow-md overflow-hidden"
        style={{
          borderColor,
          backgroundColor: cardBg,
          ...outlineStyle,
        }}
      >
        {/* ReactFlow handles */}
        <Handle
          type="target"
          position={Position.Left}
          style={{
            width: 8,
            height: 8,
            backgroundColor: typeConfig.color,
            border: `2px solid ${isDark ? 'oklch(0.13 0.015 280)' : 'oklch(1 0 0)'}`,
            left: -5,
            boxShadow: `0 0 0 1px ${isDark ? 'oklch(1 0 0 / 10%)' : 'oklch(0.85 0 0)'}`,
          }}
        />
        <Handle
          type="source"
          position={Position.Right}
          style={{
            width: 8,
            height: 8,
            backgroundColor: typeConfig.color,
            border: `2px solid ${isDark ? 'oklch(0.13 0.015 280)' : 'oklch(1 0 0)'}`,
            right: -5,
            boxShadow: `0 0 0 1px ${isDark ? 'oklch(1 0 0 / 10%)' : 'oklch(0.85 0 0)'}`,
          }}
        />

        {/* Add-after button */}
        {showAddAfter && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              data.onAddAfter?.();
            }}
            aria-label="다음 스텝 추가"
            title="다음 스텝 추가"
            className="absolute -right-9 top-1/2 flex h-5 w-5 -translate-y-1/2 items-center justify-center rounded-full border bg-background text-muted-foreground shadow-sm transition-colors hover:border-transparent hover:bg-accent hover:text-accent-foreground"
          >
            <Plus className="h-3 w-3" />
          </button>
        )}

        {/* ── Header: type identity + actions ── */}
        <div
          className="flex items-center justify-between gap-1 px-3 py-1.5"
          style={{
            backgroundColor: data.isSelected ? typeConfig.bgHeaderSelected : typeConfig.bgHeader,
            borderBottom: `1px solid ${data.isSelected ? `${typeConfig.color}33` : (isDark ? 'oklch(1 0 0 / 8%)' : 'oklch(0.93 0 0)')}`,
          }}
        >
          {/* Type icon + label */}
          <div
            className="flex items-center gap-1.5 text-xs font-semibold"
            style={{ color: typeConfig.color }}
          >
            <span style={{ opacity: 0.5 }}>#{data.stepNumber}</span>
            <typeConfig.Icon className="h-3.5 w-3.5 shrink-0" />
            <span>{typeConfig.label}</span>
          </div>

          {/* Status badge OR delete button */}
          <div className="flex items-center gap-1">
            {statusConfig && StatusIcon && (
              <div
                className="flex items-center gap-1 rounded-full px-1.5 py-0.5 text-xs font-medium border"
                style={{
                  backgroundColor: statusConfig.bg,
                  color: statusConfig.color,
                  borderColor: statusConfig.border,
                }}
              >
                <StatusIcon
                  className={`h-2.5 w-2.5 shrink-0 ${data.executionStatus === 'RUNNING' ? 'animate-spin' : ''}`}
                />
                <span>{statusConfig.label}</span>
              </div>
            )}

            {showDelete && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  data.onDelete?.();
                }}
                aria-label="스텝 삭제"
                title="스텝 삭제"
                className="flex h-5 w-5 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
        </div>

        {/* ── Body: name + description ── */}
        <div className="px-3 py-2">
          {/* Step name */}
          <p
            className="truncate text-sm font-medium leading-snug"
            style={{
              color: data.label && data.label !== '(이름 없음)'
                ? (isDark ? 'oklch(0.93 0 0)' : 'oklch(0.145 0 0)')
                : (isDark ? 'oklch(0.6 0 0)' : 'oklch(0.556 0 0)'),
            }}
            title={data.label || '(이름 없음)'}
          >
            {data.label || '(이름 없음)'}
          </p>

          {/* Description — 2 lines reserved */}
          <p
            className="mt-1 line-clamp-2 text-xs leading-normal"
            style={{
              color: data.description
                ? (isDark ? 'oklch(0.65 0 0)' : 'oklch(0.45 0 0)')
                : (isDark ? 'oklch(0.45 0 0)' : 'oklch(0.72 0 0)'),
              minHeight: 32,
            }}
            title={data.description || ''}
          >
            {data.description || '\u00A0'}
          </p>
        </div>
      </div>
    </div>
  );
});
