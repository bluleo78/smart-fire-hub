import { Boxes, PlugZap, RefreshCw, RotateCcw, Save } from 'lucide-react';

import type { EmbeddingProviderType } from '../../api/embedding';
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
import type { EmbeddingSettingsFormState } from '../../hooks/useEmbeddingSettingsForm';

// VOYAGE 는 팩토리가 거부하던 죽은 선택지라 제거했다(#713).
const PROVIDER_OPTIONS: { value: EmbeddingProviderType; label: string }[] = [
  { value: 'OLLAMA', label: 'Ollama' },
  { value: 'OPENAI', label: 'OpenAI' },
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

/**
 * 임베딩 설정 탭(#713) — <b>표현 전용</b> 컴포넌트다. 폼 상태는 `useEmbeddingSettingsForm` 이 갖고
 * 그 인스턴스는 `SettingsPage` 가 소유한다(탭 전환에도 편집이 살아남고, 이탈 가드에 dirty 를
 * 보고할 수 있다 — SmtpSettingsTab 과 같은 패턴, 리뷰 fix round 1).
 *
 * 저장 흐름: 연결 테스트로 차원 측정 → 그 (모델, 차원)의 재임베딩 대상 수 조회 → 0 보다 크면 확인 창 → PUT.
 * 서버 PUT 은 클라이언트 측정값을 믿지 않고 다시 probe 하며, 판정식이 참이면 재임베딩 잡을 스스로 투입한다.
 */
export default function EmbeddingSettingsTab({ state }: { state: EmbeddingSettingsFormState }) {
  const {
    isLoading,
    isError,
    retryLoad,
    config,
    status,
    form,
    testState,
    pending,
    busy,
    isSaving,
    isReindexing,
    setField,
    handleTest,
    handleSave,
    confirmPendingSave,
    cancelPendingSave,
    handleReindexAll,
  } = state;

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  // 조회 실패는 종단 상태다. 편집 가능한 빈 폼을 그리면 "미설정"으로 오인해 저장(연결 테스트도
  // 지금 폼 값으로 외부를 호출한다)으로 기존 설정을 덮어쓸 수 있으므로 원인과 재시도만 보여준다
  // (SmtpSettingsTab 과 같은 패턴).
  if (isError) {
    return (
      <div className="space-y-4 py-8 text-center">
        <InlineBanner variant="warning" title="임베딩 설정을 불러오지 못했습니다" className="text-left">
          지금 저장된 값을 확인할 수 없어 편집을 열지 않습니다.
        </InlineBanner>
        <Button variant="outline" onClick={retryLoad}>
          <RotateCcw className="h-4 w-4" />
          다시 시도
        </Button>
      </div>
    );
  }

  // 저장된 키는 저장된 Base URL 에만 쓸 수 있다(서버가 주소가 바뀐 저장의 키 재사용을 거부한다). 비교는 서버
  // 정규화(앞뒤 공백 제거 + UrlUtils.normalizeBaseUrl 의 끝 슬래시 1개 제거)와 맞춘다.
  const normalizeUrl = (u: string) => u.trim().replace(/\/$/, '');
  const baseUrlChanged =
    !!config?.configured && normalizeUrl(form.baseUrl) !== normalizeUrl(config.baseUrl ?? '');

  const fromLabel =
    config?.configured && config.model ? `${config.model} (${config.dimension})` : '미설정';

  return (
    <div className="space-y-6">
      {/* 미설정은 검색 기능이 멈춘 상태라 스펙 §6.1 은 오류 배너를 요구한다. InlineBanner 에는 오류 변형이 없어
          (디자인 시스템 04 §7 — warning/info/success/caution) 가장 강한 주의인 caution 을 쓴다. */}
      {config && !config.configured && (
        <InlineBanner variant="caution" title="임베딩이 설정되지 않았습니다">
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
              onValueChange={(v) => setField({ provider: v as EmbeddingProviderType })}
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
              onChange={(e) => setField({ model: e.target.value })}
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
              onChange={(e) => setField({ baseUrl: e.target.value })}
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
                  onChange={(e) => setField({ apiKey: e.target.value })}
                  placeholder="OpenAI API 키"
                />
                {config?.apiKeyMasked ? (
                  <p className="text-sm text-muted-foreground">
                    {baseUrlChanged
                      ? 'Base URL 을 바꾸면 API 키를 다시 입력해야 합니다'
                      : `저장된 키 ${config.apiKeyMasked} — 비우면 유지됩니다`}
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
            {/* 확인 뒤 PUT 이 도는 동안(isSaving)에도 막는다 — 저장 중 다른 값으로 외부 호출을 겹치지 않게. */}
            <Button variant="outline" onClick={handleTest} disabled={busy || isSaving}>
              <PlugZap className="h-4 w-4" />
              연결 테스트
            </Button>
            <Button onClick={handleSave} disabled={busy || isSaving}>
              <Save className="h-4 w-4" />
              저장
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* 재임베딩 확인 창 — 영향도가 0 보다 클 때만. 외부 API 비용과 검색 공백을 먼저 알린다. */}
      <AlertDialog open={pending !== null} onOpenChange={(open) => !open && cancelPendingSave()}>
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
            <AlertDialogAction onClick={confirmPendingSave}>저장하고 재임베딩</AlertDialogAction>
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
              {/* 사유가 비어 있으면 빈 배너 대신 다음 행동을 안내한다. */}
              {status.job.lastError || '재임베딩이 실패했습니다. 다시 시도하세요.'}
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
              <Button variant="outline" disabled={isReindexing || !status?.configured}>
                <RefreshCw className="h-4 w-4" />
                {isReindexing ? '시작 중...' : '전체 재임베딩 실행'}
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
                <AlertDialogAction onClick={handleReindexAll}>실행</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </CardContent>
      </Card>
    </div>
  );
}
