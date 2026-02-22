import { useState } from 'react';
import {
  Globe,
  Plus,
  Trash2,
  Play,
  ChevronDown,
  ChevronRight,
  Key,
  Settings2,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Separator } from '@/components/ui/separator';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { client } from '@/api/client';
import { useApiConnections } from '@/hooks/queries/useApiConnections';
import ApiCallPreview from './ApiCallPreview';

interface FieldMapping {
  sourceField: string;
  targetColumn: string;
}

interface InlineAuth {
  authType: string;
  placement?: string;
  headerName?: string;
  paramName?: string;
  apiKey?: string;
  token?: string;
}

interface Pagination {
  type: 'NONE' | 'OFFSET';
  pageSize?: number;
  offsetParam?: string;
  limitParam?: string;
  totalPath?: string;
}

interface RetryConfig {
  maxRetries: number;
  initialBackoffMs: number;
  maxBackoffMs: number;
}

interface ApiCallStepConfigProps {
  apiConfig: Record<string, unknown>;
  apiConnectionId: number | null;
  onChange: (config: Record<string, unknown>) => void;
  onConnectionChange: (id: number | null) => void;
  readOnly: boolean;
}

interface PreviewResult {
  success: boolean;
  rawJson: string | null;
  rows: Array<Record<string, unknown>>;
  columns: string[];
  totalExtractedRows: number;
  errorMessage: string | null;
}

type KvPair = { key: string; value: string };

function getConfig<T>(apiConfig: Record<string, unknown>, key: string, defaultValue: T): T {
  return (apiConfig[key] as T) ?? defaultValue;
}

function recordToKvPairs(obj: Record<string, string>): KvPair[] {
  return Object.entries(obj).map(([key, value]) => ({ key, value }));
}

function kvPairsToRecord(pairs: KvPair[]): Record<string, string> {
  const result: Record<string, string> = {};
  for (const { key, value } of pairs) {
    if (key.trim()) result[key.trim()] = value;
  }
  return result;
}

interface SectionProps {
  title: string;
  icon?: React.ReactNode;
  defaultOpen?: boolean;
  children: React.ReactNode;
}

function Section({ title, icon, defaultOpen = false, children }: SectionProps) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger asChild>
        <button className="flex w-full items-center justify-between py-1.5 text-sm font-medium hover:text-foreground/80 transition-colors">
          <span className="flex items-center gap-1.5">
            {icon}
            {title}
          </span>
          {open ? (
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
          )}
        </button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="pb-2 space-y-2">{children}</div>
      </CollapsibleContent>
    </Collapsible>
  );
}

interface KvEditorProps {
  pairs: KvPair[];
  onChange: (pairs: KvPair[]) => void;
  readOnly: boolean;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
}

function KvEditor({
  pairs,
  onChange,
  readOnly,
  keyPlaceholder = '키',
  valuePlaceholder = '값',
}: KvEditorProps) {
  const handleAdd = () => onChange([...pairs, { key: '', value: '' }]);
  const handleRemove = (i: number) => onChange(pairs.filter((_, idx) => idx !== i));
  const handleChange = (i: number, field: 'key' | 'value', val: string) => {
    const next = pairs.map((p, idx) => (idx === i ? { ...p, [field]: val } : p));
    onChange(next);
  };

  return (
    <div className="space-y-1.5">
      {pairs.map((pair, i) => (
        <div key={i} className="flex gap-1.5 items-center">
          <Input
            className="h-7 text-xs"
            placeholder={keyPlaceholder}
            value={pair.key}
            disabled={readOnly}
            onChange={(e) => handleChange(i, 'key', e.target.value)}
          />
          <Input
            className="h-7 text-xs"
            placeholder={valuePlaceholder}
            value={pair.value}
            disabled={readOnly}
            onChange={(e) => handleChange(i, 'value', e.target.value)}
          />
          {!readOnly && (
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7 shrink-0"
              onClick={() => handleRemove(i)}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          )}
        </div>
      ))}
      {!readOnly && (
        <Button variant="outline" size="sm" className="h-7 text-xs" onClick={handleAdd}>
          <Plus className="h-3 w-3 mr-1" />
          추가
        </Button>
      )}
    </div>
  );
}

export default function ApiCallStepConfig({
  apiConfig,
  apiConnectionId,
  onChange,
  onConnectionChange,
  readOnly,
}: ApiCallStepConfigProps) {
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null);
  const { data: savedConnections } = useApiConnections();

  const authMode = apiConnectionId ? 'saved' : 'inline';

  const update = (key: string, value: unknown) => onChange({ ...apiConfig, [key]: value });

  const url = getConfig<string>(apiConfig, 'url', '');
  const method = getConfig<string>(apiConfig, 'method', 'GET');
  const dataPath = getConfig<string>(apiConfig, 'dataPath', '');
  const body = getConfig<string>(apiConfig, 'body', '');
  const inlineAuth = getConfig<InlineAuth>(apiConfig, 'inlineAuth', { authType: 'NONE' });
  const pagination = getConfig<Pagination>(apiConfig, 'pagination', { type: 'NONE' });
  const retry = getConfig<RetryConfig>(apiConfig, 'retry', {
    maxRetries: 3,
    initialBackoffMs: 1000,
    maxBackoffMs: 10000,
  });
  const timeoutMs = getConfig<number>(apiConfig, 'timeoutMs', 30000);
  const maxDurationMs = getConfig<number>(apiConfig, 'maxDurationMs', 3600000);
  const fieldMappings = getConfig<FieldMapping[]>(apiConfig, 'fieldMappings', []);
  const headersRecord = getConfig<Record<string, string>>(apiConfig, 'headers', {});
  const queryParamsRecord = getConfig<Record<string, string>>(apiConfig, 'queryParams', {});

  const [headerPairs, setHeaderPairs] = useState<KvPair[]>(() =>
    recordToKvPairs(headersRecord),
  );
  const [queryParamPairs, setQueryParamPairs] = useState<KvPair[]>(() =>
    recordToKvPairs(queryParamsRecord),
  );

  const handleHeadersChange = (pairs: KvPair[]) => {
    setHeaderPairs(pairs);
    update('headers', kvPairsToRecord(pairs));
  };

  const handleQueryParamsChange = (pairs: KvPair[]) => {
    setQueryParamPairs(pairs);
    update('queryParams', kvPairsToRecord(pairs));
  };

  const handleAuthChange = (partial: Partial<InlineAuth>) => {
    update('inlineAuth', { ...inlineAuth, ...partial });
  };

  const handlePaginationChange = (partial: Partial<Pagination>) => {
    update('pagination', { ...pagination, ...partial });
  };

  const handleRetryChange = (partial: Partial<RetryConfig>) => {
    update('retry', { ...retry, ...partial });
  };

  const handleMappingChange = (i: number, field: keyof FieldMapping, value: string) => {
    const next = fieldMappings.map((m, idx) => (idx === i ? { ...m, [field]: value } : m));
    update('fieldMappings', next);
  };

  const handleAddMapping = () => {
    update('fieldMappings', [
      ...fieldMappings,
      { sourceField: '', targetColumn: '' },
    ]);
  };

  const handleRemoveMapping = (i: number) => {
    update('fieldMappings', fieldMappings.filter((_, idx) => idx !== i));
  };

  const handlePreview = async () => {
    if (!url) {
      toast.error('URL을 입력하세요');
      return;
    }
    setPreviewLoading(true);
    setPreviewResult(null);
    try {
      const payload = {
        url,
        method,
        headers: kvPairsToRecord(headerPairs),
        queryParams: kvPairsToRecord(queryParamPairs),
        body: method === 'POST' ? body : undefined,
        dataPath,
        fieldMappings,
        inlineAuth: inlineAuth.authType !== 'NONE' ? inlineAuth : undefined,
        timeoutMs,
      };
      const { data } = await client.post<PreviewResult>(
        '/pipelines/api-call/preview',
        payload,
      );
      setPreviewResult(data);

      // Auto-fill field mappings from columns if mappings are empty
      if (data.success && data.columns.length > 0 && fieldMappings.every((m) => !m.sourceField)) {
        const autoMappings: FieldMapping[] = data.columns.map((col) => ({
          sourceField: col,
          targetColumn: col,
        }));
        update('fieldMappings', autoMappings);
      }
    } catch (err: unknown) {
      const message =
        (err as { response?: { data?: { message?: string } } })?.response?.data?.message ??
        '미리보기 호출에 실패했습니다';
      setPreviewResult({
        success: false,
        rawJson: null,
        rows: [],
        columns: [],
        totalExtractedRows: 0,
        errorMessage: message,
      });
    } finally {
      setPreviewLoading(false);
    }
  };

  return (
    <div className="space-y-3">
      {/* Basic Settings - always visible */}
      <div className="space-y-2">
        <div className="flex items-center gap-1.5 text-sm font-medium">
          <Globe className="h-3.5 w-3.5" />
          기본 설정
        </div>

        <div className="space-y-1.5">
          <Label className="text-xs">URL *</Label>
          <Input
            placeholder="https://api.example.com/v1/data"
            value={url}
            disabled={readOnly}
            onChange={(e) => update('url', e.target.value)}
            className="text-xs h-8"
          />
        </div>

        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1.5">
            <Label className="text-xs">HTTP 메서드</Label>
            <Select value={method} disabled={readOnly} onValueChange={(v) => update('method', v)}>
              <SelectTrigger className="h-8 text-xs w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="GET">GET</SelectItem>
                <SelectItem value="POST">POST</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1.5">
            <Label className="text-xs">데이터 경로</Label>
            <Input
              placeholder="$.data.items"
              value={dataPath}
              disabled={readOnly}
              onChange={(e) => update('dataPath', e.target.value)}
              className="text-xs h-8"
            />
          </div>
        </div>
        <p className="text-xs text-muted-foreground">
          JSONPath 형식으로 데이터 배열의 경로를 지정하세요
        </p>
      </div>

      <Separator />

      {/* Authentication */}
      <Section title="인증" icon={<Key className="h-3.5 w-3.5" />} defaultOpen={true}>
        <RadioGroup
          value={authMode}
          onValueChange={(v) => {
            if (v === 'saved') {
              handleAuthChange({ authType: 'NONE' });
            } else {
              onConnectionChange(null);
            }
          }}
          disabled={readOnly}
          className="space-y-3"
        >
          {/* Saved connection */}
          <div className="flex items-start gap-2">
            <RadioGroupItem value="saved" id="auth-saved" className="mt-0.5" />
            <div className="flex-1 space-y-1.5">
              <Label htmlFor="auth-saved" className="text-xs font-medium">저장된 연결 사용</Label>
              {authMode === 'saved' && (
                <Select
                  value={apiConnectionId ? String(apiConnectionId) : ''}
                  disabled={readOnly}
                  onValueChange={(v) => onConnectionChange(Number(v))}
                >
                  <SelectTrigger className="h-8 text-xs w-full">
                    <SelectValue placeholder="연결을 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {savedConnections?.map((conn) => (
                      <SelectItem key={conn.id} value={String(conn.id)}>
                        {conn.name} ({conn.authType === 'BEARER' ? 'Bearer' : 'API Key'})
                      </SelectItem>
                    ))}
                    {(!savedConnections || savedConnections.length === 0) && (
                      <div className="px-2 py-1.5 text-xs text-muted-foreground">
                        저장된 연결이 없습니다. 관리 &gt; API 연결에서 추가하세요.
                      </div>
                    )}
                  </SelectContent>
                </Select>
              )}
            </div>
          </div>

          {/* Inline auth */}
          <div className="flex items-start gap-2">
            <RadioGroupItem value="inline" id="auth-inline" className="mt-0.5" />
            <div className="flex-1 space-y-1.5">
              <Label htmlFor="auth-inline" className="text-xs font-medium">직접 입력</Label>
              {authMode === 'inline' && (
                <div className="space-y-2">
                  <Select
                    value={inlineAuth.authType}
                    disabled={readOnly}
                    onValueChange={(v) => {
                      const patch: Partial<InlineAuth> = { authType: v };
                      if (v === 'API_KEY' && !inlineAuth.placement) {
                        patch.placement = 'header';
                      }
                      handleAuthChange(patch);
                    }}
                  >
                    <SelectTrigger className="h-8 text-xs w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="NONE">없음</SelectItem>
                      <SelectItem value="API_KEY">API Key</SelectItem>
                      <SelectItem value="BEARER">Bearer Token</SelectItem>
                    </SelectContent>
                  </Select>

                  {inlineAuth.authType === 'API_KEY' && (
                    <>
                      <Select
                        value={inlineAuth.placement ?? 'header'}
                        disabled={readOnly}
                        onValueChange={(v) => handleAuthChange({ placement: v })}
                      >
                        <SelectTrigger className="h-8 text-xs w-full">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="header">Header</SelectItem>
                          <SelectItem value="query">Query Parameter</SelectItem>
                        </SelectContent>
                      </Select>
                      <Input
                        className="h-7 text-xs"
                        placeholder={inlineAuth.placement === 'query' ? 'api_key' : 'X-API-Key'}
                        value={
                          inlineAuth.placement === 'query'
                            ? (inlineAuth.paramName ?? '')
                            : (inlineAuth.headerName ?? '')
                        }
                        disabled={readOnly}
                        onChange={(e) =>
                          inlineAuth.placement === 'query'
                            ? handleAuthChange({ paramName: e.target.value })
                            : handleAuthChange({ headerName: e.target.value })
                        }
                      />
                      <Input
                        className="h-7 text-xs"
                        type="password"
                        placeholder="API 키를 입력하세요"
                        value={inlineAuth.apiKey ?? ''}
                        disabled={readOnly}
                        onChange={(e) => handleAuthChange({ apiKey: e.target.value })}
                      />
                    </>
                  )}

                  {inlineAuth.authType === 'BEARER' && (
                    <Input
                      className="h-7 text-xs"
                      type="password"
                      placeholder="토큰을 입력하세요"
                      value={inlineAuth.token ?? ''}
                      disabled={readOnly}
                      onChange={(e) => handleAuthChange({ token: e.target.value })}
                    />
                  )}
                </div>
              )}
            </div>
          </div>
        </RadioGroup>
      </Section>

      <Separator />

      {/* Headers */}
      <Section title="헤더">
        <KvEditor
          pairs={headerPairs}
          onChange={handleHeadersChange}
          readOnly={readOnly}
          keyPlaceholder="Content-Type"
          valuePlaceholder="application/json"
        />
      </Section>

      <Separator />

      {/* Query Parameters */}
      <Section title="쿼리 파라미터">
        <KvEditor
          pairs={queryParamPairs}
          onChange={handleQueryParamsChange}
          readOnly={readOnly}
          keyPlaceholder="파라미터명"
          valuePlaceholder="값"
        />
      </Section>

      <Separator />

      {/* Request Body - POST only */}
      {method === 'POST' && (
        <>
          <Section title="요청 바디">
            <Textarea
              className="text-xs font-mono"
              rows={4}
              placeholder='{"key": "value"}'
              value={body}
              disabled={readOnly}
              onChange={(e) => update('body', e.target.value)}
            />
          </Section>
          <Separator />
        </>
      )}

      {/* Field Mappings */}
      <Section title="필드 매핑" defaultOpen={true}>
        <p className="text-xs text-muted-foreground">
          미리보기를 실행하면 소스 필드가 자동으로 채워집니다
        </p>
        <div className="space-y-1.5">
          {fieldMappings.length > 0 && (
            <div className="grid grid-cols-[1fr_1fr_auto] gap-1 text-xs text-muted-foreground px-0.5">
              <span>소스 필드</span>
              <span>대상 컬럼</span>
              <span></span>
            </div>
          )}
          {fieldMappings.map((mapping, i) => (
            <div key={i} className="grid grid-cols-[1fr_1fr_auto] gap-1 items-center">
              <Input
                className="h-7 text-xs"
                placeholder="sourceField"
                value={mapping.sourceField}
                disabled={readOnly}
                onChange={(e) => handleMappingChange(i, 'sourceField', e.target.value)}
              />
              <Input
                className="h-7 text-xs"
                placeholder="target_col"
                value={mapping.targetColumn}
                disabled={readOnly}
                onChange={(e) => handleMappingChange(i, 'targetColumn', e.target.value)}
              />
              {!readOnly && (
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-7 w-7 shrink-0"
                  onClick={() => handleRemoveMapping(i)}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              )}
            </div>
          ))}
          {!readOnly && (
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              onClick={handleAddMapping}
            >
              <Plus className="h-3 w-3 mr-1" />
              매핑 추가
            </Button>
          )}
        </div>
      </Section>

      <Separator />

      {/* Pagination */}
      <Section title="페이지네이션">
        <div className="space-y-1.5">
          <div className="flex items-center gap-2">
            <div className="flex-1 space-y-1">
              <Label className="text-xs">유형</Label>
              <Select
                value={pagination.type}
                disabled={readOnly}
                onValueChange={(v) =>
                  handlePaginationChange({ type: v as 'NONE' | 'OFFSET' })
                }
              >
                <SelectTrigger className="h-8 text-xs w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="NONE">없음</SelectItem>
                  <SelectItem value="OFFSET">Offset</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {pagination.type === 'OFFSET' && !readOnly && (
              <div className="flex-1 space-y-1">
                <Label className="text-xs">프리셋</Label>
                <Select
                  value=""
                  onValueChange={(v) => {
                    if (v === 'public') {
                      handlePaginationChange({ offsetParam: 'page', limitParam: 'perPage' });
                    } else if (v === 'standard') {
                      handlePaginationChange({ offsetParam: 'offset', limitParam: 'limit' });
                    }
                  }}
                >
                  <SelectTrigger className="h-8 text-xs w-full">
                    <SelectValue placeholder="프리셋 선택" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="public">공공데이터포털 (page/perPage)</SelectItem>
                    <SelectItem value="standard">표준 REST (offset/limit)</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            )}
          </div>

          {pagination.type === 'OFFSET' && (
            <div className="space-y-1.5">
              <div className="grid grid-cols-2 gap-1.5">
                <div className="space-y-1">
                  <Label className="text-xs">페이지 크기</Label>
                  <Input
                    className="h-7 text-xs"
                    type="number"
                    value={pagination.pageSize ?? 100}
                    disabled={readOnly}
                    onChange={(e) =>
                      handlePaginationChange({ pageSize: Number(e.target.value) })
                    }
                  />
                </div>
                <div className="space-y-1">
                  <Label className="text-xs">총 건수 경로</Label>
                  <Input
                    className="h-7 text-xs"
                    placeholder="$.meta.totalCount"
                    value={pagination.totalPath ?? ''}
                    disabled={readOnly}
                    onChange={(e) => handlePaginationChange({ totalPath: e.target.value })}
                  />
                </div>
                <div className="space-y-1">
                  <Label className="text-xs">Offset 파라미터</Label>
                  <Input
                    className="h-7 text-xs"
                    placeholder="offset"
                    value={pagination.offsetParam ?? 'offset'}
                    disabled={readOnly}
                    onChange={(e) => handlePaginationChange({ offsetParam: e.target.value })}
                  />
                </div>
                <div className="space-y-1">
                  <Label className="text-xs">Limit 파라미터</Label>
                  <Input
                    className="h-7 text-xs"
                    placeholder="limit"
                    value={pagination.limitParam ?? 'limit'}
                    disabled={readOnly}
                    onChange={(e) => handlePaginationChange({ limitParam: e.target.value })}
                  />
                </div>
              </div>
            </div>
          )}
        </div>
      </Section>

      <Separator />

      {/* Advanced Settings */}
      <Section title="고급 설정" icon={<Settings2 className="h-3.5 w-3.5" />}>
        <div className="grid grid-cols-2 gap-1.5">
          <div className="space-y-1">
            <Label className="text-xs">재시도 횟수</Label>
            <Input
              className="h-7 text-xs"
              type="number"
              value={retry.maxRetries}
              disabled={readOnly}
              onChange={(e) => handleRetryChange({ maxRetries: Number(e.target.value) })}
            />
          </div>
          <div className="space-y-1">
            <Label className="text-xs">요청 타임아웃 (ms)</Label>
            <Input
              className="h-7 text-xs"
              type="number"
              value={timeoutMs}
              disabled={readOnly}
              onChange={(e) => update('timeoutMs', Number(e.target.value))}
            />
          </div>
          <div className="space-y-1 col-span-2">
            <Label className="text-xs">최대 실행 시간 (ms)</Label>
            <Input
              className="h-7 text-xs"
              type="number"
              value={maxDurationMs}
              disabled={readOnly}
              onChange={(e) => update('maxDurationMs', Number(e.target.value))}
            />
          </div>
        </div>
      </Section>

      <Separator />

      {/* Preview */}
      <div className="pb-2">
        <Button
          className="w-full"
          variant="secondary"
          disabled={readOnly || previewLoading}
          onClick={handlePreview}
        >
          <Play className="h-4 w-4 mr-2" />
          {previewLoading ? '호출 중...' : '테스트 호출'}
        </Button>

        {previewResult && <ApiCallPreview result={previewResult} />}
      </div>
    </div>
  );
}
