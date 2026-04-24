import type { ReactNode } from 'react';
import { Component, useState } from 'react';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import SyntaxHighlighter from 'react-syntax-highlighter/dist/esm/prism-light';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { toast } from 'sonner';

import { analyticsApi } from '../../api/analytics';
import type { ChartConfig, ChartType } from '../../types/analytics';
import { CHART_TYPE_LABELS } from '../../types/analytics';
import { ChartRenderer } from '../analytics/ChartRenderer';
import { Button } from '../ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/dialog';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Switch } from '../ui/switch';

SyntaxHighlighter.registerLanguage('sql', sql);

const codeStyle = oneDark as Record<string, React.CSSProperties>;

// ─── Error Boundary ──────────────────────────────────────────────────────────

interface ChartErrorBoundaryState {
  hasError: boolean;
}

class ChartErrorBoundary extends Component<
  { children: ReactNode },
  ChartErrorBoundaryState
> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(): ChartErrorBoundaryState {
    return { hasError: true };
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex h-[300px] items-center justify-center rounded-md bg-muted text-sm text-muted-foreground">
          차트를 표시할 수 없습니다.
        </div>
      );
    }
    return this.props.children;
  }
}

// ─── Save Dialog ─────────────────────────────────────────────────────────────

interface SaveDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  sql: string;
  chartType: ChartType;
  config: ChartConfig;
}

function SaveDialog({ open, onOpenChange, sql, chartType, config }: SaveDialogProps) {
  const [chartName, setChartName] = useState('');
  const [description, setDescription] = useState('');
  const [isShared, setIsShared] = useState(false);
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    if (!chartName.trim()) return;
    setSaving(true);
    try {
      const queryResponse = await analyticsApi.createQuery({
        name: `AI 생성: ${chartName}`,
        description: description || undefined,
        sqlText: sql,
        isShared,
      });
      const savedQueryId = queryResponse.data.id;
      await analyticsApi.createChart({
        name: chartName,
        description: description || undefined,
        savedQueryId,
        chartType,
        config,
        isShared,
      });
      toast.success('차트가 저장되었습니다.', {
        description: (
          <a href="/analytics/charts" className="underline text-primary">
            Analytics에서 확인하기
          </a>
        ),
      });
      onOpenChange(false);
      setChartName('');
      setDescription('');
      setIsShared(false);
    } catch (err: unknown) {
      const message =
        err instanceof Error ? err.message : '저장 중 오류가 발생했습니다.';
      toast.error(message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[420px]">
        <DialogHeader>
          <DialogTitle>차트로 저장</DialogTitle>
          <DialogDescription className="sr-only">차트 이름과 공개 여부를 설정하여 저장합니다.</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="chart-name">
              차트 이름 <span className="text-destructive">*</span>
            </Label>
            <Input
              id="chart-name"
              placeholder="차트 이름을 입력하세요"
              value={chartName}
              onChange={(e) => setChartName(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="chart-desc">설명 (선택)</Label>
            <Input
              id="chart-desc"
              placeholder="설명을 입력하세요"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <div className="flex items-center justify-between">
            <Label htmlFor="chart-shared">공유</Label>
            <Switch
              id="chart-shared"
              checked={isShared}
              onCheckedChange={setIsShared}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={saving}>
            취소
          </Button>
          <Button onClick={handleSave} disabled={!chartName.trim() || saving}>
            {saving ? '저장 중...' : '저장'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ─── InlineChartWidget ────────────────────────────────────────────────────────

interface InlineChartWidgetProps {
  sql: string;
  chartType: ChartType;
  config: ChartConfig;
  columns: string[];
  rows: Record<string, unknown>[];
}


export function InlineChartWidget({
  sql: sqlText,
  chartType,
  config,
  columns,
  rows,
}: InlineChartWidgetProps) {
  const [sqlOpen, setSqlOpen] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);

  return (
    <div className="my-2 rounded-lg border border-border bg-card overflow-hidden min-w-[min(400px,100%)]">
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border bg-muted/30">
        <span className="text-sm font-medium text-card-foreground">
          {CHART_TYPE_LABELS[chartType] ?? chartType}
        </span>
        <span className="text-xs text-muted-foreground ml-1">
          ({rows.length}건)
        </span>
      </div>

      {/* Chart */}
      <div className="p-3">
        <ChartErrorBoundary>
          <ChartRenderer
            chartType={chartType}
            config={config}
            data={rows}
            columns={columns}
            height={300}
          />
        </ChartErrorBoundary>
      </div>

      {/* SQL collapsible */}
      <div className="border-t border-border">
        <button
          type="button"
          className="flex w-full items-center gap-1.5 px-3 py-2 text-xs text-muted-foreground hover:text-foreground hover:bg-muted/30 transition-colors"
          onClick={() => setSqlOpen((v) => !v)}
        >
          <span className="transition-transform duration-200" style={{ display: 'inline-block', transform: sqlOpen ? 'rotate(90deg)' : 'rotate(0deg)' }}>
            ▶
          </span>
          SQL 보기
        </button>
        {sqlOpen && (
          <div className="px-3 pb-3">
            <SyntaxHighlighter
              style={codeStyle}
              language="sql"
              PreTag="div"
              customStyle={{ margin: 0, fontSize: '0.75rem', borderRadius: '0.375rem' }}
            >
              {sqlText}
            </SyntaxHighlighter>
          </div>
        )}
      </div>

      {/* Footer */}
      <div className="flex justify-end px-3 py-2 border-t border-border bg-muted/20">
        <Button
          size="sm"
          variant="outline"
          className="text-xs h-7"
          onClick={() => setDialogOpen(true)}
        >
          차트로 저장
        </Button>
      </div>

      <SaveDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        sql={sqlText}
        chartType={chartType}
        config={config}
      />
    </div>
  );
}
