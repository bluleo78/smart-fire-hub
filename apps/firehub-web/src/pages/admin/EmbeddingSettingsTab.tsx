import { Boxes, RefreshCw } from 'lucide-react';
import { useEffect, useState } from 'react';

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
import { useEmbeddingSettings } from '../../hooks/queries/useEmbeddingSettings';
import { indexSettingsByKey, resolveSettingFieldState } from '../../lib/settings-fields';
import { PlatformLockedBanner, PlatformLockedNote, SettingFieldLabel } from './settings-lock';

interface EmbeddingForm {
  'embedding.provider': string;
  'embedding.model': string;
  'embedding.base_url': string;
  'embedding.api_key': string;
}

// 폼 초기값은 비워 둔다 — 값은 서버 응답만이 채운다. 하드코딩 기본값을 시드하면 서버에 행이
// 없을 때도 실제 적용값처럼 보인다(읽기 전용 화면에서는 특히 오해가 크다).
const EMPTY: EmbeddingForm = {
  'embedding.provider': '',
  'embedding.model': '',
  'embedding.base_url': '',
  'embedding.api_key': '',
};

// provider 옵션 — 읽기 전용이 된 뒤에도 서버 값(코드)을 사람이 읽는 라벨로 보여주기 위해 유지한다.
const PROVIDER_OPTIONS: { value: string; label: string }[] = [
  { value: 'OLLAMA', label: 'Ollama' },
  { value: 'OPENAI', label: 'OpenAI' },
  { value: 'VOYAGE', label: 'Voyage (준비 중)' },
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
 * 임베딩 설정 탭 — P7-b 이후 **provider 폼 전 필드가 플랫폼 전용(읽기 전용)**.
 *
 * `embedding.*` 4키는 모델이 바뀌면 벡터 차원이 바뀌어 **기존 임베딩 전량이 무효화**되므로
 * 플랫폼이 소유한다. 백엔드가 이 키들의 저장을 거부하기 때문에 저장/되돌리기 버튼과 클라이언트
 * 검증(서버 규칙 미러링)을 함께 제거했다 — 값을 바꿀 수 없으면 검증할 대상도 없다.
 *
 * 반면 아래 **재임베딩 카드는 그대로 유지**한다. 현재 설정으로 다시 임베딩하는 운영 액션이지
 * 설정 변경이 아니다.
 */
export default function EmbeddingSettingsTab() {
  const { data: settings, isLoading } = useEmbeddingSettings();

  // 재임베딩 카드용 — 현황 폴링 조회 및 전체 재임베딩 실행 mutation
  const { data: status } = useEmbeddingStatus();
  const reindex = useReindexAllEmbeddings();

  const [form, setForm] = useState<EmbeddingForm>(EMPTY);

  // 서버에서 settings가 로드되면 폼 상태에 반영 — 서버 데이터 → 폼 state 초기화 패턴
  useEffect(() => {
    if (!settings) return;
    const values = { ...EMPTY };
    settings.forEach((s) => {
      const key = s.key as keyof EmbeddingForm;
      if (key in values) values[key] = s.value ?? '';
    });
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setForm(values);
  }, [settings]);

  // 잠금 표시의 근거는 서버가 내려주는 tenantEditable 플래그다 — 화면이 정책 사본을 들고 있지 않다.
  const byKey = indexSettingsByKey(settings ?? []);
  const fieldState = (key: keyof EmbeddingForm) => resolveSettingFieldState(key, byKey[key]);

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  return (
    <div className="space-y-6">
      {/* 탭 상단 배너 — 스크롤하지 않아도 편집 불가를 먼저 알린다 */}
      <PlatformLockedBanner>
        임베딩 설정은 플랫폼 운영자가 관리합니다. 이 화면에서는 현재 적용된 값을 확인할 수만 있고,
        테넌트에서 변경할 수 없습니다.
      </PlatformLockedBanner>

      <Card className="card-hover">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Boxes className="h-4 w-4" />
            임베딩 provider 설정
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* Provider — 읽기 전용. Select 를 유지하는 이유는 저장된 코드값(OLLAMA)을 사람이 읽는
              라벨(Ollama)로 보여주기 위해서다. */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="embedding-provider" state={fieldState('embedding.provider')}>
              Provider
            </SettingFieldLabel>
            <Select value={form['embedding.provider']} disabled>
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
            <p className="text-sm text-muted-foreground">임베딩 생성에 사용할 provider</p>
            <PlatformLockedNote />
          </div>

          <Separator />

          {/* Model */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="embedding-model" state={fieldState('embedding.model')}>
              모델
            </SettingFieldLabel>
            <Input
              id="embedding-model"
              className="max-w-md"
              value={form['embedding.model']}
              disabled
              placeholder="bge-m3"
            />
            <p className="text-sm text-muted-foreground">임베딩 모델 이름</p>
            <PlatformLockedNote />
          </div>

          <Separator />

          {/* Base URL */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="embedding-base-url" state={fieldState('embedding.base_url')}>
              Base URL
            </SettingFieldLabel>
            <Input
              id="embedding-base-url"
              className="max-w-md"
              value={form['embedding.base_url']}
              disabled
              placeholder="http://host.docker.internal:11434"
            />
            <p className="text-sm text-muted-foreground">provider API 엔드포인트 주소</p>
            <PlatformLockedNote />
          </div>

          <Separator />

          {/* API Key — 서버에서 **** 로 마스킹되어 내려오고 편집도 불가하므로 표시/숨기기 토글을 두지 않는다 */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="embedding-api-key" state={fieldState('embedding.api_key')}>
              API 키
            </SettingFieldLabel>
            <Input
              id="embedding-api-key"
              type="password"
              className="max-w-md"
              value={form['embedding.api_key']}
              disabled
              placeholder="provider API 키 (Ollama는 불필요)"
            />
            <p className="text-sm text-muted-foreground">
              Voyage/OpenAI 사용 시 필요. Ollama는 비워둡니다.
            </p>
            <PlatformLockedNote />
          </div>

          <Separator />

          {/* 차원 안내 — 1024 고정값이며 설정 항목이 아니므로 읽기 전용 안내만 표시 */}
          <p className="text-sm text-muted-foreground">
            임베딩 차원은 1024로 고정됩니다. provider/모델 변경 시 기존 문서를 전체 재임베딩해야 합니다.
          </p>
        </CardContent>
      </Card>

      {/* 원래 저장/되돌리기 버튼 행이 있던 자리 — 배너로 대체한다 */}
      <PlatformLockedBanner>
        임베딩 설정은 플랫폼 운영자가 관리합니다. 이 화면에서는 현재 적용된 값을 확인할 수만 있고,
        테넌트에서 변경할 수 없습니다.
      </PlatformLockedBanner>

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
