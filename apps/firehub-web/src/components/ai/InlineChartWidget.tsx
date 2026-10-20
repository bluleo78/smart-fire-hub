import type { ReactNode } from 'react';
import { Component, useMemo, useState } from 'react';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import SyntaxHighlighter from 'react-syntax-highlighter/dist/esm/prism-light';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { toast } from 'sonner';

import { analyticsApi } from '../../api/analytics';
import type { ChartConfig, ChartType } from '../../types/analytics';
import { CHART_TYPE_LABELS } from '../../types/analytics';
import { findMissingChartColumns } from '../analytics/chart-missing-columns';
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
import { normalizeChartRows } from './chart-numeric-rows';

SyntaxHighlighter.registerLanguage('sql', sql);

const codeStyle = oneDark as Record<string, React.CSSProperties>;

/**
 * AI 챗 인라인 차트의 컬럼 누락 해결 문구(WD-88). AI 챗 차트에는 저장된 쿼리도 차트 편집 화면도 없으므로
 * 위젯·편집기용 기본 문구("차트 편집에서 축을 다시 고르세요") 대신 AI 에게 다시 요청하라고 안내한다.
 */
const AI_MISSING_COLUMNS_HINT = '차트 설정의 컬럼 이름이 결과와 맞지 않습니다. AI에게 차트를 다시 그려 달라고 요청하세요.';

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

  // WD-88: 설정 컬럼이 결과에 없으면 ChartRenderer 가 차트 대신 안내를 보여 준다. 이때 「차트로 저장」을 열어 두면
  // 깨진 설정이 그대로 저장되므로 버튼을 숨긴다(표 위젯 내보내기처럼 쓸 수 없는 동작은 숨기는 기존 관례).
  // ChartRenderer 와 같은 함수·같은 입력(원본 config, columns, rows)으로 판단해 두 곳의 결론이 어긋나지 않게 한다.
  const hasMissingColumns = useMemo(
    () => findMissingChartColumns(chartType, config, columns, rows).length > 0,
    [chartType, config, columns, rows],
  );

  // WD-104: 데이터셋 조회·SQL 탭 결과를 옮긴 rows 는 소수가 문자열("1.5")이라, recharts 로 넘기기 직전에 값 축 칸의
  // 숫자 문자열만 Number 로 바꾼다(파이 조각이 사라지는 등). 카테고리(x축) 칸은 그대로 둔다 — chart-numeric-rows.ts 참고.
  const chartRows = useMemo(
    () => normalizeChartRows(chartType, config, columns, rows),
    [chartType, config, columns, rows],
  );

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
            data={chartRows}
            columns={columns}
            height={300}
            missingColumnsHint={AI_MISSING_COLUMNS_HINT}
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
            className="max-h-[60vh] overflow-auto overscroll-contain rounded-md"
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

      {/* Footer — 컬럼 누락 안내가 보일 때는 깨진 설정을 저장하지 못하게 숨긴다(WD-88) */}
      {!hasMissingColumns && (
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
      )}

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
