import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  useSavedQuery,
  useCreateSavedQuery,
  useUpdateSavedQuery,
  useExecuteAnalyticsQuery,
  useSchemaInfo,
  useQueryFolders,
} from '../../hooks/queries/useAnalytics';
import { EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { sql, PostgreSQL } from '@codemirror/lang-sql';
import { oneDark } from '@codemirror/theme-one-dark';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { searchKeymap } from '@codemirror/search';
import { autocompletion } from '@codemirror/autocomplete';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '../../components/ui/dialog';
import { Switch } from '../../components/ui/switch';
import { Skeleton } from '../../components/ui/skeleton';
import {
  ArrowLeft,
  Play,
  Save,
  ChevronRight,
  ChevronDown,
  BarChart2,
  Loader2,
  Table2,
  Columns,
} from 'lucide-react';
import { toast } from 'sonner';
import { handleApiError } from '../../lib/api-error';
import type { AnalyticsQueryResult, SchemaTable } from '../../types/analytics';
import { cn } from '../../lib/utils';

// ============================================================
// Inline SQL Editor with schema-aware autocomplete
// ============================================================

interface AnalyticsSqlEditorProps {
  value: string;
  onChange: (value: string) => void;
  onExecute: () => void;
  schema: Record<string, string[]>;
}

function AnalyticsSqlEditor({ value, onChange, onExecute, schema }: AnalyticsSqlEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const onExecuteRef = useRef(onExecute);

  onChangeRef.current = onChange;
  onExecuteRef.current = onExecute;

  useEffect(() => {
    if (!containerRef.current) return;

    const state = EditorState.create({
      doc: value,
      extensions: [
        sql({ dialect: PostgreSQL, schema }),
        oneDark,
        history(),
        autocompletion(),
        keymap.of([
          ...defaultKeymap,
          ...historyKeymap,
          ...searchKeymap,
          {
            key: 'Mod-Enter',
            run: () => {
              onExecuteRef.current();
              return true;
            },
          },
        ]),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            onChangeRef.current(update.state.doc.toString());
          }
        }),
        EditorView.theme({
          '&': {
            fontSize: '13px',
            border: '1px solid hsl(var(--border))',
            borderRadius: '6px',
            cursor: 'text',
          },
          '.cm-editor': {
            minHeight: '200px',
            maxHeight: '350px',
          },
          '.cm-scroller': {
            overflow: 'auto',
            minHeight: '200px',
            maxHeight: '350px',
          },
          '.cm-content': {
            minHeight: '190px',
          },
        }),
      ],
    });

    const view = new EditorView({
      state,
      parent: containerRef.current,
    });

    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Sync external value changes (e.g. when loading a saved query)
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const currentDoc = view.state.doc.toString();
    if (currentDoc !== value) {
      view.dispatch({
        changes: { from: 0, to: currentDoc.length, insert: value },
      });
    }
  }, [value]);

  return <div ref={containerRef} />;
}

// ============================================================
// Schema Explorer (left side panel)
// ============================================================

interface SchemaExplorerProps {
  tables: SchemaTable[];
  onInsertTable: (tableName: string) => void;
}

function SchemaExplorer({ tables, onInsertTable }: SchemaExplorerProps) {
  const [expandedTables, setExpandedTables] = useState<Set<string>>(new Set());

  const toggleTable = (tableName: string) => {
    setExpandedTables((prev) => {
      const next = new Set(prev);
      if (next.has(tableName)) {
        next.delete(tableName);
      } else {
        next.add(tableName);
      }
      return next;
    });
  };

  if (tables.length === 0) {
    return (
      <div className="p-3 text-xs text-muted-foreground">
        테이블 정보를 불러오는 중...
      </div>
    );
  }

  return (
    <div className="overflow-y-auto h-full">
      {tables.map((table) => {
        const isExpanded = expandedTables.has(table.tableName);
        return (
          <div key={table.tableName}>
            <button
              className="flex items-center gap-1.5 w-full px-3 py-1.5 text-xs hover:bg-muted/50 transition-colors text-left"
              onClick={() => toggleTable(table.tableName)}
            >
              {isExpanded ? (
                <ChevronDown className="h-3 w-3 shrink-0 text-muted-foreground" />
              ) : (
                <ChevronRight className="h-3 w-3 shrink-0 text-muted-foreground" />
              )}
              <Table2 className="h-3 w-3 shrink-0 text-blue-500" />
              <span
                className="font-medium truncate flex-1"
                title={table.tableName}
              >
                {table.tableName}
              </span>
              <button
                className="shrink-0 text-muted-foreground hover:text-foreground text-[10px] bg-muted px-1 rounded"
                onClick={(e) => {
                  e.stopPropagation();
                  onInsertTable(table.tableName);
                }}
                title="에디터에 삽입"
              >
                삽입
              </button>
            </button>
            {isExpanded && (
              <div className="pl-7">
                {table.columns.map((col) => (
                  <div
                    key={col.columnName}
                    className="flex items-center gap-1.5 px-3 py-1 text-xs text-muted-foreground hover:text-foreground hover:bg-muted/30 transition-colors"
                  >
                    <Columns className="h-3 w-3 shrink-0" />
                    <span className="truncate flex-1" title={col.columnName}>
                      {col.displayName || col.columnName}
                    </span>
                    <span className="shrink-0 text-[10px] text-muted-foreground/70">
                      {col.dataType}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ============================================================
// Result Table
// ============================================================

interface ResultTableProps {
  result: AnalyticsQueryResult;
}

function ResultTable({ result }: ResultTableProps) {
  if (result.error) {
    return (
      <div className="rounded-md border border-destructive/50 bg-destructive/10 p-4">
        <p className="text-sm font-medium text-destructive">쿼리 오류</p>
        <p className="text-sm text-destructive/80 mt-1 font-mono whitespace-pre-wrap">
          {result.error}
        </p>
      </div>
    );
  }

  if (result.columns.length === 0) {
    return (
      <div className="rounded-md border p-4 text-center text-sm text-muted-foreground">
        {result.queryType === 'SELECT'
          ? '결과가 없습니다.'
          : `${result.affectedRows}개 행이 처리되었습니다. (${result.executionTimeMs}ms)`}
      </div>
    );
  }

  return (
    <div className="rounded-md border overflow-auto" style={{ maxHeight: 320 }}>
      <table className="w-full text-sm border-collapse">
        <thead className="sticky top-0 bg-muted/80 backdrop-blur-sm">
          <tr>
            {result.columns.map((col) => (
              <th
                key={col}
                className="px-3 py-2 text-left font-semibold whitespace-nowrap border-b text-xs"
              >
                {col}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {result.rows.map((row, i) => (
            <tr key={i} className="hover:bg-muted/40 transition-colors">
              {result.columns.map((col) => {
                const val = row[col];
                return (
                  <td
                    key={col}
                    className="px-3 py-1.5 border-b whitespace-nowrap max-w-[200px] truncate"
                    title={val != null ? String(val) : undefined}
                  >
                    {val == null ? (
                      <span className="text-muted-foreground italic text-xs">NULL</span>
                    ) : (
                      String(val)
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ============================================================
// Save Dialog
// ============================================================

interface SaveDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  name: string;
  onNameChange: (v: string) => void;
  description: string;
  onDescriptionChange: (v: string) => void;
  folder: string;
  onFolderChange: (v: string) => void;
  isShared: boolean;
  onIsSharedChange: (v: boolean) => void;
  folders: string[];
  onSave: () => void;
  isSaving: boolean;
  isEdit: boolean;
}

function SaveDialog({
  open,
  onOpenChange,
  name,
  onNameChange,
  description,
  onDescriptionChange,
  folder,
  onFolderChange,
  isShared,
  onIsSharedChange,
  folders,
  onSave,
  isSaving,
  isEdit,
}: SaveDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{isEdit ? '쿼리 수정' : '쿼리 저장'}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="query-name">이름 *</Label>
            <Input
              id="query-name"
              value={name}
              onChange={(e) => onNameChange(e.target.value)}
              placeholder="쿼리 이름을 입력하세요"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="query-description">설명</Label>
            <Input
              id="query-description"
              value={description}
              onChange={(e) => onDescriptionChange(e.target.value)}
              placeholder="선택사항"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="query-folder">폴더</Label>
            <Select
              value={folder || '__none__'}
              onValueChange={(v) => onFolderChange(v === '__none__' ? '' : v)}
            >
              <SelectTrigger id="query-folder">
                <SelectValue placeholder="폴더 선택 (선택사항)" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">폴더 없음</SelectItem>
                {folders.map((f) => (
                  <SelectItem key={f} value={f}>
                    {f}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center justify-between">
            <Label htmlFor="query-shared" className="cursor-pointer">
              공유 쿼리
            </Label>
            <Switch
              id="query-shared"
              checked={isShared}
              onCheckedChange={onIsSharedChange}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            취소
          </Button>
          <Button onClick={onSave} disabled={!name.trim() || isSaving}>
            {isSaving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            {isEdit ? '수정' : '저장'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ============================================================
// QueryEditorPage
// ============================================================

export default function QueryEditorPage() {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();

  const queryId = id ? parseInt(id, 10) : null;
  const isNew = !queryId;

  // URL params for "open from DatasetDataTab"
  const initialSql = searchParams.get('sql')
    ? decodeURIComponent(searchParams.get('sql')!)
    : '';

  const [sql, setSql] = useState(initialSql);
  const [result, setResult] = useState<AnalyticsQueryResult | null>(null);
  const [saveDialogOpen, setSaveDialogOpen] = useState(false);
  const [saveForm, setSaveForm] = useState({
    name: '',
    description: '',
    folder: '',
    isShared: false,
  });

  // Sidebar panel
  const [sidebarOpen, setSidebarOpen] = useState(true);

  const { data: savedQuery, isLoading: queryLoading } = useSavedQuery(queryId);
  const { data: schemaInfo } = useSchemaInfo();
  const { data: foldersData } = useQueryFolders();

  const executeQuery = useExecuteAnalyticsQuery();
  const createSavedQuery = useCreateSavedQuery();
  const updateSavedQuery = useUpdateSavedQuery();

  const folders = foldersData ?? [];
  const tables = schemaInfo?.tables ?? [];

  // Build CodeMirror schema map: { tableName: [columnName, ...] }
  const cmSchema = useMemo(() => {
    const schemaTables = schemaInfo?.tables ?? [];
    const map: Record<string, string[]> = {};
    for (const table of schemaTables) {
      map[table.tableName] = table.columns.map((c) => c.columnName);
    }
    return map;
  }, [schemaInfo]);

  // Load saved query into editor when fetched
  useEffect(() => {
    if (savedQuery && !initialSql) {
      setSql(savedQuery.sqlText);
      setSaveForm({
        name: savedQuery.name,
        description: savedQuery.description ?? '',
        folder: savedQuery.folder ?? '',
        isShared: savedQuery.isShared,
      });
    }
  }, [savedQuery, initialSql]);

  // Pre-fill save form name for new queries
  useEffect(() => {
    if (isNew && !saveForm.name) {
      setSaveForm((prev) => ({ ...prev, name: '새 쿼리' }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isNew]);

  const handleExecute = useCallback(async () => {
    if (!sql.trim()) {
      toast.error('실행할 SQL을 입력하세요.');
      return;
    }
    try {
      const res = await executeQuery.mutateAsync({ sql, maxRows: 1000 });
      setResult(res);
      if (res.error) {
        toast.error('쿼리 실행 오류');
      } else {
        toast.success(
          res.queryType === 'SELECT'
            ? `${res.rows.length}행 반환 (${res.executionTimeMs}ms)`
            : `${res.affectedRows}행 처리됨 (${res.executionTimeMs}ms)`
        );
      }
    } catch (error) {
      handleApiError(error, '쿼리 실행에 실패했습니다.');
    }
  }, [sql, executeQuery]);

  const handleSaveClick = () => {
    if (savedQuery) {
      setSaveForm({
        name: savedQuery.name,
        description: savedQuery.description ?? '',
        folder: savedQuery.folder ?? '',
        isShared: savedQuery.isShared,
      });
    }
    setSaveDialogOpen(true);
  };

  const handleSave = async () => {
    if (!saveForm.name.trim()) return;

    try {
      if (isNew) {
        const created = await createSavedQuery.mutateAsync({
          name: saveForm.name,
          description: saveForm.description || undefined,
          sqlText: sql,
          folder: saveForm.folder || null,
          isShared: saveForm.isShared,
        });
        toast.success(`쿼리 "${created.name}" 저장 완료`);
        setSaveDialogOpen(false);
        navigate(`/analytics/queries/${created.id}`, { replace: true });
      } else {
        await updateSavedQuery.mutateAsync({
          id: queryId!,
          data: {
            name: saveForm.name,
            description: saveForm.description || undefined,
            sqlText: sql,
            folder: saveForm.folder || null,
            isShared: saveForm.isShared,
          },
        });
        toast.success('쿼리가 수정되었습니다.');
        setSaveDialogOpen(false);
      }
    } catch (error) {
      handleApiError(error, '쿼리 저장에 실패했습니다.');
    }
  };

  // Insert table name at cursor (append to current sql for simplicity)
  const handleInsertTable = (tableName: string) => {
    setSql((prev) => {
      const trimmed = prev.trimEnd();
      return trimmed ? `${trimmed}\n${tableName}` : tableName;
    });
  };

  if (!isNew && queryLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  const isSaving = createSavedQuery.isPending || updateSavedQuery.isPending;
  const isRunning = executeQuery.isPending;

  return (
    <div className="flex flex-col h-[calc(100vh-120px)] min-h-0 gap-0">
      {/* Toolbar */}
      <div className="flex items-center gap-3 pb-4 flex-wrap">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => navigate('/analytics/queries')}
          className="gap-1.5"
        >
          <ArrowLeft className="h-4 w-4" />
          목록
        </Button>

        <div className="flex-1 min-w-0">
          {savedQuery ? (
            <div className="flex items-center gap-2">
              <span className="font-semibold truncate">{savedQuery.name}</span>
              {savedQuery.folder && (
                <Badge variant="outline" className="text-xs">
                  {savedQuery.folder}
                </Badge>
              )}
              {savedQuery.isShared && (
                <Badge variant="secondary" className="text-xs">
                  공유됨
                </Badge>
              )}
            </div>
          ) : (
            <span className="font-semibold text-muted-foreground">새 쿼리</span>
          )}
        </div>

        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={handleSaveClick}
            disabled={isSaving}
            className="gap-1.5"
          >
            {isSaving ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Save className="h-4 w-4" />
            )}
            저장
          </Button>
          <Button
            size="sm"
            onClick={handleExecute}
            disabled={isRunning || !sql.trim()}
            className="gap-1.5"
          >
            {isRunning ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <Play className="h-4 w-4" />
            )}
            실행
          </Button>
        </div>
      </div>

      {/* Main area: sidebar + editor + results */}
      <div className="flex flex-1 min-h-0 gap-3">
        {/* Schema Explorer Sidebar */}
        <div
          className={cn(
            'border rounded-md bg-card flex flex-col overflow-hidden transition-all duration-200',
            sidebarOpen ? 'w-56 shrink-0' : 'w-0 overflow-hidden border-0'
          )}
        >
          <div className="flex items-center justify-between px-3 py-2 border-b bg-muted/30 shrink-0">
            <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
              테이블 목록
            </span>
          </div>
          <SchemaExplorer
            tables={tables}
            onInsertTable={handleInsertTable}
          />
        </div>

        {/* Editor + Results */}
        <div className="flex-1 min-w-0 flex flex-col gap-3">
          {/* Toggle sidebar button */}
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="sm"
              className="h-6 px-2 text-xs text-muted-foreground"
              onClick={() => setSidebarOpen((v) => !v)}
            >
              {sidebarOpen ? (
                <>
                  <ChevronDown className="h-3 w-3 mr-1" />
                  테이블 숨기기
                </>
              ) : (
                <>
                  <ChevronRight className="h-3 w-3 mr-1" />
                  테이블 보기
                </>
              )}
            </Button>
            <span className="text-xs text-muted-foreground">
              Ctrl+Enter로 실행
            </span>
          </div>

          {/* CodeMirror Editor */}
          <AnalyticsSqlEditor
            value={sql}
            onChange={setSql}
            onExecute={handleExecute}
            schema={cmSchema}
          />

          {/* Results */}
          {result && (
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">결과</span>
                  {!result.error && result.queryType === 'SELECT' && (
                    <>
                      <Badge variant="secondary" className="text-xs">
                        {result.rows.length}행
                        {result.truncated && ` (상위 ${result.rows.length}행)`}
                      </Badge>
                      <Badge variant="outline" className="text-xs">
                        {result.executionTimeMs}ms
                      </Badge>
                    </>
                  )}
                </div>
                {/* Phase 2: Create Chart button */}
                <Button
                  variant="outline"
                  size="sm"
                  className="gap-1.5"
                  disabled={!queryId}
                  onClick={() => navigate(`/analytics/charts/new?queryId=${queryId}`)}
                  title={queryId ? '이 쿼리로 차트 만들기' : '쿼리를 저장한 후 차트를 만들 수 있습니다'}
                >
                  <BarChart2 className="h-4 w-4" />
                  차트로 만들기
                </Button>
              </div>
              <ResultTable result={result} />
            </div>
          )}
        </div>
      </div>

      {/* Save Dialog */}
      <SaveDialog
        open={saveDialogOpen}
        onOpenChange={setSaveDialogOpen}
        name={saveForm.name}
        onNameChange={(v) => setSaveForm((p) => ({ ...p, name: v }))}
        description={saveForm.description}
        onDescriptionChange={(v) => setSaveForm((p) => ({ ...p, description: v }))}
        folder={saveForm.folder}
        onFolderChange={(v) => setSaveForm((p) => ({ ...p, folder: v }))}
        isShared={saveForm.isShared}
        onIsSharedChange={(v) => setSaveForm((p) => ({ ...p, isShared: v }))}
        folders={folders}
        onSave={handleSave}
        isSaving={isSaving}
        isEdit={!isNew}
      />
    </div>
  );
}
