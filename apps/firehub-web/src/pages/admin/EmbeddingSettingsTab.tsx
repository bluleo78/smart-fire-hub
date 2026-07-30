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
import { handleApiError } from '../../lib/api-error';

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

// provider 옵션 — OLLAMA(로컬)와 OPENAI(클라우드) 구현됨. VOYAGE는 팩토리가 아직 예외를 던지므로
// 저장 시 런타임 오류를 막기 위해 비활성(disabled) 처리하고 "(준비 중)" 라벨을 유지한다.
const PROVIDER_OPTIONS: { value: string; label: string; disabled: boolean }[] = [
  { value: 'OLLAMA', label: 'Ollama', disabled: false },
  { value: 'OPENAI', label: 'OpenAI', disabled: false },
  { value: 'VOYAGE', label: 'Voyage (준비 중)', disabled: true },
];

// provider별 권장 기본값 — provider 전환 시 이전 기본값 그대로였던(사용자 미변경) 모델/base_url을
// 새 provider 기본값으로 자동 교체하는 데 사용한다. OpenAI는 dimensions:1024 축소를 지원하는 3-small 기본.
const PROVIDER_DEFAULTS: Record<string, { model: string; baseUrl: string }> = {
  OLLAMA: { model: 'bge-m3', baseUrl: 'http://host.docker.internal:11434' },
  OPENAI: { model: 'text-embedding-3-small', baseUrl: 'https://api.openai.com' },
  VOYAGE: { model: 'voyage-3', baseUrl: 'https://api.voyageai.com' },
};

// 폼 필드별 검증 오류. 서버(SettingsService.validateEmbeddingConsistency)와 같은 규칙을 미러링해
// 저장 전에 인라인으로 알려준다 — 잘못된 조합이 저장되면 실패가 임베딩 호출 런타임에야 드러나기 때문(#322, #323).
interface EmbeddingFormErrors {
  model?: string;
  baseUrl?: string;
  apiKey?: string;
}

// base_url 이 http/https 스킴과 호스트를 갖춘 절대 URL 인지 확인하고 스킴을 반환한다.
function parseBaseUrlScheme(baseUrl: string): string | null {
  try {
    const url = new URL(baseUrl.trim());
    if (!url.hostname) return null;
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.protocol : null;
  } catch {
    return null;
  }
}

/**
 * provider별 필수값·형식을 검증한다.
 * - 모델/Base URL은 provider와 무관하게 필수이며 Base URL은 절대 URL이어야 한다.
 * - OPENAI는 Bearer 인증이 필수라 API 키가 있어야 하고, 엔드포인트는 https여야 한다
 *   (http 주소가 남아 있으면 Ollama 등 이전 provider 주소가 잔존한 불일치 신호).
 * - API 키가 마스킹(`****`)된 상태는 "서버에 저장된 키가 있음"을 뜻하므로 유효값으로 본다.
 */
function validateEmbeddingForm(form: EmbeddingForm): EmbeddingFormErrors {
  const errors: EmbeddingFormErrors = {};
  const baseUrl = form['embedding.base_url'].trim();

  if (!form['embedding.model'].trim()) errors.model = '모델을 입력하세요.';

  if (!baseUrl) {
    errors.baseUrl = 'Base URL을 입력하세요.';
  } else if (!parseBaseUrlScheme(baseUrl)) {
    errors.baseUrl = 'Base URL은 http:// 또는 https:// 로 시작하는 올바른 주소여야 합니다.';
  } else if (form['embedding.provider'] === 'OPENAI' && parseBaseUrlScheme(baseUrl) !== 'https:') {
    errors.baseUrl = 'OpenAI provider의 Base URL은 https 주소여야 합니다.';
  }

  if (form['embedding.provider'] === 'OPENAI' && !form['embedding.api_key'].trim()) {
    errors.apiKey = 'OpenAI provider에는 API 키가 필요합니다.';
  }

  return errors;
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
  // provider 전환으로 모델/Base URL이 덮어써졌는지 — 안내 문구 노출 조건.
  // original과의 비교로 유도하지 않는 이유: A→B→A로 되돌아오면 provider는 original과 같아지지만
  // 그 사이 모델/base_url은 A의 기본값으로 덮여 사용자의 커스텀 값이 사라진 상태라 안내가 필요하다.
  const [providerJustSwitched, setProviderJustSwitched] = useState(false);

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

  // 인라인 검증 결과 — 오류가 있으면 저장 버튼을 막는다(값 유효성과 무관하던 기존 dirty-only 조건 보완).
  const errors = validateEmbeddingForm(form);
  const hasValidationErrors = Object.keys(errors).length > 0;

  // 부모에 dirty 상태 보고 — SettingsPage가 라우터 가드(beforeunload 등)를 운영한다.
  useReportDirty(hasChanges, onReportDirty);

  const updateField = (key: keyof EmbeddingForm, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  // provider 변경 — 모델/base_url을 새 provider 권장 기본값으로 항상 교체한다.
  // 예전에는 "이전 provider 기본값과 문자열이 같을 때만" 교체했는데, 기본값과 호스트만 다른
  // 동종 주소(예: http://localhost:11434 vs 기본값 http://host.docker.internal:11434)를
  // 사용자 커스텀으로 오판해 Ollama 주소가 OpenAI provider에 남는 불일치를 만들었다(#322).
  // 한 provider의 엔드포인트/모델은 다른 provider에서 어차피 무효이므로 보존 가치가 없다.
  // api_key는 의도적으로 건드리지 않는다 — 지우면 임시로 provider를 바꿨다가 되돌릴 때
  // 서버에 저장된 키까지 빈 값으로 덮어써 유실되기 때문이다.
  const handleProviderChange = (next: string) => {
    setProviderJustSwitched(Boolean(PROVIDER_DEFAULTS[next]));
    setForm((prev) => {
      const nextDefaults = PROVIDER_DEFAULTS[next];
      if (!nextDefaults) return { ...prev, 'embedding.provider': next };
      return {
        ...prev,
        'embedding.provider': next,
        'embedding.model': nextDefaults.model,
        'embedding.base_url': nextDefaults.baseUrl,
      };
    });
  };

  const handleSave = async () => {
    // 방어적 재검증 — 저장 버튼은 오류가 있으면 비활성이지만, 키보드 등 다른 경로로도 막는다.
    if (hasValidationErrors) return;
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
          setProviderJustSwitched(false);
          toast.success('임베딩 설정이 저장되었습니다.');
        },
        // 서버 검증(provider/base_url/api_key 정합성) 실패 사유를 그대로 노출한다.
        // 고정 문구로 덮으면 400의 원인이 사용자에게 전달되지 않아 검증이 무의미해진다(#323).
        onError: (error) => handleApiError(error, '임베딩 설정 저장에 실패했습니다.'),
      },
    );
  };

  const handleReset = () => {
    setForm({ ...original });
    setProviderJustSwitched(false);
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
          {/* Provider — OLLAMA/OPENAI 선택 가능, VOYAGE는 미구현이라 disabled */}
          <div className="space-y-2">
            <Label htmlFor="embedding-provider">Provider</Label>
            <Select
              value={form['embedding.provider']}
              onValueChange={handleProviderChange}
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
            {/* provider 전환 시 모델/Base URL이 자동 교체됨을 알려 값이 사라진 것처럼 보이지 않게 한다 */}
            {providerJustSwitched && (
              <p className="text-sm text-amber-600 dark:text-amber-500" role="status">
                provider를 변경하여 모델과 Base URL이 새 provider 기본값으로 교체되었습니다. 필요하면
                직접 수정하세요.
              </p>
            )}
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
              aria-invalid={Boolean(errors.model)}
            />
            {errors.model ? (
              <p className="text-sm text-destructive">{errors.model}</p>
            ) : (
              <p className="text-sm text-muted-foreground">임베딩 모델 이름</p>
            )}
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
              aria-invalid={Boolean(errors.baseUrl)}
            />
            {errors.baseUrl ? (
              <p className="text-sm text-destructive">{errors.baseUrl}</p>
            ) : (
              <p className="text-sm text-muted-foreground">provider API 엔드포인트 주소</p>
            )}
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
                aria-invalid={Boolean(errors.apiKey)}
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
            {errors.apiKey ? (
              <p className="text-sm text-destructive">{errors.apiKey}</p>
            ) : (
              <p className="text-sm text-muted-foreground">
                Voyage/OpenAI 사용 시 필요. Ollama는 비워둡니다.
              </p>
            )}
          </div>

          <Separator />

          {/* 차원 안내 — 1024 고정값이며 설정 항목이 아니므로 읽기 전용 안내만 표시 */}
          <p className="text-sm text-muted-foreground">
            임베딩 차원은 1024로 고정됩니다. provider/모델 변경 시 기존 문서를 전체 재임베딩해야 합니다.
          </p>
        </CardContent>
      </Card>

      <div className="flex items-center gap-3">
        <Button
          onClick={handleSave}
          disabled={updateMutation.isPending || !hasChanges || hasValidationErrors}
        >
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
