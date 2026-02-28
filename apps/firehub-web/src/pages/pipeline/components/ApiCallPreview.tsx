import { ChevronDown, ChevronRight, Copy } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';

interface ApiCallPreviewProps {
  result: {
    success: boolean;
    rawJson: string | null;
    rows: Array<Record<string, unknown>>;
    columns: string[];
    totalExtractedRows: number;
    errorMessage: string | null;
  };
}

interface JsonTreeNodeProps {
  value: unknown;
  path: string;
  depth: number;
}

function JsonTreeNode({ value, path, depth }: JsonTreeNodeProps) {
  const [expanded, setExpanded] = useState(depth < 2);

  const handleCopyPath = (e: React.MouseEvent, jsonPath: string) => {
    e.stopPropagation();
    navigator.clipboard.writeText(jsonPath).then(() => {
      toast.success(`JSONPath가 복사되었습니다: ${jsonPath}`);
    });
  };

  if (value === null) {
    return <span className="text-gray-400">null</span>;
  }
  if (typeof value === 'boolean') {
    return <span className="text-blue-500">{value ? 'true' : 'false'}</span>;
  }
  if (typeof value === 'number') {
    return <span className="text-green-600">{String(value)}</span>;
  }
  if (typeof value === 'string') {
    return <span className="text-amber-600">&quot;{value}&quot;</span>;
  }

  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-gray-500">[]</span>;
    return (
      <span>
        <button
          className="text-gray-500 hover:text-foreground cursor-pointer"
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? '▼' : '▶'} [{value.length}]
        </button>
        {expanded && (
          <div style={{ paddingLeft: `${(depth + 1) * 12}px` }}>
            {value.slice(0, 20).map((item, i) => (
              <div key={i} className="flex items-start gap-1">
                <button
                  className="text-blue-400 hover:text-blue-600 text-xs shrink-0"
                  title={`${path}[${i}] 복사`}
                  onClick={(e) => handleCopyPath(e, `${path}[${i}]`)}
                >
                  <Copy className="h-3 w-3" />
                </button>
                <span className="text-gray-400 shrink-0">[{i}]:</span>
                <JsonTreeNode value={item} path={`${path}[${i}]`} depth={depth + 1} />
              </div>
            ))}
            {value.length > 20 && (
              <div className="text-gray-400 text-xs">... {value.length - 20}개 더</div>
            )}
          </div>
        )}
      </span>
    );
  }

  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return <span className="text-gray-500">{'{}'}</span>;
    return (
      <span>
        <button
          className="text-gray-500 hover:text-foreground cursor-pointer"
          onClick={() => setExpanded(!expanded)}
        >
          {expanded ? '▼' : '▶'} {'{'}
          {entries.length}
          {'}'}
        </button>
        {expanded && (
          <div style={{ paddingLeft: `${(depth + 1) * 12}px` }}>
            {entries.map(([key, val]) => {
              const childPath = path ? `${path}.${key}` : `$.${key}`;
              return (
                <div key={key} className="flex items-start gap-1">
                  <button
                    className="text-blue-400 hover:text-blue-600 text-xs shrink-0"
                    title={`${childPath} 복사`}
                    onClick={(e) => handleCopyPath(e, childPath)}
                  >
                    <Copy className="h-3 w-3" />
                  </button>
                  <span className="text-purple-600 shrink-0">&quot;{key}&quot;</span>
                  <span className="text-gray-400 shrink-0">:</span>
                  <JsonTreeNode value={val} path={childPath} depth={depth + 1} />
                </div>
              );
            })}
          </div>
        )}
      </span>
    );
  }

  return <span>{String(value)}</span>;
}

export default function ApiCallPreview({ result }: ApiCallPreviewProps) {
  const [jsonOpen, setJsonOpen] = useState(false);

  if (!result.success) {
    return (
      <div className="mt-3 rounded-md border border-destructive bg-destructive/10 p-3">
        <p className="text-sm font-medium text-destructive">호출 실패</p>
        <p className="text-xs text-destructive mt-1">{result.errorMessage ?? '알 수 없는 오류'}</p>
      </div>
    );
  }

  const displayRows = result.rows.slice(0, 5);
  let parsedJson: unknown = null;
  try {
    if (result.rawJson) parsedJson = JSON.parse(result.rawJson);
  } catch {
    // ignore parse error
  }

  return (
    <div className="mt-3 space-y-3">
      <p className="text-xs text-muted-foreground">
        총 <span className="font-medium text-foreground">{result.totalExtractedRows}</span>개 행
        추출 (최대 5개 표시)
      </p>

      {/* JSON Tree */}
      <Collapsible open={jsonOpen} onOpenChange={setJsonOpen}>
        <CollapsibleTrigger asChild>
          <Button variant="ghost" size="sm" className="h-7 px-2 text-xs w-full justify-start">
            {jsonOpen ? (
              <ChevronDown className="h-3 w-3 mr-1" />
            ) : (
              <ChevronRight className="h-3 w-3 mr-1" />
            )}
            원본 JSON 보기
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          <div className="rounded border bg-muted/40 p-2 overflow-auto max-h-48">
            {parsedJson !== null ? (
              <pre className="text-xs font-mono">
                <JsonTreeNode value={parsedJson} path="$" depth={0} />
              </pre>
            ) : (
              <pre className="text-xs font-mono text-muted-foreground">
                {result.rawJson ?? '(없음)'}
              </pre>
            )}
          </div>
        </CollapsibleContent>
      </Collapsible>

      {/* Mapped Data Table */}
      {result.columns.length === 0 || displayRows.length === 0 ? (
        <p className="text-xs text-muted-foreground text-center py-2">
          매핑된 데이터가 없습니다
        </p>
      ) : (
        <div className="overflow-auto rounded border">
          <table className="w-full text-xs border-collapse">
            <thead>
              <tr className="bg-muted/60">
                {result.columns.map((col) => (
                  <th
                    key={col}
                    className="border-b px-2 py-1 text-left font-medium whitespace-nowrap"
                  >
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {displayRows.map((row, i) => (
                <tr key={i} className="even:bg-muted/20">
                  {result.columns.map((col) => (
                    <td key={col} className="border-b px-2 py-1 whitespace-nowrap">
                      {row[col] == null ? (
                        <span className="text-muted-foreground">null</span>
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
      )}
    </div>
  );
}
