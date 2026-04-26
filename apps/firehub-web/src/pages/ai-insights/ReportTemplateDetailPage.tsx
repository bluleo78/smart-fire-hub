import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Copy, Pencil, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

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
import {
  useCreateProactiveTemplate,
  useDeleteProactiveTemplate,
  useProactiveTemplate,
  useProactiveTemplates,
  useUpdateProactiveTemplate,
} from '@/hooks/queries/useProactiveMessages';
import { handleApiError } from '@/lib/api-error';
import { parseTemplateSections } from '@/lib/template-section-types';
import { type ReportTemplateFormValues, reportTemplateSchema } from '@/lib/validations/report-template';

import { SectionPreview } from './components/SectionPreview';
import { SectionPropertyEditor } from './components/SectionPropertyEditor';
import { SectionTreeBuilder } from './components/SectionTreeBuilder';
import { TemplateJsonEditor } from './components/TemplateJsonEditor';
import { useSectionTree } from './hooks/useSectionTree';

const DEFAULT_STRUCTURE = JSON.stringify({ sections: [] }, null, 2);

export default function ReportTemplateDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = !id || id === 'new';
  const templateId = isNew ? 0 : Number(id);

  const { data: templateDirect, isLoading: isLoadingDirect, isError: isErrorDirect } = useProactiveTemplate(templateId);
  const { data: templates = [], isLoading: isLoadingList } = useProactiveTemplates();
  // Fallback: use list data if single-item API fails
  const template = templateDirect ?? templates.find((t) => t.id === templateId);
  const isLoading = isLoadingDirect && isLoadingList;

  // 존재하지 않는 템플릿 ID 접근 시 에러 처리 — toast + 목록 페이지로 이동 (#38)
  // useEffect를 훅 순서 보장을 위해 조건부 return 이전에 배치한다
  useEffect(() => {
    if (isNew) return;
    if (isErrorDirect) {
      toast.error('템플릿 정보를 불러오는데 실패했습니다.');
      navigate('/ai-insights/templates');
    }
  }, [isErrorDirect, isNew, navigate]);

  useEffect(() => {
    if (isNew) return;
    if (!isLoading && !template) {
      toast.error('존재하지 않는 템플릿입니다.');
      navigate('/ai-insights/templates');
    }
  }, [isLoading, isNew, navigate, template]);

  const createMutation = useCreateProactiveTemplate();
  const updateMutation = useUpdateProactiveTemplate();
  const deleteMutation = useDeleteProactiveTemplate();

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
  const [prevTemplate, setPrevTemplate] = useState(template);
  const [prevTreeSections, setPrevTreeSections] = useState(tree.sections);
  const [suppressTreeSync, setSuppressTreeSync] = useState(false);

  // mode: 'onChange' — 이름 필드 실시간 유효성 반영으로 저장 버튼 비활성화 지원
  const form = useForm<ReportTemplateFormValues>({
    resolver: zodResolver(reportTemplateSchema),
    mode: 'onChange',
    values: template ? { name: template.name, description: template.description ?? '' } : { name: '', description: '' },
  });

  // 템플릿 로드 시 구조/스타일 동기화 — 렌더링 중 state 조정 (React 권장 패턴)
  if (template && template !== prevTemplate && !isEditing) {
    setPrevTemplate(template);
    const sections = Array.isArray(template.sections) ? template.sections : [];
    const json = JSON.stringify({ sections, output_format: 'markdown' }, null, 2);
    setStructureJson(json);
    setStyleText(template.style ?? '');
    tree.setSections(sections);
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

  // 브라우저 탭 닫기·새로고침 시 이탈 경고 (이슈 #58 — ChartBuilderPage/QueryEditorPage와 동일 패턴)
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (isDirty) e.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [isDirty]);

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
        if (parsed) {
          setSuppressTreeSync(true);
          tree.setSections(parsed);
        } else {
          toast.error('JSON 파싱 실패. 유효한 JSON을 입력하세요.');
          return;
        }
      }
      setActiveTab(tab as 'builder' | 'json');
    },
    [activeTab, structureJson, tree],
  );

  const handleSave = form.handleSubmit((values) => {
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

    // 섹션 Key 유효성 검사 (#36)
    const invalidKey = resolvedSections.find((s) => !/^[a-z][a-z0-9_]*$/.test(s.key));
    if (invalidKey) {
      toast.error(`섹션 Key "${invalidKey.key}"가 올바르지 않습니다. 영문 소문자, 숫자, 밑줄만 사용하세요.`);
      return;
    }

    // 섹션 Key 중복 검사 (#38)
    const keySet = new Set<string>();
    for (const s of resolvedSections) {
      if (keySet.has(s.key)) {
        toast.error(`섹션 Key가 중복되었습니다: "${s.key}"`);
        return;
      }
      keySet.add(s.key);
    }

    const payload = {
      name: values.name,
      description: values.description || undefined,
      sections: resolvedSections,
      style: styleText.trim() || undefined,
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
        { id: templateId, data: payload },
        {
          onSuccess: () => {
            toast.success('템플릿이 수정되었습니다.');
            // 수정 성공 → dirty 해제 후 읽기 모드로 복귀 (이슈 #58)
            setIsDirty(false);
            setIsEditing(false);
          },
          onError: (err) => handleApiError(err, '템플릿 수정에 실패했습니다.'),
        },
      );
    }
  });

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

  if (!isNew && isLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-64 bg-muted animate-pulse rounded" />
        <div className="h-96 bg-muted animate-pulse rounded" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            onClick={handleBackClick}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">
              {isNew ? '새 템플릿' : (template?.name ?? '-')}
            </h1>
            <div className="flex items-center gap-2 mt-1">
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

        <div className="flex items-center gap-2">
          {!isNew && !isEditing && (
            <>
              <Button variant="outline" size="sm" onClick={handleClone} disabled={createMutation.isPending}>
                <Copy className="h-3.5 w-3.5 mr-1" />
                복제
              </Button>
              {!isBuiltin && (
                <>
                  <Button variant="outline" size="sm" onClick={() => setIsEditing(true)}>
                    <Pencil className="h-3.5 w-3.5 mr-1" />
                    편집
                  </Button>
                  <Button
                    variant="destructive"
                    size="sm"
                    onClick={() => setDeleteDialogOpen(true)}
                    disabled={deleteMutation.isPending}
                  >
                    <Trash2 className="h-3.5 w-3.5 mr-1" />
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

      {/* 메타 정보 (읽기 모드) */}
      {!isNew && !isEditing && template && (
        <div className="space-y-1">
          <div className="text-sm text-muted-foreground flex gap-4">
            {template.description && <span>{template.description}</span>}
            <span>생성: {new Date(template.createdAt).toLocaleDateString('ko-KR')}</span>
            <span>수정: {new Date(template.updatedAt).toLocaleDateString('ko-KR')}</span>
          </div>
          {template.style && (
            <div className="text-sm text-muted-foreground">
              <span className="font-medium text-foreground">스타일: </span>
              {template.style}
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
          <p className="text-sm text-muted-foreground">이 템플릿을 삭제하시겠습니까? 되돌릴 수 없습니다.</p>
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
