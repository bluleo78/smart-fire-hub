import { Pencil, Play, Plus, Trash2, Zap } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import type { ProactiveJob } from '../../api/proactive';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Checkbox } from '../../components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Switch } from '../../components/ui/switch';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { Textarea } from '../../components/ui/textarea';
import {
  useCreateProactiveJob,
  useDeleteProactiveJob,
  useExecuteProactiveJob,
  useProactiveJobs,
  useProactiveTemplates,
  useUpdateProactiveJob,
} from '../../hooks/queries/useProactiveMessages';
import { timeAgo } from '../../lib/formatters';

const CRON_PRESETS = [
  { label: '매일 오전 9시', value: '0 9 * * *' },
  { label: '매일 오전 8시', value: '0 8 * * *' },
  { label: '매주 월요일 오전 9시', value: '0 9 * * 1' },
  { label: '매시간', value: '0 * * * *' },
  { label: '직접 입력', value: '__custom__' },
];

function formatRelative(dateStr: string | null): string {
  if (!dateStr) return '-';
  return timeAgo(dateStr);
}

function jobStatusVariant(job: ProactiveJob): 'default' | 'secondary' | 'destructive' | 'outline' {
  if (!job.enabled) return 'secondary';
  const lastStatus = job.lastExecution?.status;
  if (lastStatus === 'FAILED') return 'destructive';
  if (lastStatus === 'RUNNING') return 'default';
  return 'outline';
}

function jobStatusLabel(job: ProactiveJob): string {
  if (!job.enabled) return '비활성';
  const lastStatus = job.lastExecution?.status;
  if (lastStatus === 'FAILED') return '실패';
  if (lastStatus === 'RUNNING') return '실행 중';
  if (lastStatus === 'COMPLETED') return '완료';
  return '대기';
}

interface JobFormState {
  name: string;
  prompt: string;
  templateId: string;
  cronPreset: string;
  cronExpression: string;
  targetAll: boolean;
  channelChat: boolean;
  channelEmail: boolean;
}

const DEFAULT_FORM: JobFormState = {
  name: '',
  prompt: '',
  templateId: '',
  cronPreset: '0 9 * * *',
  cronExpression: '0 9 * * *',
  targetAll: true,
  channelChat: true,
  channelEmail: false,
};

interface DialogState {
  open: boolean;
  mode: 'create' | 'edit';
  job: ProactiveJob | null;
}

export default function ProactiveJobsTab() {
  const { data: jobs = [], isLoading } = useProactiveJobs();
  const { data: templates = [] } = useProactiveTemplates();
  const createMutation = useCreateProactiveJob();
  const updateMutation = useUpdateProactiveJob();
  const deleteMutation = useDeleteProactiveJob();
  const executeMutation = useExecuteProactiveJob();

  const [dialog, setDialog] = useState<DialogState>({ open: false, mode: 'create', job: null });
  const [form, setForm] = useState<JobFormState>(DEFAULT_FORM);
  const [deleteId, setDeleteId] = useState<number | null>(null);

  const openCreate = () => {
    setForm(DEFAULT_FORM);
    setDialog({ open: true, mode: 'create', job: null });
  };

  const openEdit = (job: ProactiveJob) => {
    const config = job.config ?? {};
    const channels = Array.isArray(config.channels) ? (config.channels as string[]) : ['CHAT'];
    const cronExpr = job.cronExpression ?? '0 9 * * *';
    const matchPreset = CRON_PRESETS.find(
      (p) => p.value !== '__custom__' && p.value === cronExpr,
    );
    setForm({
      name: job.name,
      prompt: job.prompt,
      templateId: job.templateId ? String(job.templateId) : '',
      cronPreset: matchPreset ? cronExpr : '__custom__',
      cronExpression: cronExpr,
      targetAll: config.targets !== 'SELECTED',
      channelChat: channels.includes('CHAT'),
      channelEmail: channels.includes('EMAIL'),
    });
    setDialog({ open: true, mode: 'edit', job });
  };

  const updateForm = <K extends keyof JobFormState>(key: K, value: JobFormState[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const handleCronPresetChange = (value: string) => {
    updateForm('cronPreset', value);
    if (value !== '__custom__') updateForm('cronExpression', value);
  };

  const buildConfig = () => {
    const channels: string[] = [];
    if (form.channelChat) channels.push('CHAT');
    if (form.channelEmail) channels.push('EMAIL');
    return {
      channels,
      targets: form.targetAll ? 'ALL' : 'SELECTED',
    };
  };

  const handleSubmit = () => {
    const payload = {
      name: form.name,
      prompt: form.prompt,
      templateId: form.templateId ? Number(form.templateId) : null,
      cronExpression: form.cronExpression,
      config: buildConfig(),
    };

    if (dialog.mode === 'create') {
      createMutation.mutate(payload, {
        onSuccess: () => {
          toast.success('작업이 생성되었습니다.');
          setDialog((d) => ({ ...d, open: false }));
        },
        onError: () => toast.error('작업 생성에 실패했습니다.'),
      });
    } else if (dialog.job) {
      updateMutation.mutate(
        { id: dialog.job.id, data: payload },
        {
          onSuccess: () => {
            toast.success('작업이 수정되었습니다.');
            setDialog((d) => ({ ...d, open: false }));
          },
          onError: () => toast.error('작업 수정에 실패했습니다.'),
        },
      );
    }
  };

  const handleToggle = (job: ProactiveJob, enabled: boolean) => {
    updateMutation.mutate(
      { id: job.id, data: { enabled } },
      {
        onError: () => toast.error('상태 변경에 실패했습니다.'),
      },
    );
  };

  const handleExecute = (job: ProactiveJob) => {
    executeMutation.mutate(job.id, {
      onSuccess: () => {
        toast.success(`"${job.name}" 실행이 시작되었습니다.`, {
          action: { label: '결과 보기', onClick: () => {} },
        });
      },
      onError: () => toast.error('실행에 실패했습니다.'),
    });
  };

  const handleDelete = (id: number) => {
    deleteMutation.mutate(id, {
      onSuccess: () => toast.success('작업이 삭제되었습니다.'),
      onError: () => toast.error('작업 삭제에 실패했습니다.'),
    });
    setDeleteId(null);
  };

  const isPending = createMutation.isPending || updateMutation.isPending;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">
          스케줄에 따라 자동으로 AI 분석을 실행하고 결과를 전달합니다.
        </p>
        <Button size="sm" onClick={openCreate}>
          <Plus className="h-4 w-4" />
          작업 추가
        </Button>
      </div>

      {jobs.length === 0 && !isLoading ? (
        <div className="rounded-lg border border-dashed flex flex-col items-center justify-center py-16 gap-3 text-center">
          <Zap className="h-10 w-10 text-muted-foreground" />
          <div>
            <p className="text-sm font-medium">스마트 작업 없음</p>
            <p className="text-xs text-muted-foreground mt-1">
              AI가 주기적으로 데이터를 분석하고 리포트를 보내드립니다.
            </p>
          </div>
          <Button size="sm" variant="outline" onClick={openCreate}>
            <Plus className="h-4 w-4" />
            첫 작업 만들기
          </Button>
        </div>
      ) : (
        <div className="rounded-md border">
          <Table aria-label="스마트 작업 목록">
            <TableHeader>
              <TableRow>
                <TableHead>작업명</TableHead>
                <TableHead>실행 주기</TableHead>
                <TableHead>마지막 실행</TableHead>
                <TableHead>상태</TableHead>
                <TableHead>활성</TableHead>
                <TableHead className="w-[100px]" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                <TableRow>
                  <TableCell colSpan={6} className="text-center text-muted-foreground py-8">
                    불러오는 중...
                  </TableCell>
                </TableRow>
              ) : (
                jobs.map((job) => (
                  <TableRow key={job.id} className="group">
                    <TableCell className="font-medium">{job.name}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {job.cronExpression}
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {formatRelative(job.lastExecutedAt)}
                    </TableCell>
                    <TableCell>
                      <Badge variant={jobStatusVariant(job)}>{jobStatusLabel(job)}</Badge>
                    </TableCell>
                    <TableCell>
                      <Switch
                        checked={job.enabled}
                        aria-label={`${job.name} 활성화`}
                        onCheckedChange={(checked) => handleToggle(job, checked)}
                      />
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                        <Button
                          size="icon"
                          variant="ghost"
                          className="h-7 w-7"
                          aria-label="지금 실행"
                          onClick={() => handleExecute(job)}
                          disabled={executeMutation.isPending}
                        >
                          <Play className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          className="h-7 w-7"
                          aria-label="작업 수정"
                          onClick={() => openEdit(job)}
                        >
                          <Pencil className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          className="h-7 w-7 text-destructive hover:text-destructive"
                          aria-label="작업 삭제"
                          onClick={() => setDeleteId(job.id)}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      )}

      {/* Create/Edit Dialog */}
      <Dialog open={dialog.open} onOpenChange={(open) => setDialog((d) => ({ ...d, open }))}>
        <DialogContent className="sm:max-w-lg max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{dialog.mode === 'create' ? '작업 추가' : '작업 수정'}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4 py-2">
            {/* Name */}
            <div className="space-y-2">
              <Label htmlFor="job-name">작업 이름</Label>
              <Input
                id="job-name"
                value={form.name}
                onChange={(e) => updateForm('name', e.target.value)}
                placeholder="일일 파이프라인 리포트"
              />
            </div>

            {/* Prompt */}
            <div className="space-y-2">
              <Label htmlFor="job-prompt">분석 프롬프트</Label>
              <Textarea
                id="job-prompt"
                rows={4}
                value={form.prompt}
                onChange={(e) => updateForm('prompt', e.target.value)}
                placeholder="어제 파이프라인 실행 결과를 분석하고 주요 이슈를 알려주세요."
              />
            </div>

            {/* Template */}
            <div className="space-y-2">
              <Label htmlFor="job-template">리포트 템플릿 (선택)</Label>
              <Select
                value={form.templateId || 'none'}
                onValueChange={(v) => updateForm('templateId', v === 'none' ? '' : v)}
              >
                <SelectTrigger id="job-template">
                  <SelectValue placeholder="템플릿 선택 (없으면 기본)" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">기본 (선택 안 함)</SelectItem>
                  {templates.map((t) => (
                    <SelectItem key={t.id} value={String(t.id)}>
                      {t.name}
                      {t.builtin && ' (기본)'}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {/* Cron preset */}
            <div className="space-y-2">
              <Label htmlFor="job-cron-preset">실행 주기</Label>
              <Select value={form.cronPreset} onValueChange={handleCronPresetChange}>
                <SelectTrigger id="job-cron-preset">
                  <SelectValue placeholder="실행 주기 선택" />
                </SelectTrigger>
                <SelectContent>
                  {CRON_PRESETS.map((p) => (
                    <SelectItem key={p.value} value={p.value}>
                      {p.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {form.cronPreset === '__custom__' && (
                <div className="space-y-1">
                  <Input
                    value={form.cronExpression}
                    onChange={(e) => updateForm('cronExpression', e.target.value)}
                    placeholder="0 9 * * *"
                    className="font-mono text-sm"
                  />
                  <p className="text-xs text-muted-foreground">Cron 표현식 (분 시 일 월 요일)</p>
                </div>
              )}
            </div>

            {/* Target scope */}
            <div className="space-y-2">
              <Label>분석 대상</Label>
              <div className="flex gap-4">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="target"
                    checked={form.targetAll}
                    onChange={() => updateForm('targetAll', true)}
                    className="accent-primary"
                  />
                  <span className="text-sm">전체</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="target"
                    checked={!form.targetAll}
                    onChange={() => updateForm('targetAll', false)}
                    className="accent-primary"
                  />
                  <span className="text-sm">선택</span>
                </label>
              </div>
            </div>

            {/* Delivery channels */}
            <div className="space-y-2">
              <Label>전달 채널</Label>
              <div className="flex gap-4">
                <label className="flex items-center gap-2 cursor-pointer">
                  <Checkbox
                    id="ch-chat"
                    checked={form.channelChat}
                    onCheckedChange={(checked) => updateForm('channelChat', !!checked)}
                  />
                  <span className="text-sm">AI 챗</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <Checkbox
                    id="ch-email"
                    checked={form.channelEmail}
                    onCheckedChange={(checked) => updateForm('channelEmail', !!checked)}
                  />
                  <span className="text-sm">이메일</span>
                </label>
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog((d) => ({ ...d, open: false }))}>
              취소
            </Button>
            <Button onClick={handleSubmit} disabled={isPending || !form.name.trim()}>
              {isPending ? '저장 중...' : '저장'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirm */}
      <Dialog open={deleteId !== null} onOpenChange={(open) => { if (!open) setDeleteId(null); }}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>작업 삭제</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground">이 작업을 삭제하시겠습니까? 되돌릴 수 없습니다.</p>
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
