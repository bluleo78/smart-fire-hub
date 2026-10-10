import { autocompletion, completionKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { PostgreSQL,sql } from '@codemirror/lang-sql';
import { searchKeymap } from '@codemirror/search';
import { EditorState } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView, keymap } from '@codemirror/view';
import axios from 'axios';
import {
  ArrowLeft,
  BarChart2,
  ChevronDown,
  ChevronRight,
  Download,
  FileCode2,
  Loader2,
  Play,
  Save,
} from 'lucide-react';
import { useCallback, useEffect, useMemo,useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { exportsApi } from '../../api/exports';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu';
import { ExportBlockedTooltip } from '../../components/ui/ExportBlockedTooltip';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Skeleton } from '../../components/ui/skeleton';
import { Switch } from '../../components/ui/switch';
import {
  useCreateSavedQuery,
  useExecuteAnalyticsQuery,
  useQueryFolders,
  useSavedQuery,
  useSchemaInfo,
  useUpdateSavedQuery,
} from '../../hooks/queries/useAnalytics';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { extractApiError, handleApiError, handleApiErrorAsync } from '../../lib/api-error';
import { downloadBlob, filenameFromContentDisposition } from '../../lib/download';
import { cn } from '../../lib/utils';
import type { AnalyticsQueryResult } from '../../types/analytics';
import type { ExportFormat } from '../../types/export';
import { SchemaExplorer } from './components/SchemaExplorer';

// ============================================================
// Inline SQL Editor with schema-aware autocomplete
// ============================================================

interface AnalyticsSqlEditorProps {
  value: string;
  onChange: (value: string) => void;
  onExecute: () => void;
  schema: Record<string, string[]>;
  onViewReady?: (view: EditorView | null) => void;
}

function AnalyticsSqlEditor({
  value,
  onChange,
  onExecute,
  schema,
  onViewReady,
}: AnalyticsSqlEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const onExecuteRef = useRef(onExecute);
  const onViewReadyRef = useRef(onViewReady);

  onChangeRef.current = onChange;
  onExecuteRef.current = onExecute;
  onViewReadyRef.current = onViewReady;

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
          ...completionKeymap,
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
            border: '1px solid var(--border)',
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
    onViewReadyRef.current?.(view);

    return () => {
      onViewReadyRef.current?.(null);
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
    /* scroll-pt-10: sticky thead 뒤로 행이 숨지 않도록 */
    <div
      className="rounded-md border overflow-auto overscroll-contain scroll-pt-10"
      style={{ maxHeight: 320 }}
    >
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
                    title={val != null ? (typeof val === 'object' ? JSON.stringify(val) : String(val)) : undefined}
                  >
                    {val == null ? (
                      // null 값은 시각적으로 구분되는 dash로 표시 (빈 셀과 달리 null임을 명시)
                      <span className="text-muted-foreground italic text-xs select-none">-</span>
                    ) : typeof val === 'object' ? (
                      JSON.stringify(val)
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
  /** 저장 시 발생한 오류 메시지 — 다이얼로그 내 인라인 표시용 */
  error?: string | null;
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
  error,
}: SaveDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{isEdit ? '쿼리 수정' : '쿼리 저장'}</DialogTitle>
          <DialogDescription className="sr-only">
            {isEdit ? '쿼리 설정을 수정하여 저장합니다.' : '쿼리 이름과 설정을 입력하여 저장합니다.'}
          </DialogDescription>
        </DialogHeader>
        {/* 저장 실패 오류 메시지 인라인 표시 — 사용자가 다이얼로그 내에서 원인을 즉시 인지하도록 (이슈 #195) */}
        {error && (
          <p className="text-sm text-destructive -mt-2">{error}</p>
        )}
        <div className="space-y-4 py-2">
          <div className="space-y-1.5">
            <Label htmlFor="query-name">이름 *</Label>
            <Input
              id="query-name"
              value={name}
              onChange={(e) => onNameChange(e.target.value)}
              placeholder="쿼리 이름을 입력하세요"
              maxLength={200}
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
            {isSaving && <Loader2 className="h-4 w-4 animate-spin" />}
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

/**
 * 실행 기록(runId) 없는 결과의 내보내기 안내 — 애드혹·저장 쿼리 실행 모두 서버가 실행 기록을 남기지만(code-review 4), 구버전 응답 등
 * runId 가 없으면 서버로 보낼 근거가 없다. 서버 404(QUERY_RUN_NOT_FOUND) 문구와 같은 흐름으로 다시 실행을 안내한다.
 */
const QUERY_RUN_MISSING_MESSAGE = '실행 기록이 없습니다. 쿼리를 다시 실행한 뒤 내보내세요.';

/** 서버 SQL 관문의 열람 거부 코드(DatasetAccessGuard) — 이 코드의 403 이면 화면의 이전 결과를 비운다. */
const SQL_ACCESS_DENIAL_CODES = new Set(['DATASET_SQL_ACCESS_DENIED', 'SQL_WRITE_DOWNGRADE']);

/** 실행 실패가 SQL 열람 거부(403 + 거부 코드)인지 판별한다. */
function isSqlAccessDenial(error: unknown): boolean {
  if (!axios.isAxiosError(error) || error.response?.status !== 403) return false;
  const code = (error.response.data as { code?: string } | undefined)?.code;
  return code != null && SQL_ACCESS_DENIAL_CODES.has(code);
}

export default function QueryEditorPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();

  // 이슈 #569: 쿼리 목록에서 "실행" 클릭 시 navigate(..., { state: { executionResult } })로
  // 전달된 실행 결과. 있으면 편집기 진입 시 재실행 없이 바로 결과 패널을 렌더링한다.
  const navigationExecutionResult =
    (location.state as { executionResult?: AnalyticsQueryResult } | null)?.executionResult ?? null;

  const queryId = id ? parseInt(id, 10) : null;
  const isNew = !queryId;

  // URL params for "open from DatasetDataTab"
  const initialSql = searchParams.get('sql')
    ? decodeURIComponent(searchParams.get('sql')!)
    : '';

  const [sql, setSql] = useState(initialSql);
  const [result, setResult] = useState<AnalyticsQueryResult | null>(navigationExecutionResult);
  const [saveDialogOpen, setSaveDialogOpen] = useState(false);
  // 저장 다이얼로그 내 인라인 오류 메시지 상태 (이슈 #195)
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveForm, setSaveForm] = useState({
    name: '',
    description: '',
    folder: '',
    isShared: false,
  });
  const editorViewRef = useRef<EditorView | null>(null);

  // Sidebar panel
  const [sidebarOpen, setSidebarOpen] = useState(true);

  // 사용자 상호작용 후 변경 여부 추적 (이슈 #57)
  // - 초기 로드(savedQuery로부터 setState)는 변경으로 간주하지 않기 위해 핸들러에서 명시적으로 markDirty()를 호출
  // - SQL 텍스트 변경(에디터 입력) 또는 저장 폼 필드 변경(이름/설명/폴더/공유) 시 dirty=true
  const [isDirty, setIsDirty] = useState(false);
  const markDirty = useCallback(() => setIsDirty(true), []);

  // 미저장 변경사항 이탈 가드 (#639) — 사이드바 링크(<a> 클릭)·브라우저 뒤로가기·탭 닫기/새로고침을
  // 모두 포괄하는 공용 훅으로 교체. 기존에는 "목록으로 돌아가기" 버튼 전용 수동 가드만 있어
  // 사이드바 네비게이션 등 다른 이탈 경로가 경고 없이 뚫려 있었다.
  const { dialog: unsavedChangesDialog, requestNavigate } = useUnsavedChangesGuard(isDirty);

  // CodeMirror 외부 sync(초기 로드)로 인한 onChange를 사용자 입력과 구분하기 위한 플래그.
  // - savedQuery 로드 시 setSql → CodeMirror sync useEffect → updateListener → onChange 발화.
  //   이때 markDirty가 호출되면 안 되므로 ignoreNextChange를 true로 세팅한다.
  const ignoreNextChangeRef = useRef(false);

  // isError: 존재하지 않는 쿼리 ID(404 등) 접근 시 에러 상태를 감지한다.
  const { data: savedQuery, isLoading: queryLoading, isError: queryError } = useSavedQuery(queryId);
  const { data: schemaInfo } = useSchemaInfo();
  const { data: foldersData } = useQueryFolders();

  const executeQuery = useExecuteAnalyticsQuery();
  const createSavedQuery = useCreateSavedQuery();
  const updateSavedQuery = useUpdateSavedQuery();
  const savingRef = useRef(false);

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
      // CodeMirror sync useEffect가 발화시킬 onChange를 무시하도록 플래그 세팅 (이슈 #57)
      ignoreNextChangeRef.current = true;
      setSql(savedQuery.sqlText);
      setSaveForm({
        name: savedQuery.name,
        description: savedQuery.description ?? '',
        folder: savedQuery.folder ?? '',
        isShared: savedQuery.isShared,
      });
      // 초기 로드는 dirty가 아님 (이슈 #57)
      setIsDirty(false);
    }
  }, [savedQuery, initialSql]);

  // SQL 텍스트 변경 핸들러 — CodeMirror onChange에 전달, 사용자 입력 시 dirty 마킹 (이슈 #57)
  // - ignoreNextChangeRef가 true면 외부 sync(초기 로드)로 인한 onChange이므로 dirty 마킹을 건너뛴다.
  const handleSqlChange = useCallback(
    (next: string) => {
      setSql(next);
      if (ignoreNextChangeRef.current) {
        ignoreNextChangeRef.current = false;
        return;
      }
      markDirty();
    },
    [markDirty],
  );

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
        toast.error('쿼리 실행 오류.');
      } else {
        toast.success(
          res.queryType === 'SELECT'
            ? `${res.rows.length}행 반환 (${res.executionTimeMs}ms)`
            : `${res.affectedRows}행 처리됨 (${res.executionTimeMs}ms)`
        );
      }
    } catch (error) {
      // SQL 열람 거부(403) — 이전 실행 결과가 거부 토스트 옆에 남으면 방금 SQL 의 결과처럼 보이므로 비운다.
      if (isSqlAccessDenial(error)) {
        setResult(null);
      }
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
    // 다이얼로그 열 때 이전 오류 초기화 (이슈 #195)
    setSaveError(null);
    setSaveDialogOpen(true);
  };

  const handleSave = async () => {
    if (savingRef.current || !saveForm.name.trim()) return;

    // 클라이언트 측 SQL 빈값 검증 — 서버 400 이전에 인라인 오류를 표시하여 UX 개선 (이슈 #195)
    if (!sql.trim()) {
      setSaveError('SQL을 입력해야 저장할 수 있습니다.');
      return;
    }

    savingRef.current = true;
    setSaveError(null);

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
        // 저장 성공 → dirty 해제 후 신규 쿼리 상세로 replace (이슈 #57)
        setIsDirty(false);
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
        // 수정 성공 → dirty 해제 (페이지 이동은 없음) (이슈 #57)
        setIsDirty(false);
      }
    } catch (error) {
      // API 오류 발생 시 다이얼로그 내에 인라인으로 오류 메시지를 표시한다 (이슈 #195)
      // toast만으로는 다이얼로그 내 사용자가 오류를 인지하기 어려우므로 saveError도 함께 설정
      setSaveError(extractApiError(error, '쿼리 저장에 실패했습니다.'));
      handleApiError(error, '쿼리 저장에 실패했습니다.');
    } finally {
      savingRef.current = false;
    }
  };

  const handleQueryExport = async (format: ExportFormat) => {
    if (!result || result.error || result.columns.length === 0) return;
    // 내보내기는 화면 rows 가 아니라 실행 기록 id 로 서버가 다시 판정·실행한다(S4) — 기록이 없는 결과(구버전 응답 등)는
    // 서버로 보낼 근거가 없으므로 다시 실행하도록 안내한다. 버튼도 비활성이지만 방어적으로 한 번 더 막는다.
    if (!result.runId) {
      toast.error(QUERY_RUN_MISSING_MESSAGE);
      return;
    }

    // 결과가 서버 maxRows 캡으로 잘린 경우, 메모리상의 result.rows는 전체가 아니라
    // 상위 N행뿐이다. 이를 그대로 내보내면 사용자가 전체 데이터를 받았다고
    // 오인할 수 있으므로, 내보내기 진행 전 명확히 확인받는다. (#658)
    if (result.truncated) {
      const confirmed = window.confirm(
        `결과가 ${result.rows.length}행으로 제한되어 있어 전체 데이터가 아닌 일부만 내보내집니다. 계속하시겠습니까?`,
      );
      if (!confirmed) return;
    }

    try {
      const response = await exportsApi.exportQueryRun(result.runId, format);
      // 파일 이름은 서버가 정한다(Content-Disposition) — 서버가 다시 실행한 결과 기준이라 잘림 접미사(_상위N행, #658)도 화면 상태가
      // 아니라 실제 파일 내용과 맞는다. 헤더가 없을 때만 같은 규칙의 이름을 화면 상태로 만든다.
      const ext = format === 'CSV' ? 'csv' : 'xlsx';
      const suffix = result.truncated ? `_상위${result.rows.length}행` : '';
      const fallback = `query_result_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}${suffix}.${ext}`;
      const disposition = response.headers['content-disposition'];
      const filename = filenameFromContentDisposition(typeof disposition === 'string' ? disposition : null, fallback);
      downloadBlob(filename, response.data as Blob);
      if (result.truncated) {
        toast.warning(`상위 ${result.rows.length}행만 내보내졌습니다. 전체 결과가 아닙니다.`);
      } else {
        toast.success('파일이 다운로드되었습니다.');
      }
    } catch (error) {
      // blob 응답이라 오류 본문도 Blob 으로 온다 — 비동기로 풀어 서버 문구(404 QUERY_RUN_NOT_FOUND·403 정책 거부)를 보인다.
      await handleApiErrorAsync(error, '내보내기에 실패했습니다.');
    }
  };

  const handleInsertAtCursor = useCallback((text: string) => {
    const view = editorViewRef.current;
    if (!view) {
      setSql((prev) => {
        const trimmed = prev.trimEnd();
        return trimmed ? `${trimmed}\n${text}` : text;
      });
      return;
    }

    const cursor = view.state.selection.main.head;
    view.dispatch({
      changes: { from: cursor, insert: text },
      selection: { anchor: cursor + text.length },
    });
    view.focus();
  }, []);

  if (!isNew && queryLoading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  // 존재하지 않는 쿼리 ID(404 등) 접근 시: 에러 안내 + 목록 이동 버튼 표시
  if (!isNew && queryError) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20">
        <FileCode2 className="h-12 w-12 text-muted-foreground" />
        <p className="text-muted-foreground">쿼리를 찾을 수 없습니다.</p>
        <Button
          variant="outline"
          onClick={() => navigate('/analytics/queries')}
        >
          목록으로
        </Button>
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
          size="icon"
          aria-label="목록으로 돌아가기"
          onClick={() => requestNavigate('/analytics/queries')}
        >
          <ArrowLeft className="h-4 w-4" />
        </Button>

        <div className="flex-1 min-w-0 flex flex-col">
          {savedQuery ? (
            <div className="flex items-center gap-2">
              {/* 문서 개요에 페이지 heading 이 하나는 있어야 한다(02-typography §2.2, #433).
                  툴바 내 제목이라 시각 크기(text-lg)는 그대로 두고 태그만 h1 으로 올린다. */}
              <h1 className="text-lg font-semibold truncate">{savedQuery.name}</h1>
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
            <h1 className="font-semibold text-muted-foreground">새 쿼리</h1>
          )}
          {/* 미저장 변경사항 표시 — ChartBuilderPage와 동일한 시각 패턴 (이슈 #57) */}
          {isDirty && (
            <span className="text-xs text-muted-foreground flex items-center gap-1">
              <span className="text-muted-foreground">●</span>
              미저장 변경사항
            </span>
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
            onInsertAtCursor={handleInsertAtCursor}
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
                  <ChevronDown className="h-3 w-3" />
                  테이블 숨기기
                </>
              ) : (
                <>
                  <ChevronRight className="h-3 w-3" />
                  테이블 보기
                </>
              )}
            </Button>
            <span className="text-xs text-muted-foreground">
              {/mac/i.test(navigator.userAgent) ? 'Cmd' : 'Ctrl'}+Enter로 실행
            </span>
          </div>

          {/* CodeMirror Editor */}
          <AnalyticsSqlEditor
            value={sql}
            onChange={handleSqlChange}
            onExecute={handleExecute}
            schema={cmSchema}
            onViewReady={(view) => {
              editorViewRef.current = view;
            }}
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
                <div className="flex items-center gap-2">
                  {/* 내보내기 — 조회자 기준 정책 차단(exportAllowed !== true)이나 실행 기록 없음(runId 없음)이면 드롭다운 대신
                      비활성 버튼+사유 툴팁만 그린다(주 버튼 → 비활성, 스펙 §5-4). DropdownMenuTrigger 안쪽을 감싸면 트리거가
                      span 이 되어 동작이 꼬이므로 분기로 나눈다. */}
                  {!result.error && result.queryType === 'SELECT' && result.rows.length > 0 && (
                    result.exportAllowed === true && result.runId ? (
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="outline" size="sm" className="gap-1.5">
                            <Download className="h-4 w-4" />
                            내보내기
                            <ChevronDown className="h-3 w-3" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onClick={() => handleQueryExport('CSV')}>
                            CSV로 내보내기
                          </DropdownMenuItem>
                          <DropdownMenuItem onClick={() => handleQueryExport('EXCEL')}>
                            Excel로 내보내기
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    ) : (
                      <ExportBlockedTooltip
                        blocked
                        message={result.exportAllowed === true ? QUERY_RUN_MISSING_MESSAGE : undefined}
                      >
                        <Button variant="outline" size="sm" className="gap-1.5">
                          <Download className="h-4 w-4" />
                          내보내기
                          <ChevronDown className="h-3 w-3" />
                        </Button>
                      </ExportBlockedTooltip>
                    )
                  )}
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
        error={saveError}
      />

      {/* 미저장 변경사항 이탈 가드 다이얼로그 (#639) — useUnsavedChangesGuard가 렌더 */}
      {unsavedChangesDialog}
    </div>
  );
}
