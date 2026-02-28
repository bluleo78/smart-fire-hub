import axios from 'axios';
import { AlertCircle, BarChart3,CheckCircle2, ExternalLink, Loader2, Play } from 'lucide-react';
import { lazy,Suspense, useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Card } from '../../../components/ui/card';
import { useExecuteQuery } from '../../../hooks/queries/useDatasets';
import type { ErrorResponse } from '../../../types/auth';
import type { DatasetColumnResponse, SqlQueryResponse } from '../../../types/dataset';
import { SqlQueryHistory } from './SqlQueryHistory';

// Lazy-loaded CodeMirror wrapper for code splitting
const CodeMirrorEditor = lazy(() =>
  import('./CodeMirrorEditor').then((m) => ({ default: m.CodeMirrorEditor }))
);

interface SqlQueryEditorProps {
  datasetId: number;
  columns: DatasetColumnResponse[];
}

export function SqlQueryEditor({ datasetId, columns }: SqlQueryEditorProps) {
  const navigate = useNavigate();
  const [sql, setSql] = useState('');
  const [result, setResult] = useState<SqlQueryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);

  const executeQuery = useExecuteQuery(datasetId);

  const handleExecute = useCallback(async () => {
    const trimmed = sql.trim();
    if (!trimmed) {
      toast.error('SQL 쿼리를 입력하세요.');
      return;
    }
    setError(null);
    setResult(null);
    try {
      const data = await executeQuery.mutateAsync({ sql: trimmed });
      setResult(data);
      if (data.error) {
        setError(data.error);
      }
    } catch (err) {
      if (axios.isAxiosError(err) && err.response?.data) {
        const errData = err.response.data as ErrorResponse;
        setError(errData.message || '쿼리 실행에 실패했습니다.');
      } else {
        setError('쿼리 실행에 실패했습니다.');
      }
    }
  }, [sql, executeQuery]);

  const handleLoadFromHistory = useCallback((historySql: string) => {
    setSql(historySql);
    setHistoryOpen(false);
  }, []);

  const columnNames = columns.map((c) => c.columnName);

  return (
    <Card className="p-4 space-y-3">
      {/* Toolbar */}
      <div className="flex items-center gap-2">
        <SqlQueryHistory
          datasetId={datasetId}
          open={historyOpen}
          onOpenChange={setHistoryOpen}
          onSelect={handleLoadFromHistory}
        />
        <div className="flex-1" />
        <span className="text-xs text-muted-foreground">Cmd+Enter로 실행</span>
        <Button
          size="sm"
          onClick={handleExecute}
          disabled={executeQuery.isPending || !sql.trim()}
        >
          {executeQuery.isPending ? (
            <Loader2 className="mr-1 h-4 w-4 animate-spin" />
          ) : (
            <Play className="mr-1 h-4 w-4" />
          )}
          실행
        </Button>
      </div>

      {/* Editor */}
      <Suspense
        fallback={
          <div className="flex items-center justify-center h-[150px] border rounded-md bg-muted/30">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        }
      >
        <CodeMirrorEditor
          value={sql}
          onChange={setSql}
          onExecute={handleExecute}
          columnNames={columnNames}
        />
      </Suspense>

      {/* Result panel */}
      {executeQuery.isPending && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground py-2">
          <Loader2 className="h-4 w-4 animate-spin" />
          쿼리 실행 중...
        </div>
      )}

      {error && (
        <div className="flex items-start gap-2 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
          <AlertCircle className="h-4 w-4 mt-0.5 flex-shrink-0" />
          <pre className="whitespace-pre-wrap break-all font-mono text-xs">{error}</pre>
        </div>
      )}

      {result && !error && (
        <>
          {result.queryType === 'SELECT' ? (
            <div className="space-y-2">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Badge variant="secondary" className="text-xs">{result.queryType}</Badge>
                <span>{result.rows.length}행 반환</span>
                <span>({result.executionTimeMs}ms)</span>
              </div>
              {result.rows.length > 0 ? (
                <div className="rounded-md border overflow-x-auto max-h-[300px] overflow-y-auto">
                  <table className="w-full text-sm" style={{ borderCollapse: 'collapse' }}>
                    <thead className="bg-muted/50 sticky top-0">
                      <tr>
                        {result.columns.map((col) => (
                          <th
                            key={col}
                            className="text-left px-3 py-2 font-medium text-xs whitespace-nowrap border-b"
                          >
                            {col}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {result.rows.map((row, i) => (
                        <tr key={i} className="hover:bg-muted/30">
                          {result.columns.map((col) => (
                            <td
                              key={col}
                              className="px-3 py-1.5 text-xs whitespace-nowrap border-b max-w-[300px] truncate"
                              title={row[col] != null ? String(row[col]) : undefined}
                            >
                              {row[col] == null ? (
                                <span className="text-muted-foreground italic">NULL</span>
                              ) : (
                                String(row[col])
                              )}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">결과가 없습니다.</p>
              )}
            </div>
          ) : (
            <div className="flex items-center gap-2 p-3 rounded-md bg-green-50 dark:bg-green-950/30 text-green-700 dark:text-green-400 text-sm">
              <CheckCircle2 className="h-4 w-4 flex-shrink-0" />
              <span>
                {result.affectedRows}행이 영향 받았습니다.
                <span className="text-muted-foreground ml-2">({result.executionTimeMs}ms)</span>
              </span>
            </div>
          )}

          <div className="flex gap-2 mt-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                navigate(
                  `/analytics/queries/new?datasetId=${datasetId}&sql=${encodeURIComponent(sql)}`
                )
              }
            >
              <ExternalLink size={14} className="mr-1" />
              분석 쿼리로 열기
            </Button>
            {result.queryType === 'SELECT' && result.rows.length > 0 && (
              <Button
                variant="outline"
                size="sm"
                onClick={() =>
                  navigate(
                    `/analytics/charts/new?queryId=adhoc&sql=${encodeURIComponent(sql)}&datasetId=${datasetId}`
                  )
                }
              >
                <BarChart3 size={14} className="mr-1" />
                차트로 만들기
              </Button>
            )}
          </div>
        </>
      )}
    </Card>
  );
}
