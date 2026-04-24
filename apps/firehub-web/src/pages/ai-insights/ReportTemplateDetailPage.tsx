import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Copy, Pencil, Trash2 } from 'lucide-react';
import { useCallback, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

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

  const { data: templateDirect, isLoading: isLoadingDirect } = useProactiveTemplate(templateId);
  const { data: templates = [], isLoading: isLoadingList } = useProactiveTemplates();
  // Fallback: use list data if single-item API fails
  const template = templateDirect ?? templates.find((t) => t.id === templateId);
  const isLoading = isLoadingDirect && isLoadingList;
  const createMutation = useCreateProactiveTemplate();
  const updateMutation = useUpdateProactiveTemplate();
  const deleteMutation = useDeleteProactiveTemplate();

  const [isEditing, setIsEditing] = useState(isNew);
  const [structureJson, setStructureJson] = useState(DEFAULT_STRUCTURE);
  const [styleText, setStyleText] = useState('');
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<'builder' | 'json'>('builder');

  const tree = useSectionTree([]);

  // 이전 값 추적용 state (React 19 권장: 렌더링 중 state 조정 시 ref 대신 state 사용)
  const [prevTemplate, setPrevTemplate] = useState(template);
  const [prevTreeSections, setPrevTreeSections] = useState(tree.sections);
  const [suppressTreeSync, setSuppressTreeSync] = useState(false);

  const form = useForm<ReportTemplateFormValues>({
    resolver: zodResolver(reportTemplateSchema),
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

  const handleCancelEdit = () => {
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
            onClick={() => navigate('/ai-insights/templates')}
            aria-label="목록으로"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">
              {isNew ? '새 템플릿' : (template?.name ?? '-')}
            </h1>
            {!isNew && template && (
              <Badge variant={isBuiltin ? 'secondary' : 'default'} className="mt-1">
                {isBuiltin ? '기본' : '커스텀'}
              </Badge>
            )}
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
              <Button variant="default" size="sm" onClick={handleSave} disabled={isSaving}>
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
                  {...form.register('name')}
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
                  {...form.register('description')}
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
                  onChange={(e) => setStyleText(e.target.value)}
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
                onMove={tree.moveSection}
                onAdd={tree.addSection}
                onRemove={tree.removeSection}
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
                    if (tree.selectedKey) tree.updateSection(tree.selectedKey, patch);
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
                      onChange={setStructureJson}
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
    </div>
  );
}
