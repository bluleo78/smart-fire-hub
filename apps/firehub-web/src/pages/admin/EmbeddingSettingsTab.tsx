import { Boxes, PlugZap, RefreshCw, Save } from 'lucide-react';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';

import {
  embeddingApi,
  type EmbeddingConfigRequest,
  type EmbeddingImpact,
  type EmbeddingProviderType,
} from '../../api/embedding';
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
import { InlineBanner } from '../../components/ui/inline-banner';
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
import { useEmbeddingStatus, useReindexAllEmbeddings } from '../../hooks/queries/useEmbedding';
import { useEmbeddingConfig, useSaveEmbeddingConfig } from '../../hooks/queries/useEmbeddingSettings';
import { extractApiError } from '../../lib/api-error';

interface EmbeddingForm {
  provider: EmbeddingProviderType;
  model: string;
  baseUrl: string;
  apiKey: string;
}

// 미설정 테넌트의 시작 폼. 기본값을 "적용 중인 값"처럼 보이지 않게 모델·주소는 비워 둔다.
const EMPTY: EmbeddingForm = { provider: 'OLLAMA', model: '', baseUrl: '', apiKey: '' };

// VOYAGE 는 팩토리가 거부하던 죽은 선택지라 제거했다(#713).
const PROVIDER_OPTIONS: { value: EmbeddingProviderType; label: string }[] = [
  { value: 'OLLAMA', label: 'Ollama' },
  { value: 'OPENAI', label: 'OpenAI' },
];

type TestState = { ok: true; dimension: number } | { ok: false; message: string } | null;

/** 저장 확인 창에 필요한 값 — 클라이언트가 먼저 잰 차원과 영향도(서버 PUT 은 다시 probe 한다). */
interface PendingSave {
  request: EmbeddingConfigRequest;
  dimension: number;
  impact: EmbeddingImpact;
}

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

/**
 * 임베딩 설정 탭(#713) — 테넌트가 provider·모델·Base URL·키를 직접 저장한다(플랫폼 값·폴백 없음).
 *
 * 저장 흐름: 연결 테스트로 차원 측정 → 그 (모델, 차원)의 재임베딩 대상 수 조회 → 0 보다 크면 확인 창 → PUT.
 * 서버 PUT 은 클라이언트 측정값을 믿지 않고 다시 probe 하며, 판정식이 참이면 재임베딩 잡을 스스로 투입한다.
 */
export default function EmbeddingSettingsTab() {
  const { data: config, isLoading } = useEmbeddingConfig();
  const save = useSaveEmbeddingConfig();
  const { data: status } = useEmbeddingStatus();
  const reindex = useReindexAllEmbeddings();

  const [form, setForm] = useState<EmbeddingForm>(EMPTY);
  const [testState, setTestState] = useState<TestState>(null);
  const [pending, setPending] = useState<PendingSave | null>(null);
  const [busy, setBusy] = useState(false);

  // 서버 설정 → 폼. 키는 값으로 내려오지 않으므로 항상 빈 칸에서 시작한다(비우면 유지).
  useEffect(() => {
    if (!config) return;
    setForm(
      config.configured
        ? {
            provider: config.provider ?? 'OLLAMA',
            model: config.model ?? '',
            baseUrl: config.baseUrl ?? '',
            apiKey: '',
          }
        : EMPTY,
    );
  }, [config]);

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  // 빈 키는 보내지 않는다 — 서버 계약상 "생략 = 기존 키 유지"다.
  const buildRequest = (): EmbeddingConfigRequest => ({
    provider: form.provider,
    model: form.model.trim(),
    baseUrl: form.baseUrl.trim(),
    ...(form.provider === 'OPENAI' && form.apiKey ? { apiKey: form.apiKey } : {}),
  });

  const handleTest = async () => {
    setBusy(true);
    try {
      const { data } = await embeddingApi.testConfig(buildRequest());
      setTestState({ ok: true, dimension: data.dimension });
    } catch (e) {
      setTestState({ ok: false, message: extractApiError(e, '연결 테스트에 실패했습니다.') });
    } finally {
      setBusy(false);
    }
  };

  const commitSave = async (request: EmbeddingConfigRequest) => {
    await save.mutateAsync(request);
    toast.success('임베딩 설정을 저장했습니다');
    setPending(null);
    setForm((f) => ({ ...f, apiKey: '' }));
  };

  const handleSave = async () => {
    const request = buildRequest();
    setBusy(true);
    try {
      const { data: probe } = await embeddingApi.testConfig(request);
      setTestState({ ok: true, dimension: probe.dimension });
      const { data: impact } = await embeddingApi.getImpact({
        model: request.model,
        dimension: probe.dimension,
      });
      if (impact.chunks + impact.datasets + impact.rowSearchIndexes > 0) {
        setPending({ request, dimension: probe.dimension, impact });
      } else {
        await commitSave(request);
      }
    } catch (e) {
      toast.error(extractApiError(e, '임베딩 설정을 저장하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const fromLabel =
    config?.configured && config.model ? `${config.model} (${config.dimension})` : '미설정';

  return (
    <div className="space-y-6">
      {config && !config.configured && (
        <InlineBanner variant="warning" title="임베딩이 설정되지 않았습니다">
          문서 검색·데이터셋 탐색·행 검색이 동작하지 않습니다. 아래에서 임베딩 provider 를 설정하고
          저장하세요.
        </InlineBanner>
      )}

      <Card className="card-hover">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Boxes className="h-4 w-4" />
            임베딩 provider 설정
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="space-y-2">
            <Label htmlFor="embedding-provider">Provider</Label>
            <Select
              value={form.provider}
              onValueChange={(v) => setForm((f) => ({ ...f, provider: v as EmbeddingProviderType }))}
            >
              <SelectTrigger id="embedding-provider" className="w-full max-w-md">
                <SelectValue placeholder="Provider를 선택하세요" />
              </SelectTrigger>
              <SelectContent>
                {PROVIDER_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value} value={opt.value}>
                    {opt.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <Separator />

          <div className="space-y-2">
            <Label htmlFor="embedding-model">모델</Label>
            <Input
              id="embedding-model"
              className="max-w-md"
              value={form.model}
              onChange={(e) => setForm((f) => ({ ...f, model: e.target.value }))}
              placeholder={form.provider === 'OPENAI' ? 'text-embedding-3-small' : 'bge-m3'}
            />
            <p className="text-sm text-muted-foreground">
              지원 차원은 1024, 1536 입니다. 차원은 연결 테스트로 자동 측정됩니다.
            </p>
          </div>

          <Separator />

          <div className="space-y-2">
            <Label htmlFor="embedding-base-url">Base URL</Label>
            <Input
              id="embedding-base-url"
              className="max-w-md"
              value={form.baseUrl}
              onChange={(e) => setForm((f) => ({ ...f, baseUrl: e.target.value }))}
              placeholder={
                form.provider === 'OPENAI' ? 'https://api.openai.com' : 'http://host.docker.internal:11434'
              }
            />
          </div>

          {form.provider === 'OPENAI' && (
            <>
              <Separator />
              <div className="space-y-2">
                <Label htmlFor="embedding-api-key">API 키</Label>
                <Input
                  id="embedding-api-key"
                  type="password"
                  autoComplete="off"
                  className="max-w-md"
                  value={form.apiKey}
                  onChange={(e) => setForm((f) => ({ ...f, apiKey: e.target.value }))}
                  placeholder="OpenAI API 키"
                />
                {config?.apiKeyMasked ? (
                  <p className="text-sm text-muted-foreground">
                    저장된 키 {config.apiKeyMasked} — 비우면 유지됩니다
                  </p>
                ) : null}
              </div>
            </>
          )}

          {testState && (
            <p
              role="status"
              className={testState.ok ? 'text-sm text-success' : 'text-sm text-destructive'}
            >
              {testState.ok ? `연결 성공 · ${testState.dimension}차원` : testState.message}
            </p>
          )}

          <div className="flex gap-2">
            <Button variant="outline" onClick={handleTest} disabled={busy}>
              <PlugZap className="h-4 w-4" />
              연결 테스트
            </Button>
            <Button onClick={handleSave} disabled={busy || save.isPending}>
              <Save className="h-4 w-4" />
              저장
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 재임베딩 확인 창 — 영향도가 0 보다 클 때만. 외부 API 비용과 검색 공백을 먼저 알린다. */}
      <AlertDialog open={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>임베딩 모델 변경</AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="space-y-2">
                <p>
                  {fromLabel} → {pending?.request.model} ({pending?.dimension})
                </p>
                <p>
                  문서 청크 {pending?.impact.chunks} · 데이터셋 {pending?.impact.datasets} · 행 검색
                  색인 {pending?.impact.rowSearchIndexes} 개를 다시 임베딩합니다. 외부 API 는 비용이
                  발생합니다. 완료 전까지 의미 검색은 새로 임베딩된 부분만 찾습니다.
                </p>
              </div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (!pending) return;
                commitSave(pending.request).catch((e) =>
                  toast.error(extractApiError(e, '임베딩 설정을 저장하지 못했습니다.')),
                );
              }}
            >
              저장하고 재임베딩
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* 재임베딩 — 현재 공간 기준 진행률 + 잡 상태 + 전체 재임베딩 실행 */}
      <Card className="card-hover">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <RefreshCw className="h-4 w-4" />
            재임베딩
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="text-sm">
            <span className="text-muted-foreground">현재 모델: </span>
            <span className="font-medium">
              {status?.configured ? `${status.model} · ${status.dimension}차원` : '—'}
            </span>
          </div>

          {status?.job?.status === 'FAILED' && (
            <InlineBanner variant="warning" title="재임베딩 실패">
              {status.job.lastError}
            </InlineBanner>
          )}

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

          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="outline" disabled={reindex.isPending || !status?.configured}>
                <RefreshCw className="h-4 w-4" />
                {reindex.isPending ? '시작 중...' : '전체 재임베딩 실행'}
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>전체 재임베딩 실행</AlertDialogTitle>
                <AlertDialogDescription>
                  현재 모델({status?.model ?? '—'})로 아직 임베딩되지 않은 데이터셋·문서를 다시
                  임베딩합니다. 데이터 양에 따라 시간이 걸릴 수 있습니다.
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
