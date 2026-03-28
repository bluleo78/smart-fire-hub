import { FileText, Pencil, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Textarea } from '../../components/ui/textarea';
import type { ReportTemplate } from '../../api/proactive';
import {
  useCreateProactiveTemplate,
  useDeleteProactiveTemplate,
  useProactiveTemplates,
  useUpdateProactiveTemplate,
} from '../../hooks/queries/useProactiveMessages';

interface TemplateDialogState {
  open: boolean;
  mode: 'create' | 'edit';
  template: ReportTemplate | null;
}

export default function ReportTemplatesTab() {
  const { data: templates = [], isLoading } = useProactiveTemplates();
  const createMutation = useCreateProactiveTemplate();
  const updateMutation = useUpdateProactiveTemplate();
  const deleteMutation = useDeleteProactiveTemplate();

  const [dialog, setDialog] = useState<TemplateDialogState>({
    open: false,
    mode: 'create',
    template: null,
  });
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [structureJson, setStructureJson] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [deleteId, setDeleteId] = useState<number | null>(null);

  const builtins = templates.filter((t) => t.builtin);
  const customs = templates.filter((t) => !t.builtin);

  const openCreate = () => {
    setName('');
    setDescription('');
    setStructureJson(
      JSON.stringify(
        {
          sections: [
            { key: 'summary', label: '요약', required: true },
            { key: 'details', label: '상세 분석' },
          ],
        },
        null,
        2,
      ),
    );
    setJsonError('');
    setDialog({ open: true, mode: 'create', template: null });
  };

  const openEdit = (t: ReportTemplate) => {
    setName(t.name);
    setDescription(t.description ?? '');
    setStructureJson(JSON.stringify(t.structure, null, 2));
    setJsonError('');
    setDialog({ open: true, mode: 'edit', template: t });
  };

  const handleSubmit = () => {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(structureJson);
    } catch {
      setJsonError('올바른 JSON 형식이 아닙니다.');
      return;
    }

    if (dialog.mode === 'create') {
      createMutation.mutate(
        { name, description: description || undefined, structure: parsed },
        {
          onSuccess: () => {
            toast.success('템플릿이 생성되었습니다.');
            setDialog((d) => ({ ...d, open: false }));
          },
          onError: () => toast.error('템플릿 생성에 실패했습니다.'),
        },
      );
    } else if (dialog.template) {
      updateMutation.mutate(
        { id: dialog.template.id, data: { name, description, structure: parsed } },
        {
          onSuccess: () => {
            toast.success('템플릿이 수정되었습니다.');
            setDialog((d) => ({ ...d, open: false }));
          },
          onError: () => toast.error('템플릿 수정에 실패했습니다.'),
        },
      );
    }
  };

  const handleDelete = (id: number) => {
    deleteMutation.mutate(id, {
      onSuccess: () => toast.success('템플릿이 삭제되었습니다.'),
      onError: () => toast.error('템플릿 삭제에 실패했습니다.'),
    });
    setDeleteId(null);
  };

  const isPending = createMutation.isPending || updateMutation.isPending;

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  return (
    <div className="space-y-8">
      {/* Built-in templates */}
      <div className="space-y-4">
        <div>
          <h3 className="text-base font-semibold">기본 템플릿</h3>
          <p className="text-sm text-muted-foreground mt-1">시스템에서 제공하는 기본 리포트 템플릿입니다.</p>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {builtins.map((t) => (
            <Card key={t.id} className="bg-muted/20 border-dashed">
              <CardHeader className="pb-2">
                <div className="flex items-start justify-between gap-2">
                  <CardTitle className="text-sm font-medium">{t.name}</CardTitle>
                  <Badge variant="secondary" className="shrink-0 text-xs">기본</Badge>
                </div>
                {t.description && (
                  <CardDescription className="text-xs">{t.description}</CardDescription>
                )}
              </CardHeader>
              <CardContent>
                <p className="text-xs text-muted-foreground">
                  섹션 {Array.isArray((t.structure as { sections?: unknown[] })?.sections)
                    ? (t.structure as { sections: unknown[] }).sections.length
                    : 0}개
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>

      {/* Custom templates */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-base font-semibold">커스텀 템플릿</h3>
            <p className="text-sm text-muted-foreground mt-1">직접 만든 리포트 템플릿입니다.</p>
          </div>
          <Button size="sm" onClick={openCreate}>
            <Plus className="h-4 w-4" />
            템플릿 추가
          </Button>
        </div>

        {customs.length === 0 ? (
          <div className="rounded-lg border border-dashed flex flex-col items-center justify-center py-12 gap-3 text-center">
            <FileText className="h-10 w-10 text-muted-foreground" />
            <div>
              <p className="text-sm font-medium">커스텀 템플릿 없음</p>
              <p className="text-xs text-muted-foreground mt-1">
                나만의 리포트 구조를 만들어 스마트 작업에 사용하세요.
              </p>
            </div>
            <Button size="sm" variant="outline" onClick={openCreate}>
              <Plus className="h-4 w-4" />
              첫 템플릿 만들기
            </Button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {customs.map((t) => (
              <Card key={t.id} className="card-hover group">
                <CardHeader className="pb-2">
                  <div className="flex items-start justify-between gap-2">
                    <CardTitle className="text-sm font-medium">{t.name}</CardTitle>
                    <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      <Button
                        size="icon"
                        variant="ghost"
                        className="h-6 w-6"
                        aria-label="템플릿 수정"
                        onClick={() => openEdit(t)}
                      >
                        <Pencil className="h-3 w-3" />
                      </Button>
                      <Button
                        size="icon"
                        variant="ghost"
                        className="h-6 w-6 text-destructive hover:text-destructive"
                        aria-label="템플릿 삭제"
                        onClick={() => setDeleteId(t.id)}
                      >
                        <Trash2 className="h-3 w-3" />
                      </Button>
                    </div>
                  </div>
                  {t.description && (
                    <CardDescription className="text-xs">{t.description}</CardDescription>
                  )}
                </CardHeader>
                <CardContent>
                  <p className="text-xs text-muted-foreground">
                    섹션 {Array.isArray((t.structure as { sections?: unknown[] })?.sections)
                      ? (t.structure as { sections: unknown[] }).sections.length
                      : 0}개
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>

      {/* Create/Edit Dialog */}
      <Dialog open={dialog.open} onOpenChange={(open) => setDialog((d) => ({ ...d, open }))}>
        <DialogContent className="sm:max-w-lg max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>
              {dialog.mode === 'create' ? '템플릿 추가' : '템플릿 수정'}
            </DialogTitle>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="tpl-name">이름</Label>
              <Input
                id="tpl-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="일일 파이프라인 리포트"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="tpl-desc">설명 (선택)</Label>
              <Input
                id="tpl-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="템플릿 설명을 입력하세요"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="tpl-structure">섹션 구조 (JSON)</Label>
              <Textarea
                id="tpl-structure"
                rows={10}
                className="font-mono text-xs"
                value={structureJson}
                onChange={(e) => {
                  setStructureJson(e.target.value);
                  setJsonError('');
                }}
              />
              {jsonError && <p className="text-sm text-destructive">{jsonError}</p>}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog((d) => ({ ...d, open: false }))}>
              취소
            </Button>
            <Button onClick={handleSubmit} disabled={isPending || !name.trim()}>
              {isPending ? '저장 중...' : '저장'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirm dialog */}
      <Dialog open={deleteId !== null} onOpenChange={(open) => { if (!open) setDeleteId(null); }}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>템플릿 삭제</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">이 템플릿을 삭제하시겠습니까? 되돌릴 수 없습니다.</p>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteId(null)}>취소</Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={() => deleteId !== null && handleDelete(deleteId)}
            >
              삭제
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
