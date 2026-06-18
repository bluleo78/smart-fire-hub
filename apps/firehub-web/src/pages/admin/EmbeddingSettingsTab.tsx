import { Boxes, Eye, EyeOff, RefreshCw, RotateCcw, Save } from 'lucide-react';
import { useEffect, useState } from 'react';
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
  AlertDialogTrigger,
} from '../../components/ui/alert-dialog';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Separator } from '../../components/ui/separator';
import {
  useEmbeddingStatus,
  useReindexAllEmbeddings,
} from '../../hooks/queries/useEmbedding';
import {
  useEmbeddingSettings,
  useUpdateEmbeddingSettings,
} from '../../hooks/queries/useEmbeddingSettings';
import { type ReportDirty, useReportDirty } from '../../hooks/useUnsavedChangesGuard';

interface EmbeddingForm {
  'embedding.provider': string;
  'embedding.model': string;
  'embedding.base_url': string;
  'embedding.api_key': string;
}

// 백엔드 기본값과 동일하게 맞춘다 — 설정 미존재 시 폼 초기값으로 사용.
const DEFAULT: EmbeddingForm = {
  'embedding.provider': 'OLLAMA',
  'embedding.model': 'bge-m3',
  'embedding.base_url': 'http://host.docker.internal:11434',
  'embedding.api_key': '',
};

// provider 옵션 — Phase 1에서는 OLLAMA만 구현됨. VOYAGE/OPENAI는 팩토리가 예외를 던지므로
// 저장 시 런타임 오류를 막기 위해 비활성(disabled) 처리하고 "(준비 중)" 라벨을 붙인다.
const PROVIDER_OPTIONS: { value: string; label: string; disabled: boolean }[] = [
  { value: 'OLLAMA', label: 'Ollama', disabled: false },
  { value: 'VOYAGE', label: 'Voyage (준비 중)', disabled: true },
  { value: 'OPENAI', label: 'OpenAI (준비 중)', disabled: true },
];

// 재임베딩 진행 현황 한 줄(라벨 + 카운트 + 진행 바)을 렌더링한다.
// shadcn Progress 컴포넌트가 없어 muted/primary div 바로 직접 구성한다.
function ReindexProgressRow({
  label,
  embedded,
  total,
}: {
  label: string;
  embedded: number;
  total: number;
}) {
  // 대상이 0건이면 "완료"로 간주해 100%로 표시한다.
  const pct = total === 0 ? 100 : Math.round((embedded / total) * 100);
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between text-sm">
        <span className="text-muted-foreground">{label}</span>
        <span className="tabular-nums">
          {embedded} / {total}
        </span>
      </div>
      <div className="h-2 w-full rounded bg-muted">
        <div className="h-2 rounded bg-primary transition-all" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

interface EmbeddingSettingsTabProps {
  // 부모(SettingsPage)에 dirty 상태를 보고해 라우터 이탈 가드를 활성화한다 (이슈 #86 패턴).
  onReportDirty?: ReportDirty;
}

/**
 * 임베딩 설정 탭
 * - 문서 RAG에 사용할 임베딩 provider/모델/base_url/api_key를 조회·수정한다.
 * - api_key는 서버에서 마스킹되어 내려오므로, 사용자가 수정하지 않은(마스킹 유지) 값은 PUT에서 제외한다.
 */
export default function EmbeddingSettingsTab({ onReportDirty }: EmbeddingSettingsTabProps = {}) {
  const { data: settings, isLoading } = useEmbeddingSettings();
  const updateMutation = useUpdateEmbeddingSettings();

  // 재임베딩 카드용 — 현황 폴링 조회 및 전체 재임베딩 실행 mutation
  const { data: status } = useEmbeddingStatus();
  const reindex = useReindexAllEmbeddings();

  const [form, setForm] = useState<EmbeddingForm>(DEFAULT);
  const [original, setOriginal] = useState<EmbeddingForm>(DEFAULT);
  const [showApiKey, setShowApiKey] = useState(false);

  // 서버에서 settings가 로드되면 폼 상태에 반영 — 서버 데이터 → 폼 state 초기화 패턴
  useEffect(() => {
    if (!settings) return;
    const values = { ...DEFAULT };
    settings.forEach((s) => {
      const key = s.key as keyof EmbeddingForm;
      if (key in values) values[key] = s.value ?? '';
    });
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setForm(values);
    setOriginal(values);
  }, [settings]);

  const hasChanges = JSON.stringify(form) !== JSON.stringify(original);

  // 부모에 dirty 상태 보고 — SettingsPage가 라우터 가드(beforeunload 등)를 운영한다.
  useReportDirty(hasChanges, onReportDirty);

  const updateField = (key: keyof EmbeddingForm, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = async () => {
    const toSave: Record<string, string> = { ...form };
    // 마스킹된 api_key(****...)를 사용자가 수정하지 않았다면 PUT에서 제외한다.
    // (SMTP/AI 탭과 동일한 마스킹 스킵 로직 — 평문 키 유실 방지)
    if (toSave['embedding.api_key'].startsWith('****')) {
      delete toSave['embedding.api_key'];
    }
    updateMutation.mutate(
      { settings: toSave },
      {
        onSuccess: () => {
          setOriginal({ ...form });
          toast.success('임베딩 설정이 저장되었습니다.');
        },
        onError: () => toast.error('임베딩 설정 저장에 실패했습니다.'),
      },
    );
  };

  const handleReset = () => {
    setForm({ ...original });
  };

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  return (
    <div className="space-y-6">
      <Card className="card-hover">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Boxes className="h-4 w-4" />
            임베딩 provider 설정
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* Provider — OLLAMA만 선택 가능, 나머지는 disabled */}
          <div className="space-y-2">
            <Label htmlFor="embedding-provider">Provider</Label>
            <Select
              value={form['embedding.provider']}
              onValueChange={(value) => updateField('embedding.provider', value)}
            >
              <SelectTrigger id="embedding-provider" className="w-full max-w-md">
                <SelectValue placeholder="Provider를 선택하세요" />
              </SelectTrigger>
              <SelectContent>
                {PROVIDER_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value} value={opt.value} disabled={opt.disabled}>
                    {opt.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-sm text-muted-foreground">임베딩 생성에 사용할 provider</p>
          </div>

          <Separator />

          {/* Model */}
          <div className="space-y-2">
            <Label htmlFor="embedding-model">모델</Label>
            <Input
              id="embedding-model"
              className="max-w-md"
              value={form['embedding.model']}
              onChange={(e) => updateField('embedding.model', e.target.value)}
              placeholder="bge-m3"
            />
            <p className="text-sm text-muted-foreground">임베딩 모델 이름</p>
          </div>

          <Separator />

          {/* Base URL */}
          <div className="space-y-2">
            <Label htmlFor="embedding-base-url">Base URL</Label>
            <Input
              id="embedding-base-url"
              className="max-w-md"
              value={form['embedding.base_url']}
              onChange={(e) => updateField('embedding.base_url', e.target.value)}
              placeholder="http://host.docker.internal:11434"
            />
            <p className="text-sm text-muted-foreground">provider API 엔드포인트 주소</p>
          </div>

          <Separator />

          {/* API Key — 마스킹 스킵 로직 적용 */}
          <div className="space-y-2">
            <Label htmlFor="embedding-api-key">API 키</Label>
            <div className="relative max-w-md">
              <Input
                id="embedding-api-key"
                type={showApiKey ? 'text' : 'password'}
                className="pr-10 focus-visible:ring-2"
                value={form['embedding.api_key']}
                onChange={(e) => updateField('embedding.api_key', e.target.value)}
                placeholder="provider API 키 (Ollama는 불필요)"
              />
              <button
                type="button"
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                onClick={() => setShowApiKey((v) => !v)}
                aria-label={showApiKey ? 'API 키 숨기기' : 'API 키 보기'}
              >
                {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
            <p className="text-sm text-muted-foreground">
              Voyage/OpenAI 사용 시 필요. Ollama는 비워둡니다.
            </p>
          </div>

          <Separator />

          {/* 차원 안내 — 1024 고정값이며 설정 항목이 아니므로 읽기 전용 안내만 표시 */}
          <p className="text-sm text-muted-foreground">
            임베딩 차원은 1024로 고정됩니다. provider/모델 변경 시 기존 문서를 전체 재임베딩해야 합니다.
          </p>
        </CardContent>
      </Card>

      <div className="flex items-center gap-3">
        <Button onClick={handleSave} disabled={updateMutation.isPending || !hasChanges}>
          <Save className="h-4 w-4" />
          {updateMutation.isPending ? '저장 중...' : '저장'}
        </Button>
        <Button variant="outline" onClick={handleReset} disabled={!hasChanges}>
          <RotateCcw className="h-4 w-4" />
          되돌리기
        </Button>
      </div>

      {/* 재임베딩 — 현재 모델 기준 데이터셋·문서 청크 임베딩 진행 현황 및 전체 재임베딩 실행.
          provider 폼(카드+저장/되돌리기) 아래에 별도 카드로 배치한다. */}
      <Card className="card-hover">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <RefreshCw className="h-4 w-4" />
            재임베딩
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* 현재 임베딩 모델 — status 로딩 전에는 dash 표시 */}
          <div className="text-sm">
            <span className="text-muted-foreground">현재 모델: </span>
            <span className="font-medium">{status?.model ?? '—'}</span>
          </div>

          {/* 진행 현황 두 줄 — 데이터셋 카탈로그 / 문서 청크 */}
          <div className="space-y-4">
            <ReindexProgressRow
              label="데이터셋 카탈로그"
              embedded={status?.datasets.embedded ?? 0}
              total={status?.datasets.total ?? 0}
            />
            <ReindexProgressRow
              label="문서 청크"
              embedded={status?.documentChunks.embedded ?? 0}
              total={status?.documentChunks.total ?? 0}
            />
          </div>

          <Separator />

          {/* 전체 재임베딩 실행 — 비용/시간이 큰 작업이므로 AlertDialog로 한 번 더 확인 */}
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="outline" disabled={reindex.isPending}>
                <RefreshCw className="h-4 w-4" />
                {reindex.isPending ? '시작 중...' : '전체 재임베딩 실행'}
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>전체 재임베딩 실행</AlertDialogTitle>
                <AlertDialogDescription>
                  모든 데이터셋·문서를 현재 모델({status?.model ?? '—'})로 다시 임베딩합니다. 데이터
                  양에 따라 시간이 걸릴 수 있습니다.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>취소</AlertDialogCancel>
                <AlertDialogAction onClick={() => reindex.mutate()}>실행</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </CardContent>
      </Card>
    </div>
  );
}
