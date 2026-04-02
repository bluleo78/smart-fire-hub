import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, Copy, Pencil, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import type { TemplateSection } from '@/api/proactive';
import {
  useCreateProactiveTemplate,
  useDeleteProactiveTemplate,
  useProactiveTemplate,
  useUpdateProactiveTemplate,
} from '@/hooks/queries/useProactiveMessages';
import { handleApiError } from '@/lib/api-error';
import { type ReportTemplateFormValues, reportTemplateSchema } from '@/lib/validations/report-template';

import { SectionPreview } from './components/SectionPreview';
import { TemplateJsonEditor } from './components/TemplateJsonEditor';
import { TemplateSidePanel } from './components/TemplateSidePanel';

const DEFAULT_STRUCTURE = JSON.stringify({ sections: [] }, null, 2);

function parseSections(json: string): TemplateSection[] {
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed?.sections) ? parsed.sections : [];
  } catch {
    return [];
  }
}

export default function ReportTemplateDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = !id || id === 'new';
  const templateId = isNew ? 0 : Number(id);

  const { data: template, isLoading } = useProactiveTemplate(templateId);
  const createMutation = useCreateProactiveTemplate();
  const updateMutation = useUpdateProactiveTemplate();
  const deleteMutation = useDeleteProactiveTemplate();

  const [isEditing, setIsEditing] = useState(isNew);
  const [structureJson, setStructureJson] = useState(DEFAULT_STRUCTURE);
  const [jsonInitialized, setJsonInitialized] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);

  const form = useForm<ReportTemplateFormValues>({
    resolver: zodResolver(reportTemplateSchema),
    values: template ? { name: template.name, description: template.description ?? '' } : { name: '', description: '' },
  });

  // Sync template structure to JSON editor when loaded
  if (template && !jsonInitialized) {
    setStructureJson(JSON.stringify(template.structure, null, 2));
    setJsonInitialized(true);
  }

  const handleSave = form.handleSubmit((values) => {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(structureJson);
    } catch {
      toast.error('JSON 형식이 올바르지 않습니다.');
      return;
    }

    const payload = {
      name: values.name,
      description: values.description || undefined,
      structure: parsed,
    };

    if (isNew) {
      createMutation.mutate(payload, {
        onSuccess: (created) => {
          toast.success('템플릿이 생성되었습니다.');
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
        structure: template.structure,
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
      setStructureJson(JSON.stringify(template.structure, null, 2));
    }
    setIsEditing(false);
  };

  const isSaving = createMutation.isPending || updateMutation.isPending;

  if (!isNew && isLoading) {
    return (
      <div className="space-y-6">
        <div className="h-8 w-64 bg-muted animate-pulse rounded" />
        <div className="h-96 bg-muted animate-pulse rounded" />
      </div>
    );
  }

  const isBuiltin = template?.builtin ?? false;
  const sections = parseSections(structureJson);

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
                <Button variant="outline" size="sm" onClick={handleCancelEdit}>
                  취소
                </Button>
              )}
              <Button size="sm" onClick={handleSave} disabled={isSaving}>
                {isSaving ? '저장 중...' : isNew ? '생성' : '저장'}
              </Button>
            </>
          )}
        </div>
      </header>

      {/* 메타 정보 (읽기 모드) */}
      {!isNew && !isEditing && template && (
        <div className="text-sm text-muted-foreground flex gap-4">
          {template.description && <span>{template.description}</span>}
          <span>생성: {new Date(template.createdAt).toLocaleDateString('ko-KR')}</span>
          <span>수정: {new Date(template.updatedAt).toLocaleDateString('ko-KR')}</span>
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
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* 본문: 에디터 + 사이드패널 */}
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
                readonly={!isEditing}
              />
            </CardContent>
          </Card>
        </div>

        <div className="lg:col-span-2">
          <Card className="h-full">
            <CardContent className="pt-6 h-full">
              {isEditing ? (
                <TemplateSidePanel jsonValue={structureJson} />
              ) : (
                <div>
                  <h3 className="text-sm font-medium mb-4">섹션 구조 미리보기</h3>
                  <SectionPreview sections={sections} />
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      </div>

      {/* 삭제 확인 다이얼로그 */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>템플릿 삭제</DialogTitle>
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
