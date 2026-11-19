import axios from 'axios';
import type { ReactNode } from 'react';
import { Component, useMemo, useState } from 'react';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import SyntaxHighlighter from 'react-syntax-highlighter/dist/esm/prism-light';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { toast } from 'sonner';

import { analyticsApi } from '../../api/analytics';
import { extractApiError, handleApiError, isDuplicateName, NameRetryExhaustedError } from '../../lib/api-error';
import { createWithNumberedName, numberedName } from '../../lib/clone-name';
import { normalizeChartConfig } from '../../lib/normalize-chart-config';
import { MAX_DESCRIPTION_LENGTH } from '../../lib/validations/description';
import type { ChartConfig, ChartType } from '../../types/analytics';
import { findMissingChartColumns, getChartTypeLabel, isKnownChartType } from '../analytics/chart-missing-columns';
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
  // 차트 이름 중복 409 의 서버 문구 — 이름 칸 아래에 보인다(WD-824)
  const [nameError, setNameError] = useState<string | null>(null);

  async function handleSave() {
    if (!chartName.trim()) return;
    setSaving(true);
    setNameError(null);
    // 이 저장이 만든 쿼리 id — 차트 생성이 실패하면 지워 고아로 남기지 않는다(WD-824)
    let createdQueryId: number | null = null;
    // 차트 생성 요청을 보낸 뒤인지 — 이름 중복 409 가 쿼리 이름(자동 파생)에서 났는지 차트 이름에서 났는지 가른다(WD-824)
    let chartStep = false;
    try {
      // 쿼리 이름은 차트 이름에서 파생한 것이라 사용자가 정한 적이 없다 — 예전 고아 등으로 겹치면 「… (2)」 로 번호를 올려 다시 만든다(WD-824)
      const queryResponse = await createWithNumberedName(
        (attempt) => numberedName(`AI 생성: ${chartName}`, attempt),
        (queryName) =>
          analyticsApi.createQuery({
            name: queryName,
            description: description || undefined,
            sqlText: sql,
            isShared,
          }),
        // 소진은 사용자가 정한 이름이 아니라 쿼리 이름이라 토스트로 할 일을 안내한다(WD-837) — 목록 읽기는 없다(쿼리 목록이 커 한 번에 못 읽는다).
        // 지울 쿼리를 찾을 수 있게 실제 1회차 쿼리 이름(200자 자르기 반영)을 넣는다.
        {
          onExhausted: (firstName) =>
            new NameRetryExhaustedError(
              `저장 쿼리 이름을 정하지 못했습니다. 쓰지 않는 「${firstName}」 쿼리를 지우거나 차트 이름을 바꾼 뒤 다시 저장하세요.`,
            ),
        },
      );
      const savedQueryId = queryResponse.data.id;
      createdQueryId = savedQueryId;
      chartStep = true;
      await analyticsApi.createChart({
        name: chartName,
        description: description || undefined,
        savedQueryId,
        chartType,
        // AI 가 만든 config 는 타입이 어긋날 수 있다 — 서버 검사(WD-413)에 막히지 않게 화면과 같은 모양으로 맞춰 저장한다
        config: normalizeChartConfig(config),
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
      // 서버가 차트 생성을 거절한 경우에만 방금 만든 쿼리를 지운다(보상, WD-824) — 남기면 다음 저장이 그 쿼리 이름에 걸린다. 삭제 실패는 원래 오류를 가리지 않게 삼킨다.
      // 응답 없는 실패(시간 초과·연결 끊김)는 서버가 차트를 이미 만들었을 수 있고, chart.saved_query_id 가 ON DELETE CASCADE 라 쿼리를 지우면 그 차트도 사라지므로 지우지 않는다.
      if (createdQueryId != null && axios.isAxiosError(err) && err.response) {
        await analyticsApi.deleteQuery(createdQueryId).catch(() => undefined);
      }
      if (chartStep && isDuplicateName(err)) {
        // 차트 이름 중복 409 는 서버 문구를 이름 칸 아래에 보이고 토스트는 생략한다. 쿼리 이름 409(재시도 소진)는 사용자가 정한 이름이 아니라 아래 토스트로 알린다.
        setNameError(extractApiError(err, '이미 있는 이름입니다. 다른 이름을 입력하세요.'));
      } else {
        // 서버가 준 사유(예: 차트 설정 400)를 그대로 보인다
        handleApiError(err, '저장 중 오류가 발생했습니다.');
      }
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
              onChange={(e) => {
                setChartName(e.target.value);
                setNameError(null);
              }}
              aria-invalid={nameError != null}
              aria-describedby={nameError ? 'chart-name-error' : undefined}
            />
            {nameError && (
              <p id="chart-name-error" role="alert" className="text-sm text-destructive">
                {nameError}
              </p>
            )}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="chart-desc">설명 (선택)</Label>
            <Input
              id="chart-desc"
              placeholder="설명을 입력하세요"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={MAX_DESCRIPTION_LENGTH}
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
  const cannotSave = useMemo(
    // 모르는 차트 타입도 렌더러가 그리지 못하므로 「차트로 저장」을 숨긴다 — 저장하면 대시보드에서 영영 그려지지 않는다(WD-831)
    () => !isKnownChartType(chartType) || findMissingChartColumns(chartType, config, columns, rows).length > 0,
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
  // 유형명은 아는 타입일 때만 찾는다 — 'toString'·'constructor' 같은 상속 이름이 함수로 풀려 JSX 에 들어가지 않게(WD-831).
  const chartTypeLabel = getChartTypeLabel(chartType);
  const trimmedTitle = title?.trim();
  const headerLabel = trimmedTitle && trimmedTitle.length > 0
    ? trimmedTitle
    : chartTypeLabel;
  // title이 있으면 차트 유형명을 보조 라벨로 표시(맥락 유지). 없으면 행수만 표시.
  const subLabel = trimmedTitle && trimmedTitle.length > 0
    ? `${chartTypeLabel} · ${rows.length}건`
    : `${rows.length}건`;

  return (
    <div className="my-2 rounded-lg border border-border bg-card overflow-hidden min-w-[min(400px,100%)]">
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border bg-muted">
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
            // WD-826: 접근 이름에 쓰는 차트 제목
            title={title}
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
      {!cannotSave && (
        <div className="flex justify-end px-3 py-2 border-t border-border bg-muted">
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
