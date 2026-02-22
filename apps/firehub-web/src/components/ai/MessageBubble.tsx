import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import SyntaxHighlighter from 'react-syntax-highlighter/dist/esm/prism-light';
import sql from 'react-syntax-highlighter/dist/esm/languages/prism/sql';
import python from 'react-syntax-highlighter/dist/esm/languages/prism/python';
import javascript from 'react-syntax-highlighter/dist/esm/languages/prism/javascript';
import typescript from 'react-syntax-highlighter/dist/esm/languages/prism/typescript';
import json from 'react-syntax-highlighter/dist/esm/languages/prism/json';
import bash from 'react-syntax-highlighter/dist/esm/languages/prism/bash';
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism';
import type { AIMessage, AIToolCall } from '../../types/ai';
import { cn } from '../../lib/utils';

SyntaxHighlighter.registerLanguage('sql', sql);
SyntaxHighlighter.registerLanguage('python', python);
SyntaxHighlighter.registerLanguage('javascript', javascript);
SyntaxHighlighter.registerLanguage('typescript', typescript);
SyntaxHighlighter.registerLanguage('json', json);
SyntaxHighlighter.registerLanguage('bash', bash);

const codeStyle = oneDark as Record<string, React.CSSProperties>;

const TOOL_LABELS: Record<string, { label: string; icon: string }> = {
  list_categories: { label: '카테고리 목록 조회', icon: '\uD83D\uDCC2' },
  create_category: { label: '카테고리 생성', icon: '\uD83D\uDCC2' },
  update_category: { label: '카테고리 수정', icon: '\uD83D\uDCC2' },
  list_datasets: { label: '데이터셋 목록 조회', icon: '\uD83D\uDDC3\uFE0F' },
  get_dataset: { label: '데이터셋 상세 조회', icon: '\uD83D\uDD0D' },
  query_dataset_data: { label: '데이터 조회', icon: '\uD83D\uDD0D' },
  get_dataset_columns: { label: '컬럼 정보 조회', icon: '\uD83D\uDD0D' },
  create_dataset: { label: '데이터셋 생성', icon: '\u2795' },
  update_dataset: { label: '데이터셋 수정', icon: '\u270F\uFE0F' },
  execute_sql_query: { label: 'SQL 쿼리 실행', icon: '\uD83D\uDCBE' },
  add_row: { label: '데이터 추가', icon: '\u2795' },
  add_rows: { label: '데이터 일괄 추가', icon: '\u2795' },
  update_row: { label: '데이터 수정', icon: '\u270F\uFE0F' },
  delete_rows: { label: '데이터 삭제', icon: '\uD83D\uDDD1\uFE0F' },
  truncate_dataset: { label: '전체 데이터 삭제', icon: '\uD83D\uDDD1\uFE0F' },
  get_row_count: { label: '행 수 조회', icon: '\uD83D\uDD22' },
  replace_dataset_data: { label: '전체 데이터 교체', icon: '\uD83D\uDD04' },
  list_pipelines: { label: '파이프라인 목록 조회', icon: '\u2699\uFE0F' },
  get_pipeline: { label: '파이프라인 상세 조회', icon: '\u2699\uFE0F' },
  create_pipeline: { label: '파이프라인 생성', icon: '\u2699\uFE0F' },
  update_pipeline: { label: '파이프라인 수정', icon: '\u2699\uFE0F' },
  delete_pipeline: { label: '파이프라인 삭제', icon: '\uD83D\uDDD1\uFE0F' },
  preview_api_call: { label: 'API 호출 미리보기', icon: '\uD83D\uDD0D' },
  execute_pipeline: { label: '파이프라인 실행', icon: '\u25B6\uFE0F' },
  get_execution_status: { label: '실행 상태 조회', icon: '\uD83D\uDCCA' },
  list_triggers: { label: '트리거 목록 조회', icon: '\u23F0' },
  create_trigger: { label: '트리거 생성', icon: '\u23F0' },
  update_trigger: { label: '트리거 수정', icon: '\u23F0' },
  delete_trigger: { label: '트리거 삭제', icon: '\uD83D\uDDD1\uFE0F' },
  list_api_connections: { label: 'API 연결 목록 조회', icon: '\uD83D\uDD0C' },
  get_api_connection: { label: 'API 연결 상세 조회', icon: '\uD83D\uDD0C' },
  create_api_connection: { label: 'API 연결 생성', icon: '\uD83D\uDD0C' },
  update_api_connection: { label: 'API 연결 수정', icon: '\uD83D\uDD0C' },
  delete_api_connection: { label: 'API 연결 삭제', icon: '\uD83D\uDDD1\uFE0F' },
  list_imports: { label: '임포트 이력 조회', icon: '\uD83D\uDCE5' },
  get_dashboard: { label: '대시보드 통계 조회', icon: '\uD83D\uDCCA' },
};

function getToolDisplay(name: string): { label: string; icon: string } {
  const cleanName = name.replace(/^mcp__firehub__/, '');
  return TOOL_LABELS[cleanName] ?? { label: cleanName, icon: '\uD83D\uDD27' };
}

function formatToolDetail(input: Record<string, unknown>): string | null {
  const parts: string[] = [];
  if (input.datasetId) parts.push(`#${input.datasetId}`);
  if (input.sql) {
    const sql = String(input.sql).trim();
    parts.push(sql.length > 60 ? sql.slice(0, 60) + '...' : sql);
  }
  if (input.pipelineId) parts.push(`Pipeline #${input.pipelineId}`);
  if (input.rows && Array.isArray(input.rows)) parts.push(`${input.rows.length}건`);
  if (input.rowIds && Array.isArray(input.rowIds)) parts.push(`${input.rowIds.length}건`);
  return parts.length > 0 ? parts.join(' \u00B7 ') : null;
}

function formatToolResult(result: string): string | null {
  try {
    const parsed = JSON.parse(result);
    if (parsed.affectedRows !== undefined) return `${parsed.affectedRows}건 처리`;
    if (parsed.deletedCount !== undefined) return `${parsed.deletedCount}건 삭제`;
    if (parsed.rowCount !== undefined) return `${parsed.rowCount}건`;
    if (parsed.insertedCount !== undefined) return `${parsed.insertedCount}건 추가`;
    if (parsed.totalRows !== undefined) return `총 ${parsed.totalRows}행`;
    if (parsed.rows && Array.isArray(parsed.rows)) return `${parsed.rows.length}건 조회`;
    return null;
  } catch {
    return null;
  }
}

function ToolCallDisplay({ toolCall }: { toolCall: AIToolCall }) {
  const { label, icon } = getToolDisplay(toolCall.name);
  const detail = formatToolDetail(toolCall.input);
  const hasResult = toolCall.result !== undefined;
  const resultSummary = hasResult && toolCall.result ? formatToolResult(toolCall.result) : null;

  return (
    <div className="my-1 flex items-center gap-1.5 rounded border border-border/50 bg-background/50 px-2 py-1 text-xs">
      <span>{icon}</span>
      <span className="font-medium">{label}</span>
      {detail && <span className="text-muted-foreground truncate">{detail}</span>}
      {hasResult && (
        <span className="ml-auto shrink-0 text-green-600 dark:text-green-400">
          {resultSummary ?? '\u2713 \uC644\uB8CC'}
        </span>
      )}
      {!hasResult && (
        <span className="ml-auto shrink-0 animate-pulse text-yellow-600 dark:text-yellow-400">
          \uC2E4\uD589 \uC911...
        </span>
      )}
    </div>
  );
}

interface MessageBubbleProps {
  message: Partial<AIMessage>;
}

export function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === 'user';
  const isSystem = message.role === 'system';
  const hasContent = !!message.content;
  const hasToolCalls = !!(message.toolCalls && message.toolCalls.length > 0);

  if (isSystem) {
    return (
      <div className="flex items-center gap-2 py-2">
        <div className="h-px flex-1 bg-border" />
        <span className="shrink-0 text-xs text-muted-foreground">{message.content}</span>
        <div className="h-px flex-1 bg-border" />
      </div>
    );
  }

  return (
    <div className={cn('flex w-full', isUser ? 'justify-end' : 'justify-start')}>
      <div className={cn(
        'rounded-lg px-3 py-2 text-sm',
        isUser ? 'max-w-[85%] bg-primary text-primary-foreground' : 'max-w-[85%] min-w-0 bg-muted overflow-hidden'
      )}>
        {isUser ? (
          <p className="whitespace-pre-wrap">{message.content}</p>
        ) : (
          <>
            {hasToolCalls && (
              <div className="space-y-0.5">
                {message.toolCalls!.map((tc, i) => (
                  <ToolCallDisplay key={i} toolCall={tc} />
                ))}
              </div>
            )}
            {hasContent && (
              <div className={cn(
                'prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-2 prose-ul:my-1 prose-ol:my-1 prose-li:my-0 prose-pre:my-2 prose-table:my-0',
                hasToolCalls && 'mt-2',
              )}>
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    table({ children }) {
                      return (
                        <div className="overflow-x-auto my-2">
                          <table className="text-xs whitespace-nowrap">{children}</table>
                        </div>
                      );
                    },
                    code({ className, children }) {
                      const match = /language-(\w+)/.exec(className || '');
                      return match ? (
                        <SyntaxHighlighter
                          style={codeStyle}
                          language={match[1]}
                          PreTag="div"
                        >
                          {String(children).replace(/\n$/, '')}
                        </SyntaxHighlighter>
                      ) : (
                        <code className={className}>
                          {children}
                        </code>
                      );
                    },
                  }}
                >
                  {message.content}
                </ReactMarkdown>
              </div>
            )}
          </>
        )}
        {message.timestamp && (
          <p className="mt-1 text-xs opacity-70">
            {new Date(message.timestamp).toLocaleTimeString('ko-KR', {
              hour: '2-digit',
              minute: '2-digit',
            })}
          </p>
        )}
      </div>
    </div>
  );
}
