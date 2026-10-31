import { autocompletion, completionKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { PostgreSQL,sql } from '@codemirror/lang-sql';
import { searchKeymap } from '@codemirror/search';
import { EditorState } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView, keymap } from '@codemirror/view';
import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import {
  ArrowLeft,
  BarChart2,
  ChevronDown,
  ChevronRight,
  Copy,
  Download,
  FileCode2,
  Loader2,
  Play,
  Save,
} from 'lucide-react';
import { useCallback, useEffect, useMemo,useRef, useState } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { analyticsApi } from '../../api/analytics';
import { exportsApi } from '../../api/exports';
import { SharedReadOnlyNotice } from '../../components/analytics/SharedReadOnlyNotice';
import { EditConflictDialog } from '../../components/EditConflictDialog';
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
  useCloneSavedQuery,
  useCreateSavedQuery,
  useExecuteAnalyticsQuery,
  useQueryFolders,
  useSavedQuery,
  useSchemaInfo,
  useUpdateSavedQuery,
} from '../../hooks/queries/useAnalytics';
import { useIsOwner } from '../../hooks/useIsOwner';
import { useMediaQuery } from '../../hooks/useMediaQuery';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { extractApiError, handleApiError, handleApiErrorAsync, isEditConflict } from '../../lib/api-error';
import { downloadBlob, filenameFromContentDisposition, localDateStamp } from '../../lib/download';
import { parseRouteId } from '../../lib/route-id';
import { sqlRunShortcut } from '../../lib/sql-run-shortcut';
import { RUN_SHORTCUT_LABEL } from '../../lib/sql-run-shortcut-label';
import { cn } from '../../lib/utils';
import type { AnalyticsQueryResult, SavedQuery } from '../../types/analytics';
import type { ExportFormat } from '../../types/export';
import { qualifiedName } from './components/schema-explorer-utils';
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
        // WD-420: 실제 입력 요소(.cm-content, role=textbox)에 이름이 없어 스크린리더가 「편집 가능한 텍스트」로만 읽는다.
        EditorView.contentAttributes.of({ 'aria-label': 'SQL 쿼리 편집' }),
        // 실행 단축키 — 기본 키맵보다 우선해야 Mod-Enter 가 빈 줄 대신 실행된다(WD-87)
        sqlRunShortcut(() => onExecuteRef.current()),
        keymap.of([
          ...completionKeymap,
          ...defaultKeymap,
          ...historyKeymap,
          ...searchKeymap,
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
          {result.rows.map((row, i) => {
            // 이 행에서 브라우저 double 로 깎이는 칸의 서버 원문(WD-105). rows 숫자는 이미 깎였으므로 원문이 있으면 그것을 보인다.
            const exactRow = result.exactValues?.[i];
            return (
              <tr key={i} className="hover:bg-muted/40 transition-colors">
                {result.columns.map((col) => {
                  const val = row[col];
                  const exact = exactRow?.[col];
                  const text =
                    exact ?? (val == null ? undefined : typeof val === 'object' ? JSON.stringify(val) : String(val));
                  return (
                    <td
                      key={col}
                      className="px-3 py-1.5 border-b whitespace-nowrap max-w-[200px] truncate"
                      title={text}
                    >
                      {text === undefined ? (
                        // null 값은 시각적으로 구분되는 dash로 표시 (빈 셀과 달리 null임을 명시)
                        <span className="text-muted-foreground italic text-xs select-none">-</span>
                      ) : (
                        text
                      )}
                    </td>
                  );
                })}
              </tr>
            );
          })}
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
/** 휴대폰 폭(Tailwind md 미만) — 테이블 목록을 접은 채로 시작하고 편집기 위에 쌓는 기준(WD-326) */
const NARROW_MQ = '(max-width: 767px)';

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

  // 주소 id 는 숫자만 받는다(WD-434) — parseInt 는 'abc' 를 NaN(새 쿼리로), '15abc' 를 15(다른 쿼리)로 읽었다
  const parsedQueryId = parseRouteId(id);
  const isInvalidId = parsedQueryId === null;
  const queryId = parsedQueryId ?? null;
  const isNew = id === undefined;

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

  // 테이블 목록 패널(WD-326) — 휴대폰 폭(md 미만)에서는 접은 채로 시작한다. 펼쳐 둔 채 시작하면 고정 폭 패널이
  // SQL 편집기·결과를 100px 남짓으로 눌러 아무것도 읽을 수 없다. 사용자가 한 번 접거나 펼치면 그 선택을 따른다(null = 아직 안 고름).
  const isNarrow = useMediaQuery(NARROW_MQ);
  const [sidebarChoice, setSidebarChoice] = useState<boolean | null>(null);
  const sidebarOpen = sidebarChoice ?? !isNarrow;

  // 사용자 상호작용 후 변경 여부 추적 (이슈 #57)
  // - 초기 로드(savedQuery로부터 setState)는 변경으로 간주하지 않기 위해 핸들러에서 명시적으로 markDirty()를 호출
  // - SQL 텍스트 변경(에디터 입력) 또는 저장 폼 필드 변경(이름/설명/폴더/공유) 시 dirty=true
  const [isDirty, setIsDirty] = useState(false);

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

  // 저장(덮어쓰기)·이름 변경은 작성자만 — 서버가 남의 수정 요청을 404 로 거절한다(WD-196). 남이 공유한 쿼리는 저장 버튼 대신
  // 「복제」 안내를 둔다. SQL 을 고쳐 실행해 보는 것(읽기)은 그대로 허용하고, 보안 등급 판정도 실행 경로가 기존대로 한다.
  const isOwner = useIsOwner();
  const canEdit = isNew || (savedQuery != null && isOwner(savedQuery.createdBy));
  // 저장할 수 없는 사용자는 변경을 추적하지 않는다 — 저장 못 할 「미저장 변경사항」 경고·이탈 확인이 뜨지 않게(WD-196)
  const markDirty = useCallback(() => {
    if (canEdit) setIsDirty(true);
  }, [canEdit]);
  const cloneSavedQuery = useCloneSavedQuery();
  const { data: schemaInfo } = useSchemaInfo();
  const { data: foldersData } = useQueryFolders();

  const executeQuery = useExecuteAnalyticsQuery();
  const createSavedQuery = useCreateSavedQuery();
  const updateSavedQuery = useUpdateSavedQuery();
  const savingRef = useRef(false);
  const queryClient = useQueryClient();

  /**
   * 지금 편집기 내용의 기준 버전 — 그 내용을 불러온(또는 저장한) 시점의 서버 updatedAt(WD-364 낙관적 잠금).
   * 저장할 때 expectedUpdatedAt 으로 보내 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다.
   * 편집기 내용을 서버 값으로 채우는 곳(applySavedQuery)에서만 함께 바꾼다 — 내용과 기준이 어긋나면 다른 탭의 저장을 조용히 덮어쓴다.
   * 서버 문자열 그대로 둔다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다).
   */
  const [baseUpdatedAt, setBaseUpdatedAt] = useState<string | null>(null);
  // 마지막 저장이 편집 충돌로 거부됐는가 — 참이면 EditConflictDialog 를 저장 창 위에 띄운다
  const [editConflict, setEditConflict] = useState(false);
  // 충돌 창의 「다시 불러오기」 진행 중 — 저장 중과 함께 창의 버튼을 막는다
  const [isReloading, setIsReloading] = useState(false);

  const folders = foldersData ?? [];
  const tables = schemaInfo?.tables ?? [];

  // Build CodeMirror schema map: { tableName: [columnName, ...] }
  const cmSchema = useMemo(() => {
    const schemaTables = schemaInfo?.tables ?? [];
    const map: Record<string, string[]> = {};
    for (const table of schemaTables) {
      // Tibero 는 `schema.table` 로 참조하므로 자동완성 키도 한정 이름으로 맞춘다(WD-76)
      const key = qualifiedName(table.tableName, schemaInfo ?? {});
      map[key] = table.columns.map((c) => c.columnName);
    }
    return map;
  }, [schemaInfo]);

  /** 서버 쿼리로 편집기 내용과 기준 버전을 함께 채운다 — 초기 로드·재조회·충돌 후 「다시 불러오기」가 같은 길을 쓴다 */
  const applySavedQuery = useCallback((query: SavedQuery) => {
    // CodeMirror sync useEffect가 발화시킬 onChange를 무시하도록 플래그 세팅 (이슈 #57)
    ignoreNextChangeRef.current = true;
    setSql(query.sqlText);
    setSaveForm({
      name: query.name,
      description: query.description ?? '',
      folder: query.folder ?? '',
      isShared: query.isShared,
    });
    setBaseUpdatedAt(query.updatedAt);
    // 서버 내용으로 채운 직후는 dirty가 아님 (이슈 #57)
    setIsDirty(false);
  }, []);

  // Load saved query into editor when fetched
  useEffect(() => {
    if (savedQuery && !initialSql) applySavedQuery(savedQuery);
  }, [savedQuery, initialSql, applySavedQuery]);

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

  // 실행 중 표시. 단축키 재입력·키 반복으로 같은 SQL 이 또 나가지 않게 막는다 — 버튼만 비활성이라 DML 이 두 번 적용될 수
  // 있다(WD-87). isPending 은 다시 그려져야 바뀌어 빠른 연타를 놓치므로 렌더와 무관한 ref 로 판정한다.
  const runningRef = useRef(false);

  const handleExecute = useCallback(async () => {
    if (runningRef.current) return;
    if (!sql.trim()) {
      toast.error('실행할 SQL을 입력하세요.');
      return;
    }
    runningRef.current = true;
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
    } finally {
      runningRef.current = false;
    }
  }, [sql, executeQuery]);

  const handleSaveClick = () => {
    if (!canEdit) return; // 남의 쿼리는 저장 창을 열지 않는다(WD-196)
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

  /** force: 기준 버전을 보내지 않아 서버가 비교하지 않는다 — 편집 충돌 뒤 「내 변경으로 덮어쓰기」(WD-364) */
  const handleSave = async (force = false) => {
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
        const saved = await updateSavedQuery.mutateAsync({
          id: queryId!,
          data: {
            name: saveForm.name,
            // 지운 설명·"폴더 없음"은 빈 문자열로 보낸다. undefined/null 은 서버가 "변경 없음"으로 보고
            // 옛 설명·폴더를 그대로 남긴다 — 빈 문자열이어야 서버가 NULL 로 비운다(WD-197).
            description: saveForm.description,
            sqlText: sql,
            folder: saveForm.folder,
            isShared: saveForm.isShared,
            // 편집 기준 버전(WD-364) — 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다
            ...(force ? {} : { expectedUpdatedAt: baseUpdatedAt }),
          },
        });
        // 새 버전을 바로 기준으로 삼는다 — 재조회를 기다리면 그 사이 다시 저장할 때 자기 저장과 충돌한다.
        // 응답에 버전이 없으면(옛 서버·모킹) null — 다음 저장은 비교 없이 나간다(자기 충돌보다 안전한 쪽)
        setBaseUpdatedAt(saved?.updatedAt ?? null);
        setEditConflict(false);
        toast.success('쿼리가 수정되었습니다.');
        setSaveDialogOpen(false);
        // 수정 성공 → dirty 해제 (페이지 이동은 없음) (이슈 #57)
        setIsDirty(false);
      }
    } catch (error) {
      // 편집 충돌은 인라인 오류·토스트 대신 안내 창으로 — 사용자가 다시 불러올지 덮어쓸지 고른다(WD-364)
      if (isEditConflict(error)) {
        setEditConflict(true);
        return;
      }
      // API 오류 발생 시 다이얼로그 내에 인라인으로 오류 메시지를 표시한다 (이슈 #195)
      // toast만으로는 다이얼로그 내 사용자가 오류를 인지하기 어려우므로 saveError도 함께 설정
      setSaveError(extractApiError(error, '쿼리 저장에 실패했습니다.'));
      handleApiError(error, '쿼리 저장에 실패했습니다.');
    } finally {
      savingRef.current = false;
    }
  };

  /**
   * 편집 충돌의 「다시 불러오기」(WD-364) — 최신 쿼리를 서버에서 새로 받아 편집기를 그것으로 바꾸고 내 변경을 버린다.
   * 캐시의 쿼리가 재조회 결과와 같으면(구조 공유로 같은 참조) 로드 effect 가 다시 돌지 않으므로 applySavedQuery 로 직접 채운다.
   * 저장 창은 닫는다 — 편집기에서 최신 SQL 을 확인한 뒤 다시 저장하게 한다.
   */
  const handleReloadLatest = async () => {
    if (!queryId) return;
    setIsReloading(true);
    try {
      const latest = await queryClient.fetchQuery({
        queryKey: ['analytics', 'queries', queryId],
        queryFn: () => analyticsApi.getQuery(queryId).then((r) => r.data),
        staleTime: 0,
      });
      applySavedQuery(latest);
      setEditConflict(false);
      setSaveError(null);
      setSaveDialogOpen(false);
    } catch (error) {
      handleApiError(error, '최신 내용을 불러오지 못했습니다.');
    } finally {
      setIsReloading(false);
    }
  };

  /**
   * 남이 공유한 쿼리를 내 쿼리로 복제하고 그 편집기로 옮긴다(WD-196) — 서버 복제(POST /queries/{id}/clone)는 비공개·내 소유 사본을
   * 만든다(이름 「… (복사본)」). 복제본은 내 것이라 바로 저장·이름 변경을 할 수 있다.
   */
  const handleClone = async () => {
    if (!queryId || cloneSavedQuery.isPending) return;
    try {
      const cloned = await cloneSavedQuery.mutateAsync(queryId);
      toast.success(`복사본을 만들었습니다: ${cloned.name}`);
      navigate(`/analytics/queries/${cloned.id}`);
    } catch (error) {
      handleApiError(error, '쿼리 복제에 실패했습니다.');
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
      const fallback = `query_result_${localDateStamp()}${suffix}.${ext}`;
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
  if (!isNew && (queryError || isInvalidId)) {
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
          {/* 저장은 작성자에게만(WD-196) — 실행은 누구나 */}
          {canEdit && (
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
          )}
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

      {/* 남이 공유한 쿼리 — 저장 버튼이 없는 이유와 복제 경로를 알린다(WD-196). 서버에 복제 기능이 있어 안내 옆에 버튼을 둔다. */}
      {!canEdit && savedQuery && (
        <SharedReadOnlyNotice
          className="mb-3"
          testId="query-shared-readonly"
          message={`${savedQuery.createdByName}님이 공유한 쿼리입니다 — 실행해 볼 수 있고, 고치려면 복제해서 내 쿼리로 만드세요.`}
          action={
            <Button
              variant="outline"
              size="sm"
              className="gap-1.5"
              onClick={() => void handleClone()}
              disabled={cloneSavedQuery.isPending}
            >
              {cloneSavedQuery.isPending ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <Copy className="h-4 w-4" />
              )}
              복제
            </Button>
          }
        />
      )}

      {/* Main area: sidebar + editor + results */}
      {/* 휴대폰 폭(md 미만)에서는 테이블 목록을 편집기 위에 쌓는다(WD-326) — 옆에 두면 편집기 칸이 화면 폭에서 패널 폭만큼 줄어든다 */}
      <div className="flex flex-1 min-h-0 flex-col gap-3 md:flex-row">
        {/* Schema Explorer Sidebar */}
        <div
          data-testid="query-schema-panel"
          className={cn(
            'border rounded-md bg-card flex flex-col overflow-hidden transition-all duration-200',
            sidebarOpen
              ? 'max-h-60 w-full shrink-0 md:max-h-none md:w-56'
              : 'hidden md:flex md:w-0 md:border-0'
          )}
        >
          <div className="flex items-center justify-between px-3 py-2 border-b bg-muted/30 shrink-0">
            <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
              테이블 목록
            </span>
          </div>
          <SchemaExplorer
            tables={tables}
            dialect={schemaInfo?.dialect}
            schema={schemaInfo?.schema}
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
              onClick={() => setSidebarChoice(!sidebarOpen)}
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
              {RUN_SHORTCUT_LABEL}
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
              {/* 좁은 화면에서는 배지·버튼 묶음이 다음 줄로 내려간다(WD-326) — 한 줄에 몰면 「결과」가 「결/과」로 쪼개지고
                  「차트로 만들기」가 화면 밖으로 밀린다 */}
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium whitespace-nowrap">결과</span>
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
                <div className="flex flex-wrap items-center gap-2">
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
        onSave={() => handleSave()}
        isSaving={isSaving}
        isEdit={!isNew}
        error={saveError}
      />

      {/* 편집 충돌 안내 창(WD-364) — 저장이 409 EDIT_CONFLICT 로 거부되면 저장 창 위에 뜬다 */}
      <EditConflictDialog
        open={editConflict}
        onOpenChange={(open) => {
          if (!open) setEditConflict(false);
        }}
        resourceLabel="쿼리"
        onReload={handleReloadLatest}
        onOverwrite={() => handleSave(true)}
        pending={isSaving || isReloading}
      />

      {/* 미저장 변경사항 이탈 가드 다이얼로그 (#639) — useUnsavedChangesGuard가 렌더 */}
      {unsavedChangesDialog}
    </div>
  );
}
