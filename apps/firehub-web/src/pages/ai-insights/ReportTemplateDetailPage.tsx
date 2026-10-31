import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Copy, Pencil, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

import type { ReportTemplate, ReportTemplateSummary } from '@/api/proactive';
import { EditConflictDialog } from '@/components/EditConflictDialog';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { useCanWriteProactive,useCreateProactiveTemplate, useDeleteProactiveTemplate, useFetchLatestProactiveTemplate, useProactiveJobs, useProactiveTemplate, useProactiveTemplates, useUpdateProactiveTemplate } from '@/hooks/queries/useProactiveMessages';
import { useUnsavedChangesGuard } from '@/hooks/useUnsavedChangesGuard';
import { handleApiError, isEditConflict, isForbiddenError } from '@/lib/api-error';
import { parseUtcDate } from '@/lib/formatters';
import { parseTemplateSections, validateSectionKeys } from '@/lib/template-section-types';
import { type ReportTemplateFormValues, reportTemplateSchema } from '@/lib/validations/report-template';

import { ProactiveReadForbiddenNotice } from './components/ProactiveReadForbiddenNotice';
import { ProactiveWriteNotice } from './components/ProactiveWriteNotice';
import { SectionPreview } from './components/SectionPreview';
import { SectionPropertyEditor } from './components/SectionPropertyEditor';
import { SectionTreeBuilder } from './components/SectionTreeBuilder';
import { TemplateJsonEditor } from './components/TemplateJsonEditor';
import { useSectionTree } from './hooks/useSectionTree';

const DEFAULT_STRUCTURE = JSON.stringify({ sections: [] }, null, 2);

/**
 * 목록 요약(ReportTemplateSummary)을 폴백용 ReportTemplate 형태로 변환한다(#632).
 * 단건 조회(getTemplate)가 실패했을 때만 쓰이는 드문 경로이며, 요약에는 sections/style이
 * 없으므로 빈 값으로 채운다 — 이름/설명 등 메타데이터는 보존해 화면이 완전히 비지 않도록 한다.
 */
function toFallbackTemplate(summary: ReportTemplateSummary | undefined): ReportTemplate | undefined {
  if (!summary) return undefined;
  const { id, name, description, builtin, createdAt, updatedAt } = summary;
  return { id, name, description, builtin, createdAt, updatedAt, sections: [], style: null };
}

export default function ReportTemplateDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = !id || id === 'new';
  const templateId = isNew ? 0 : Number(id);

  const { data: templateDirect, isLoading: isLoadingDirect, isError: isErrorDirect, error: errorDirect } = useProactiveTemplate(templateId);
  // 조회 권한 부족(403) — 「불러오는데 실패」 토스트 + 목록 이동 대신 이 화면에 권한 안내를 보이는 기준 (WD-137).
  // 목록으로 보내 봐야 목록도 같은 권한으로 막혀 있어, 사용자는 왜 튕겼는지 모른 채 다른 안내를 보게 된다.
  const isForbiddenDirect = isErrorDirect && isForbiddenError(errorDirect);
  const { data: templates = [], isLoading: isLoadingList } = useProactiveTemplates();
  // 삭제 시 영향받는 스마트 작업을 고지하기 위한 조회 (#364)
  // FK가 ON DELETE SET NULL이라 삭제 자체는 막히지 않고, 참조 작업이 조용히 기본 형식으로 전환된다.
  const { data: allJobs = [] } = useProactiveJobs();
  // 생성·수정·삭제·복제(POST /proactive/templates)는 서버가 proactive:write 로 막는다 — 권한이 없으면 진입점을 숨기고
  // 조회 전용으로 보인다(WD-133). 최종 판정은 서버이고 여기는 노출 결정용이다.
  const { canWrite, isLoading: isPermissionsLoading } = useCanWriteProactive();
  // Fallback: use list data if single-item API fails
  // #632: 목록 응답이 요약(ReportTemplateSummary)으로 축소되어 sections/style을 포함하지 않는다.
  // 이 폴백은 단건 조회 실패라는 드문 경로뿐 아니라, 단건 조회가 아직 pending인 정상 경로에서도
  // 항상 거쳐가는 흔한 경로다(목록 캐시가 warm하고 단건 조회가 로딩 중인 동안).
  // toFallbackTemplate()는 매 호출마다 새 객체를 반환하므로, 아래 146번 줄처럼 결과를 참조
  // 동일성(!==)으로 비교해 렌더 중 state를 조정하는 코드와 만나면 매 렌더마다 값이 "바뀐 것"으로
  // 오인되어 무한 리렌더에 빠진다(#633). summary(목록에서 찾은 원본 객체)는 react-query 캐시가
  // 안정적으로 유지하는 참조이므로, 이를 의존성으로 useMemo에 넣어 fallback 객체 자체도 같은
  // summary에 대해 항상 같은 참조를 반환하도록 고정한다.
  const fallbackSummary = templates.find((t) => t.id === templateId);
  const fallbackTemplate = useMemo(() => toFallbackTemplate(fallbackSummary), [fallbackSummary]);
  const template = templateDirect ?? fallbackTemplate;
  // 두 쿼리 중 하나라도 진행 중이면 "로딩 중"으로 본다 — 저장 직후 /templates/new → /templates/100 으로
  // 이동하는 시점에 list 쿼리가 캐시 적중으로 즉시 false가 되어 guard(73~80)가 잘못 발화,
  // detail 쿼리가 끝나기 전에 목록으로 되돌리던 race를 차단한다.
  const isLoading = isLoadingDirect || isLoadingList;

  // 존재하지 않는 템플릿 ID 접근 시 에러 처리 — toast + 목록 페이지로 이동 (#38)
  // useEffect를 훅 순서 보장을 위해 조건부 return 이전에 배치한다
  useEffect(() => {
    if (isNew) return;
    // 권한 부족은 아래 렌더 분기가 권한 안내로 처리한다 — 토스트·이동을 하지 않는다 (WD-137).
    if (isForbiddenDirect) return;
    if (isErrorDirect) {
      toast.error('템플릿 정보를 불러오는데 실패했습니다.');
      navigate('/ai-insights/templates');
    }
  }, [isErrorDirect, isForbiddenDirect, isNew, navigate]);

  useEffect(() => {
    if (isNew) return;
    // 에러 케이스는 위 useEffect(isErrorDirect)가 별도 toast/navigate 로 처리한다.
    // 둘 다 발화하면 toast 2개가 동시 노출되어 strict-mode locator 검증이 실패하므로 분리.
    if (isErrorDirect) return;
    if (!isLoading && !template) {
      toast.error('존재하지 않는 템플릿입니다.');
      navigate('/ai-insights/templates');
    }
  }, [isErrorDirect, isLoading, isNew, navigate, template]);

  const createMutation = useCreateProactiveTemplate();
  const updateMutation = useUpdateProactiveTemplate();
  const deleteMutation = useDeleteProactiveTemplate();
  const fetchLatestTemplate = useFetchLatestProactiveTemplate();

  const [isEditing, setIsEditing] = useState(isNew);
  const [structureJson, setStructureJson] = useState(DEFAULT_STRUCTURE);
  const [styleText, setStyleText] = useState('');
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<'builder' | 'json'>('builder');

  // 사용자 상호작용 후 변경 여부 추적 (이슈 #58)
  // - 초기 로드(template 동기화)는 변경으로 간주하지 않기 위해 명시적으로 markDirty()를 호출
  // - 이름/설명 폼, 스타일 텍스트, 섹션 트리, JSON 텍스트 변경 시 dirty=true
  const [isDirty, setIsDirty] = useState(false);
  const markDirty = useCallback(() => setIsDirty(true), []);

  // 이탈 확인 다이얼로그 상태 — 뒤로가기/취소 클릭 시 dirty면 오픈
  const [leaveDialogOpen, setLeaveDialogOpen] = useState(false);
  // 이탈 다이얼로그 확인 후 실행할 동작 — 'back'(목록 이동) 또는 'cancel-edit'(편집 취소)
  const [leaveAction, setLeaveAction] = useState<'back' | 'cancel-edit'>('back');

  const tree = useSectionTree([]);

  // 이전 값 추적용 state (React 19 권장: 렌더링 중 state 조정 시 ref 대신 state 사용)
  // 초깃값을 지금 양식이 아니라 undefined 로 둔다(WD-364) — 상세 캐시가 있는 채로 다시 들어오면 첫 렌더의 양식이 초깃값과 같아
  // 아래 동기화가 건너뛰어져, 섹션은 빈 채로·기준 버전은 null 로 남았다(저장이 비교 없이 섹션을 지우며 덮어씀).
  const [prevTemplate, setPrevTemplate] = useState<ReportTemplate | undefined>(undefined);
  const [prevTreeSections, setPrevTreeSections] = useState(tree.sections);
  const [suppressTreeSync, setSuppressTreeSync] = useState(false);

  /**
   * 지금 화면 내용(섹션·스타일)의 기준 버전 — 그 내용을 채운(또는 저장한) 시점의 서버 updatedAt(WD-364 낙관적 잠금).
   * 저장할 때 expectedUpdatedAt 으로 보내 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다.
   * 섹션은 전체 교체라 이 장치가 없으면 나중 저장이 먼저 저장된 섹션을 통째로 되돌린다.
   * 섹션을 서버 값으로 채우는 곳(아래 동기화·편집 취소·다시 불러오기)에서만 함께 바꾼다 — 편집 중 재조회는 섹션을 바꾸지 않으므로
   * 기준도 넘기지 않는다. 서버 문자열 그대로 둔다(Date 로 바꾸면 마이크로초가 잘려 매번 충돌한다).
   */
  const [baseUpdatedAt, setBaseUpdatedAt] = useState<string | null>(null);
  // 마지막 저장이 편집 충돌로 거부됐는가 — 참이면 EditConflictDialog 를 띄운다
  const [editConflict, setEditConflict] = useState(false);
  // 충돌 창의 「다시 불러오기」 진행 중 — 저장 중과 함께 창의 버튼을 막는다
  const [isReloading, setIsReloading] = useState(false);

  // mode: 'all' — onChange + onBlur + onSubmit 모든 시점에 유효성 검사를 수행한다.
  // 이름 필드를 Ctrl+A → Backspace로 지운 후에도 isValid가 즉시 false로 갱신되도록 'all'을 사용한다.
  // (이슈 #200: mode: 'onChange'만으로는 field 비움 후 isValid 갱신이 지연되는 문제)
  const form = useForm<ReportTemplateFormValues>({
    resolver: zodResolver(reportTemplateSchema),
    mode: 'all',
    values: template ? { name: template.name, description: template.description ?? '' } : { name: '', description: '' },
  });

  // 템플릿 로드 시 구조/스타일 동기화 — 렌더링 중 state 조정 (React 권장 패턴)
  // 주의(#633): 이 비교는 참조 동일성(!==)에 의존한다. `template`을 만들어내는 계산(위 fallbackTemplate 등)이
  // 매 렌더마다 새 객체를 반환하면 이 비교가 항상 true가 되어 무한 리렌더에 빠진다 — `template` 값에
  // 관여하는 모든 계산은 반드시 useMemo 등으로 참조를 안정화해야 한다.
  if (template && template !== prevTemplate && !isEditing) {
    setPrevTemplate(template);
    const sections = Array.isArray(template.sections) ? template.sections : [];
    const json = JSON.stringify({ sections, output_format: 'markdown' }, null, 2);
    setStructureJson(json);
    setStyleText(template.style ?? '');
    tree.setSections(sections);
    setBaseUpdatedAt(template.updatedAt);
    // 초기 로드는 dirty가 아님 (이슈 #58)
    setIsDirty(false);
  }

  // tree.sections → structureJson 동기화 — 렌더링 중 state 조정
  if (tree.sections !== prevTreeSections) {
    setPrevTreeSections(tree.sections);
    if (!suppressTreeSync) {
      const json = JSON.stringify({ sections: tree.sections, output_format: 'markdown' }, null, 2);
      setStructureJson(json);
    } else {
      setSuppressTreeSync(false);
    }
  }

  // 사이드바 메뉴 클릭(SPA 내부 네비게이션) · 브라우저 뒤로/앞으로 · 탭 닫기·새로고침 가드 (이슈 #552)
  // - 페이지 내 명시적 "뒤로가기"/"취소" 버튼은 아래 handleBackClick/handleCancelEdit이 별도로 처리한다
  //   (leaveAction으로 back/cancel-edit을 구분해야 하므로 이 공용 훅으로 대체할 수 없음)
  // - 이슈 #58에서 커버하던 beforeunload도 이 훅이 함께 처리하므로 페이지 자체의 중복 리스너는 제거한다
  const { dialog: unsavedChangesDialog } = useUnsavedChangesGuard(isDirty);

  // 뒤로가기 버튼 클릭 핸들러 — dirty면 다이얼로그 표시, 아니면 즉시 이동 (이슈 #58)
  const handleBackClick = () => {
    if (isDirty) {
      setLeaveAction('back');
      setLeaveDialogOpen(true);
    } else {
      navigate('/ai-insights/templates');
    }
  };

  // structureJson onChange — JSON 탭 직접 편집 시 dirty 마킹 (이슈 #58)
  const handleStructureJsonChange = useCallback(
    (next: string) => {
      setStructureJson(next);
      markDirty();
    },
    [markDirty],
  );

  // 스타일 텍스트 변경 — 사용자 입력 시 dirty 마킹 (이슈 #58)
  const handleStyleTextChange = (next: string) => {
    setStyleText(next);
    markDirty();
  };

  // Handle tab switching with JSON -> builder sync
  const handleTabChange = useCallback(
    (tab: string) => {
      if (tab === 'builder' && activeTab === 'json') {
        const parsed = parseTemplateSections(structureJson);
        if (!parsed) {
          toast.error('JSON 파싱 실패. 유효한 JSON을 입력하세요.');
          return;
        }
        // 불량/중복 key를 빌더로 넘기기 전에 차단한다 (#361) — 넘어가면 트리 조작이 오작동한다
        const keyError = validateSectionKeys(parsed);
        if (keyError) {
          toast.error(keyError);
          return;
        }
        setSuppressTreeSync(true);
        tree.setSections(parsed);
      }
      setActiveTab(tab as 'builder' | 'json');
    },
    [activeTab, structureJson, tree],
  );

  /** force: 기준 버전을 보내지 않아 서버가 비교하지 않는다 — 편집 충돌 뒤 「내 변경으로 덮어쓰기」(WD-364) */
  const submitTemplate = (force: boolean) => form.handleSubmit((values) => {
    // JSON 탭 활성 시 현재 structureJson을 파싱하여 sections 반영 (#37)
    let resolvedSections = tree.sections;
    if (activeTab === 'json') {
      const parsed = parseTemplateSections(structureJson);
      if (!parsed) {
        toast.error('JSON이 올바르지 않아 저장할 수 없습니다.');
        return;
      }
      resolvedSections = parsed;
    }

    // 섹션 Key 형식·중복 검사 (#36, #38) — 그룹 하위까지 트리 전역으로 검증한다 (#361)
    const keyError = validateSectionKeys(resolvedSections);
    if (keyError) {
      toast.error(keyError);
      return;
    }

    // 비운 설명·작성 스타일은 빈 문자열로 보낸다 — 필드를 빼면 서버는 "바꾸지 않음"으로 받아 옛 값이 남는다 (WD-135).
    // 서버가 빈 값을 NULL 로 저장한다.
    const payload = {
      name: values.name,
      description: values.description ?? '',
      sections: resolvedSections,
      style: styleText.trim(),
    };

    if (isNew) {
      createMutation.mutate(payload, {
        onSuccess: (created) => {
          toast.success('템플릿이 생성되었습니다.');
          // 저장 성공 → dirty 해제 후 신규 템플릿 상세로 이동 (이슈 #58)
          setIsDirty(false);
          setIsEditing(false);
          navigate(`/ai-insights/templates/${created.id}`);
        },
        onError: (err) => handleApiError(err, '템플릿 생성에 실패했습니다.'),
      });
    } else {
      updateMutation.mutate(
        // 편집 기준 버전(WD-364) — 그 사이 다른 곳에서 저장됐으면 서버가 409 EDIT_CONFLICT 로 거부한다
        { id: templateId, data: force ? payload : { ...payload, expectedUpdatedAt: baseUpdatedAt } },
        {
          onSuccess: (res) => {
            // 새 버전을 바로 기준으로 삼는다 — 재조회를 기다리면 그 사이 다시 고쳐 저장할 때 자기 저장과 충돌한다.
            // 본문이 없으면(옛 서버·204 모킹) null — 다음 저장은 비교 없이 나간다(자기 충돌보다 안전한 쪽)
            setBaseUpdatedAt(res?.updatedAt ?? null);
            setEditConflict(false);
            toast.success('템플릿이 수정되었습니다.');
            // 수정 성공 → dirty 해제 후 읽기 모드로 복귀 (이슈 #58)
            setIsDirty(false);
            setIsEditing(false);
          },
          onError: (err) => {
            // 편집 충돌은 토스트 대신 안내 창으로 — 사용자가 다시 불러올지 덮어쓸지 고른다(WD-364)
            if (isEditConflict(err)) {
              setEditConflict(true);
              return;
            }
            handleApiError(err, '템플릿 수정에 실패했습니다.');
          },
        },
      );
    }
  });
  const handleSave = submitTemplate(false);

  /**
   * 편집 충돌의 「다시 불러오기」(WD-364) — 최신 양식을 서버에서 새로 받아 이름·설명·섹션·스타일·기준 버전을 그것으로 바꾸고 내 변경을 버린다.
   * 편집 중에는 위 동기화가 섹션을 다시 채우지 않으므로 직접 채운다. 편집 모드는 유지해 이어 고칠 수 있게 한다.
   */
  const handleReloadLatest = async () => {
    if (isNew) return;
    setIsReloading(true);
    try {
      const latest = await fetchLatestTemplate(templateId);
      form.reset({ name: latest.name, description: latest.description ?? '' });
      const sections = Array.isArray(latest.sections) ? latest.sections : [];
      setStructureJson(JSON.stringify({ sections, output_format: 'markdown' }, null, 2));
      setStyleText(latest.style ?? '');
      tree.setSections(sections);
      setActiveTab('builder');
      setPrevTemplate(latest);
      setBaseUpdatedAt(latest.updatedAt);
      setIsDirty(false);
      setEditConflict(false);
    } catch (error) {
      handleApiError(error, '최신 내용을 불러오지 못했습니다.');
    } finally {
      setIsReloading(false);
    }
  };

  const handleDelete = () => {
    deleteMutation.mutate(templateId, {
      onSuccess: () => {
        toast.success('템플릿이 삭제되었습니다.');
        navigate('/ai-insights/templates');
      },
      onError: (err) => handleApiError(err, '템플릿 삭제에 실패했습니다.'),
    });
    setDeleteDialogOpen(false);
  };

  const handleClone = () => {
    if (!template) return;
    createMutation.mutate(
      {
        name: `${template.name} (사본)`,
        description: template.description ?? undefined,
        sections: Array.isArray(template.sections) ? template.sections : [],
        style: template.style ?? undefined,
      },
      {
        onSuccess: (created) => {
          toast.success(`"${created.name}" 템플릿이 복제되었습니다.`);
          navigate(`/ai-insights/templates/${created.id}`);
        },
        onError: (err) => handleApiError(err, '템플릿 복제에 실패했습니다.'),
      },
    );
  };

  // 편집 취소 본 동작 — 폼/구조/스타일/탭 초기화 (이슈 #58: dirty 가드 분리)
  const performCancelEdit = useCallback(() => {
    if (template) {
      form.reset({ name: template.name, description: template.description ?? '' });
      const sections = Array.isArray(template.sections) ? template.sections : [];
      const json = JSON.stringify({ sections, output_format: 'markdown' }, null, 2);
      setStructureJson(json);
      setStyleText(template.style ?? '');
      tree.setSections(sections);
      // 화면을 캐시의 양식으로 되돌렸으니 기준도 그 버전이다(WD-364)
      setBaseUpdatedAt(template.updatedAt);
    }
    setActiveTab('builder');
    setIsEditing(false);
    setIsDirty(false);
  }, [form, template, tree]);

  // 취소 버튼 클릭 — dirty면 다이얼로그 표시, 아니면 즉시 취소 (이슈 #58)
  const handleCancelEdit = () => {
    if (isDirty) {
      setLeaveAction('cancel-edit');
      setLeaveDialogOpen(true);
    } else {
      performCancelEdit();
    }
  };

  // 다이얼로그에서 '이탈' 클릭 — leaveAction에 따라 분기 (이슈 #58)
  const handleLeaveConfirm = () => {
    setLeaveDialogOpen(false);
    setIsDirty(false);
    if (leaveAction === 'back') {
      navigate('/ai-insights/templates');
    } else {
      performCancelEdit();
    }
  };

  const isSaving = createMutation.isPending || updateMutation.isPending;

  // 훅은 조건부 return 이전에 반드시 호출해야 한다 (Rules of Hooks 준수)
  const isBuiltin = template?.builtin ?? false;
  const sections = useMemo(() => parseTemplateSections(structureJson) ?? [], [structureJson]);

  // 이 템플릿을 참조하는 스마트 작업 목록 (#364)
  const affectedJobs = useMemo(
    () => (isNew ? [] : allJobs.filter((j) => j.templateId === templateId)),
    [allJobs, isNew, templateId],
  );

  // 새 템플릿 화면은 권한을 알기 전까지 빌더를 그리지 않는다 — 조회 전용 사용자에게 폼이 잠깐 비쳤다 사라지지 않게 한다.
  if ((!isNew && isLoading) || (isNew && isPermissionsLoading)) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-64 bg-muted animate-pulse rounded" />
        <div className="h-96 bg-muted animate-pulse rounded" />
      </div>
    );
  }

  // 조회 권한이 없으면 복제·편집·삭제 버튼과 쓰기 안내 없이 권한 안내만 보인다 (WD-137).
  if (!isNew && isForbiddenDirect) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => navigate('/ai-insights/reports?tab=templates')}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">리포트 양식</h1>
        </div>
        <ProactiveReadForbiddenNotice subject="리포트 양식" testId="template-forbidden" />
      </div>
    );
  }

  // 쓰기 권한 없이 /ai-insights/templates/new 로 직접 들어오면 저장할 수 없는 빌더 대신 안내만 보인다(WD-133).
  if (isNew && !canWrite) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => navigate('/ai-insights/reports?tab=templates')}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">새 템플릿</h1>
        </div>
        <ProactiveWriteNotice subject="리포트 양식" testId="template-readonly-notice" />
        <Button variant="outline" size="sm" onClick={() => navigate('/ai-insights/reports?tab=templates')}>
          <ArrowLeft className="h-4 w-4" />
          목록으로 돌아가기
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 사이드바 링크 클릭 등 SPA 내부 네비게이션 가드 다이얼로그 (이슈 #552) */}
      {unsavedChangesDialog}
      {/* 헤더 */}
      {/* 좁은 화면에서 버튼 줄이 아래로 내려가 줄바꿈되게 한다 — 스마트 작업 상세와 같은 처리(WD-295) */}
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={handleBackClick}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div className="min-w-0">
            <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight break-words">
              {isNew ? '새 템플릿' : (template?.name ?? '-')}
            </h1>
            <div className="flex flex-wrap items-center gap-2 mt-1">
              {!isNew && template && (
                <Badge variant={isBuiltin ? 'secondary' : 'default'}>
                  {isBuiltin ? '기본' : '커스텀'}
                </Badge>
              )}
              {/* 미저장 변경사항 표시 — ChartBuilderPage/QueryEditorPage와 동일 시각 패턴 (이슈 #58) */}
              {isDirty && (
                <span className="text-xs text-muted-foreground flex items-center gap-1">
                  <span className="text-muted-foreground">●</span>
                  미저장 변경사항
                </span>
              )}
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/* 복제(POST)·편집(PUT)·삭제(DELETE)는 모두 쓰기 권한이 있을 때만 보인다(WD-133) — 기본 템플릿의 복제도 마찬가지 */}
          {!isNew && !isEditing && canWrite && (
            <>
              <Button variant="outline" size="sm" onClick={handleClone} disabled={createMutation.isPending}>
                <Copy className="h-3.5 w-3.5" />
                복제
              </Button>
              {!isBuiltin && (
                <>
                  <Button variant="outline" size="sm" onClick={() => setIsEditing(true)}>
                    <Pencil className="h-3.5 w-3.5" />
                    편집
                  </Button>
                  <Button
                    variant="destructive"
                    size="sm"
                    onClick={() => setDeleteDialogOpen(true)}
                    disabled={deleteMutation.isPending}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    삭제
                  </Button>
                </>
              )}
            </>
          )}
          {isEditing && (
            <>
              {!isNew && (
                <Button variant="ghost" size="sm" onClick={handleCancelEdit}>
                  취소
                </Button>
              )}
              {/* isValid: 이름 필드가 비어 있으면 생성/저장 버튼 비활성화 */}
              <Button variant="default" size="sm" onClick={handleSave} disabled={isSaving || !form.formState.isValid}>
                {isSaving ? '저장 중...' : isNew ? '생성' : '저장'}
              </Button>
            </>
          )}
        </div>
      </header>

      {/* 조회만 가능한 사용자에게 버튼이 없는 이유를 알린다 — 권한을 불러오는 중에는 깜빡이지 않게 숨긴다. */}
      {!isPermissionsLoading && !canWrite && (
        <ProactiveWriteNotice subject="리포트 양식" testId="template-readonly-notice" />
      )}

      {/* 메타 정보 (읽기 모드) */}
      {!isNew && !isEditing && template && (
        <div className="space-y-1">
          <div className="text-sm text-muted-foreground flex gap-4">
            {/* 긴 설명이 헤더를 무한정 늘리지 않도록 2줄로 제한하고, title로 전체 텍스트를 확인할 수 있게 한다 */}
            {template.description && (
              <span className="line-clamp-2 min-w-0 flex-1" title={template.description}>
                {template.description}
              </span>
            )}
            <span className="shrink-0">생성: {parseUtcDate(template.createdAt).toLocaleDateString('ko-KR')}</span>
            <span className="shrink-0">수정: {parseUtcDate(template.updatedAt).toLocaleDateString('ko-KR')}</span>
          </div>
          {template.style && (
            <div className="text-sm text-muted-foreground">
              <span className="font-medium text-foreground">스타일: </span>
              {/* 줄바꿈 없는 긴 텍스트가 뷰포트 밖으로 잘려 사라지지 않도록 줄바꿈 허용 + 2줄 제한 */}
              <span className="line-clamp-2 break-words" title={template.style}>
                {template.style}
              </span>
            </div>
          )}
        </div>
      )}

      {/* 이름/설명 편집 (편집 모드) */}
      {isEditing && (
        <Card>
          <CardContent className="pt-6">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="tpl-name">이름</Label>
                <Input
                  id="tpl-name"
                  {...form.register('name', {
                    onChange: markDirty,
                  })}
                  placeholder="리포트 템플릿 이름"
                />
                {form.formState.errors.name && (
                  <p className="text-sm text-destructive">{form.formState.errors.name.message}</p>
                )}
              </div>
              <div className="space-y-2">
                <Label htmlFor="tpl-desc">설명 (선택)</Label>
                <Input
                  id="tpl-desc"
                  {...form.register('description', {
                    onChange: markDirty,
                  })}
                  placeholder="템플릿 설명을 입력하세요"
                />
                {form.formState.errors.description && (
                  <p className="text-sm text-destructive">{form.formState.errors.description.message}</p>
                )}
              </div>
              <div className="col-span-2 space-y-2">
                <Label htmlFor="tpl-style">작성 스타일 (선택)</Label>
                <Textarea
                  id="tpl-style"
                  value={styleText}
                  onChange={(e) => handleStyleTextChange(e.target.value)}
                  placeholder="AI가 리포트를 작성할 때의 스타일을 기술하세요 (예: 경영진 보고서 스타일, 기술 분석 스타일 등)"
                  rows={2}
                />
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* 본문: 편집 모드 = 빌더 2-column, 읽기 모드 = 미리보기 */}
      {isEditing ? (
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
          {/* Left column: meta + tree builder */}
          <div className="lg:col-span-2 space-y-4">
            <Card className="h-[calc(100vh-320px)] min-h-[400px]">
              <SectionTreeBuilder
                sections={tree.sections}
                selectedKey={tree.selectedKey}
                collapsedKeys={tree.collapsedKeys}
                flatItems={tree.flatItems}
                onSelect={tree.setSelectedKey}
                onMove={(...args) => {
                  markDirty();
                  tree.moveSection(...args);
                }}
                onAdd={(...args) => {
                  markDirty();
                  tree.addSection(...args);
                }}
                onRemove={(...args) => {
                  markDirty();
                  tree.removeSection(...args);
                }}
                onToggleCollapse={tree.toggleCollapsed}
              />
            </Card>
          </div>

          {/* Right column: Builder/JSON tabs */}
          <div className="lg:col-span-3">
            <Tabs value={activeTab} onValueChange={handleTabChange}>
              <TabsList>
                <TabsTrigger value="builder">빌더</TabsTrigger>
                <TabsTrigger value="json">JSON</TabsTrigger>
              </TabsList>
              <TabsContent value="builder" className="mt-4">
                <SectionPropertyEditor
                  section={tree.selectedSection}
                  onUpdate={(patch) => {
                    if (tree.selectedKey) {
                      markDirty();
                      tree.updateSection(tree.selectedKey, patch);
                      // Key 필드가 변경되면 selectedKey도 새 값으로 따라가야
                      // selectedSection의 find(key === selectedKey)가 계속 대상을 찾을 수 있다 (#535)
                      if (patch.key && patch.key !== tree.selectedKey) {
                        tree.setSelectedKey(patch.key);
                      }
                    }
                  }}
                />
              </TabsContent>
              <TabsContent value="json" className="mt-4">
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">섹션 구조 (JSON)</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <TemplateJsonEditor
                      value={structureJson}
                      onChange={handleStructureJsonChange}
                      readonly={false}
                    />
                  </CardContent>
                </Card>
              </TabsContent>
            </Tabs>
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
          <div className="lg:col-span-3">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">섹션 구조</CardTitle>
              </CardHeader>
              <CardContent>
                <TemplateJsonEditor
                  value={structureJson}
                  onChange={setStructureJson}
                  readonly
                />
              </CardContent>
            </Card>
          </div>
          <div className="lg:col-span-2">
            <Card className="h-full">
              <CardContent className="pt-6 h-full">
                <h3 className="text-sm font-medium mb-4">섹션 구조 미리보기</h3>
                <SectionPreview sections={sections} />
              </CardContent>
            </Card>
          </div>
        </div>
      )}

      {/* 삭제 확인 다이얼로그 */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>템플릿 삭제</DialogTitle>
            <DialogDescription className="sr-only">리포트 템플릿을 삭제합니다. 되돌릴 수 없습니다.</DialogDescription>
          </DialogHeader>
          {/*
            삭제 영향 범위 고지 (#364) — 삭제를 막지는 않는다(FK가 SET NULL이라 DB도 허용).
            대신 어떤 템플릿인지와, 이 템플릿을 쓰는 작업이 기본 형식으로 전환된다는 사실을 알린다.
          */}
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">
              {template?.name ? `"${template.name}" 템플릿을 삭제하시겠습니까? 되돌릴 수 없습니다.` : '이 템플릿을 삭제하시겠습니까? 되돌릴 수 없습니다.'}
            </p>
            {affectedJobs.length > 0 && (
              <div className="rounded-md border border-destructive/40 bg-destructive/5 p-3" data-testid="template-delete-impact">
                <p className="text-sm font-medium text-destructive">
                  이 양식을 사용하는 스마트 작업 {affectedJobs.length}개가 기본 형식으로 전환됩니다.
                </p>
                <ul className="mt-2 space-y-1 text-sm text-muted-foreground list-disc list-inside">
                  {affectedJobs.map((job) => (
                    <li key={job.id}>
                      <button
                        type="button"
                        className="underline underline-offset-2 hover:text-foreground"
                        onClick={() => navigate(`/ai-insights/jobs/${job.id}`)}
                      >
                        {job.name}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteDialogOpen(false)}>취소</Button>
            <Button variant="destructive" onClick={handleDelete} disabled={deleteMutation.isPending}>
              삭제
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/*
        이탈 확인 다이얼로그 — 뒤로가기/취소 클릭 시 dirty면 표시 (이슈 #58)
        취소 시 머무름, 확인 시 변경사항 버리고 이탈/취소 동작 실행
      */}
      {/* 편집 충돌 안내 창(WD-364) — 저장이 409 EDIT_CONFLICT 로 거부되면 뜬다 */}
      <EditConflictDialog
        open={editConflict}
        onOpenChange={(open) => {
          if (!open) setEditConflict(false);
        }}
        resourceLabel="리포트 양식"
        onReload={handleReloadLatest}
        onOverwrite={() => submitTemplate(true)()}
        pending={isSaving || isReloading}
      />
      <AlertDialog open={leaveDialogOpen} onOpenChange={setLeaveDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>저장하지 않은 변경사항</AlertDialogTitle>
            <AlertDialogDescription>
              저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleLeaveConfirm}>이탈</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
