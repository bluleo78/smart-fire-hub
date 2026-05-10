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
  // AI가 전달하는 분석 제목 — 있으면 헤더에 표시, 없으면 차트 유형명으로 폴백
  title?: string;
  chartType: ChartType;
  config: ChartConfig;
  columns: string[];
  rows: Record<string, unknown>[];
}


export function InlineChartWidget({
  sql: sqlText,
  title,
  chartType,
  config,
  columns,
  rows,
}: InlineChartWidgetProps) {
  // SQL 보기 다이얼로그(전체 SQL을 모달로 표시 — 인라인 한 줄 표시 한계 해소, #204)
  const [sqlDialogOpen, setSqlDialogOpen] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);

  // 클립보드 복사 — 모달 안에서 전체 SQL을 빠르게 복사할 수 있도록 제공
  async function handleCopySql() {
    try {
      await navigator.clipboard.writeText(sqlText);
      toast.success('SQL이 복사되었습니다.');
    } catch {
      toast.error('복사에 실패했습니다.');
    }
  }

  // 헤더 표시 우선순위: title > 차트 유형명. 둘 다 trim 후 비어 있으면 chartType 키 그대로.
  const trimmedTitle = title?.trim();
  const headerLabel = trimmedTitle && trimmedTitle.length > 0
    ? trimmedTitle
    : (CHART_TYPE_LABELS[chartType] ?? chartType);
  // title이 있으면 차트 유형명을 보조 라벨로 표시(맥락 유지). 없으면 행수만 표시.
  const subLabel = trimmedTitle && trimmedTitle.length > 0
    ? `${CHART_TYPE_LABELS[chartType] ?? chartType} · ${rows.length}건`
    : `${rows.length}건`;

  return (
    <div className="my-2 rounded-lg border border-border bg-card overflow-hidden min-w-[min(400px,100%)]">
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border bg-muted/30">
        <span className="text-sm font-medium text-card-foreground" data-testid="inline-chart-title">
          {headerLabel}
        </span>
        <span className="text-xs text-muted-foreground ml-1" data-testid="inline-chart-sublabel">
          ({subLabel})
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

      {/* SQL 보기 — 인라인 펼침 대신 모달로 전체 SQL 표시 (#204).
          긴 CTE/서브쿼리도 줄바꿈+세로 스크롤로 확인 가능하도록 다이얼로그에서 표시한다. */}
      <div className="border-t border-border">
        <button
          type="button"
          data-testid="inline-chart-sql-toggle"
          className="flex w-full items-center gap-1.5 px-3 py-2 text-xs text-muted-foreground hover:text-foreground hover:bg-muted/30 transition-colors"
          onClick={() => setSqlDialogOpen(true)}
        >
          SQL 보기
        </button>
      </div>

      {/* SQL 모달 — 전체 SQL을 구문 강조 + 줄바꿈 + 세로 스크롤로 표시. 복사 버튼 제공. */}
      <Dialog open={sqlDialogOpen} onOpenChange={setSqlDialogOpen}>
        <DialogContent className="sm:max-w-[720px]">
          <DialogHeader>
            <DialogTitle>실행 SQL</DialogTitle>
            <DialogDescription className="sr-only">
              차트 생성에 사용된 전체 SQL 쿼리입니다.
            </DialogDescription>
          </DialogHeader>
          <div
            className="max-h-[60vh] overflow-auto rounded-md"
            data-testid="inline-chart-sql-dialog-content"
          >
            <SyntaxHighlighter
              style={codeStyle}
              language="sql"
              PreTag="div"
              wrapLongLines
              customStyle={{
                margin: 0,
                fontSize: '0.8125rem',
                borderRadius: '0.375rem',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              {sqlText}
            </SyntaxHighlighter>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setSqlDialogOpen(false)}>
              닫기
            </Button>
            <Button onClick={handleCopySql} data-testid="inline-chart-sql-copy">
              복사
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

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
