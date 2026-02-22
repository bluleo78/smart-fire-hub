import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { Play, Plus, Trash2, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { client } from '@/api/client';
import { useCreateApiImport } from '@/hooks/queries/useDatasets';
import type { DatasetColumnResponse } from '@/types/dataset';
import ApiCallPreview from '../../pipeline/components/ApiCallPreview';

interface ApiImportWizardProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  datasetId: number;
  datasetName: string;
  datasetColumns: DatasetColumnResponse[];
}

interface InlineAuth {
  authType: string;
  placement?: string;
  headerName?: string;
  paramName?: string;
  apiKey?: string;
  token?: string;
}

interface KvPair {
  key: string;
  value: string;
}

interface FieldMapping {
  sourceField: string;
  targetColumn: string;
  dataType: string;
}

interface PreviewResult {
  success: boolean;
  rawJson: string | null;
  rows: Array<Record<string, unknown>>;
  columns: string[];
  totalExtractedRows: number;
  errorMessage: string | null;
}

function kvPairsToRecord(pairs: KvPair[]): Record<string, string> {
  const result: Record<string, string> = {};
  for (const { key, value } of pairs) {
    if (key.trim()) result[key.trim()] = value;
  }
  return result;
}

const STEPS = ['API 설정', '응답 매핑', '미리보기', '실행 옵션'];

export function ApiImportWizard({
  open,
  onOpenChange,
  datasetId,
  datasetName,
  datasetColumns,
}: ApiImportWizardProps) {
  const navigate = useNavigate();
  const createApiImport = useCreateApiImport(datasetId);

  const [step, setStep] = useState(0);

  // Step 1 state
  const [url, setUrl] = useState('');
  const [method, setMethod] = useState('GET');
  const [authType, setAuthType] = useState('NONE');
  const [authPlacement, setAuthPlacement] = useState('header');
  const [authHeaderName, setAuthHeaderName] = useState('');
  const [authParamName, setAuthParamName] = useState('');
  const [authApiKey, setAuthApiKey] = useState('');
  const [authToken, setAuthToken] = useState('');
  const [headers, setHeaders] = useState<KvPair[]>([]);
  const [queryParams, setQueryParams] = useState<KvPair[]>([]);
  const [body, setBody] = useState('');
  const [dataPath, setDataPath] = useState('');

  // Step 2 state
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null);
  const [fieldMappings, setFieldMappings] = useState<FieldMapping[]>([]);

  // Step 4 state
  const [pipelineName, setPipelineName] = useState(`${datasetName} API Import`);
  const [loadStrategy, setLoadStrategy] = useState('REPLACE');
  const [executeImmediately, setExecuteImmediately] = useState(true);
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [cronExpression, setCronExpression] = useState('0 0 * * *');
  const [scheduleName, setScheduleName] = useState('');

  const buildApiConfig = (): Record<string, unknown> => {
    const inlineAuth: InlineAuth = { authType };
    if (authType === 'API_KEY') {
      inlineAuth.placement = authPlacement;
      if (authPlacement === 'query') inlineAuth.paramName = authParamName;
      else inlineAuth.headerName = authHeaderName;
      inlineAuth.apiKey = authApiKey;
    } else if (authType === 'BEARER') {
      inlineAuth.token = authToken;
    }

    return {
      url,
      method,
      headers: kvPairsToRecord(headers),
      queryParams: kvPairsToRecord(queryParams),
      body: method === 'POST' ? body : undefined,
      dataPath,
      fieldMappings,
      inlineAuth: authType !== 'NONE' ? inlineAuth : undefined,
    };
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
        headers: kvPairsToRecord(headers),
        queryParams: kvPairsToRecord(queryParams),
        body: method === 'POST' ? body : undefined,
        dataPath,
        fieldMappings,
        inlineAuth:
          authType !== 'NONE'
            ? {
                authType,
                placement: authPlacement,
                headerName: authHeaderName,
                paramName: authParamName,
                apiKey: authApiKey,
                token: authToken,
              }
            : undefined,
        timeoutMs: 30000,
      };
      const { data } = await client.post<PreviewResult>('/pipelines/api-call/preview', payload);
      setPreviewResult(data);

      if (data.success && data.columns.length > 0 && fieldMappings.every((m) => !m.sourceField)) {
        const autoMappings: FieldMapping[] = data.columns.map((col) => ({
          sourceField: col,
          targetColumn: col,
          dataType: 'TEXT',
        }));
        setFieldMappings(autoMappings);
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

  const handleComplete = () => {
    if (!url) {
      toast.error('URL을 입력하세요');
      setStep(0);
      return;
    }

    createApiImport.mutate(
      {
        pipelineName: pipelineName || `${datasetName} API Import`,
        apiConfig: buildApiConfig(),
        loadStrategy,
        executeImmediately,
        schedule: scheduleEnabled
          ? { cronExpression, name: scheduleName || undefined }
          : null,
      },
      {
        onSuccess: (data) => {
          toast.success('API 임포트 파이프라인이 생성되었습니다.');
          onOpenChange(false);
          navigate(`/pipelines/${data.data.pipelineId}`);
        },
        onError: () => {
          toast.error('API 임포트 생성에 실패했습니다.');
        },
      },
    );
  };

  const handleNext = () => {
    if (step === 0 && !url) {
      toast.error('URL을 입력하세요');
      return;
    }
    setStep((s) => s + 1);
  };

  const handleBack = () => setStep((s) => s - 1);

  const addKv = (setter: React.Dispatch<React.SetStateAction<KvPair[]>>) =>
    setter((prev) => [...prev, { key: '', value: '' }]);

  const removeKv = (setter: React.Dispatch<React.SetStateAction<KvPair[]>>, i: number) =>
    setter((prev) => prev.filter((_, idx) => idx !== i));

  const updateKv = (
    setter: React.Dispatch<React.SetStateAction<KvPair[]>>,
    i: number,
    field: 'key' | 'value',
    val: string,
  ) => setter((prev) => prev.map((p, idx) => (idx === i ? { ...p, [field]: val } : p)));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>API 가져오기 — {datasetName}</DialogTitle>
        </DialogHeader>

        {/* Step indicator */}
        <div className="flex items-center gap-1 mb-4">
          {STEPS.map((label, i) => (
            <div key={i} className="flex items-center gap-1">
              <div
                className={`flex items-center justify-center w-6 h-6 rounded-full text-xs font-medium ${
                  i === step
                    ? 'bg-primary text-primary-foreground'
                    : i < step
                    ? 'bg-primary/30 text-primary'
                    : 'bg-muted text-muted-foreground'
                }`}
              >
                {i + 1}
              </div>
              <span
                className={`text-xs ${i === step ? 'font-medium' : 'text-muted-foreground'}`}
              >
                {label}
              </span>
              {i < STEPS.length - 1 && (
                <div className="w-4 h-px bg-border mx-1" />
              )}
            </div>
          ))}
        </div>

        {/* Step 1: API 설정 */}
        {step === 0 && (
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label>URL *</Label>
              <Input
                placeholder="https://api.example.com/v1/data"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
              />
            </div>

            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>HTTP 메서드</Label>
                <Select value={method} onValueChange={setMethod}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="GET">GET</SelectItem>
                    <SelectItem value="POST">POST</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <Label>데이터 경로 (JSONPath)</Label>
                <Input
                  placeholder="$.data.items"
                  value={dataPath}
                  onChange={(e) => setDataPath(e.target.value)}
                />
              </div>
            </div>

            {/* Auth */}
            <div className="space-y-2 border rounded-md p-3">
              <Label className="text-sm font-medium">인증</Label>
              <Select value={authType} onValueChange={setAuthType}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="NONE">없음</SelectItem>
                  <SelectItem value="API_KEY">API Key</SelectItem>
                  <SelectItem value="BEARER">Bearer Token</SelectItem>
                </SelectContent>
              </Select>

              {authType === 'API_KEY' && (
                <div className="space-y-2">
                  <Select value={authPlacement} onValueChange={setAuthPlacement}>
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="header">Header</SelectItem>
                      <SelectItem value="query">Query Parameter</SelectItem>
                    </SelectContent>
                  </Select>
                  <Input
                    placeholder={authPlacement === 'query' ? 'api_key' : 'X-API-Key'}
                    value={authPlacement === 'query' ? authParamName : authHeaderName}
                    onChange={(e) =>
                      authPlacement === 'query'
                        ? setAuthParamName(e.target.value)
                        : setAuthHeaderName(e.target.value)
                    }
                  />
                  <Input
                    type="password"
                    placeholder="API 키 값"
                    value={authApiKey}
                    onChange={(e) => setAuthApiKey(e.target.value)}
                  />
                </div>
              )}

              {authType === 'BEARER' && (
                <Input
                  type="password"
                  placeholder="Bearer 토큰"
                  value={authToken}
                  onChange={(e) => setAuthToken(e.target.value)}
                />
              )}
            </div>

            {/* Headers */}
            <div className="space-y-2">
              <Label className="text-sm font-medium">헤더</Label>
              {headers.map((pair, i) => (
                <div key={i} className="flex gap-2 items-center">
                  <Input
                    className="h-8 text-xs"
                    placeholder="키"
                    value={pair.key}
                    onChange={(e) => updateKv(setHeaders, i, 'key', e.target.value)}
                  />
                  <Input
                    className="h-8 text-xs"
                    placeholder="값"
                    value={pair.value}
                    onChange={(e) => updateKv(setHeaders, i, 'value', e.target.value)}
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 shrink-0"
                    onClick={() => removeKv(setHeaders, i)}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </div>
              ))}
              <Button variant="outline" size="sm" className="h-8 text-xs" onClick={() => addKv(setHeaders)}>
                <Plus className="h-3 w-3 mr-1" />
                헤더 추가
              </Button>
            </div>

            {/* Query Params */}
            <div className="space-y-2">
              <Label className="text-sm font-medium">쿼리 파라미터</Label>
              {queryParams.map((pair, i) => (
                <div key={i} className="flex gap-2 items-center">
                  <Input
                    className="h-8 text-xs"
                    placeholder="파라미터명"
                    value={pair.key}
                    onChange={(e) => updateKv(setQueryParams, i, 'key', e.target.value)}
                  />
                  <Input
                    className="h-8 text-xs"
                    placeholder="값"
                    value={pair.value}
                    onChange={(e) => updateKv(setQueryParams, i, 'value', e.target.value)}
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 shrink-0"
                    onClick={() => removeKv(setQueryParams, i)}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </div>
              ))}
              <Button variant="outline" size="sm" className="h-8 text-xs" onClick={() => addKv(setQueryParams)}>
                <Plus className="h-3 w-3 mr-1" />
                파라미터 추가
              </Button>
            </div>

            {/* Body (POST only) */}
            {method === 'POST' && (
              <div className="space-y-1.5">
                <Label>요청 바디 (JSON)</Label>
                <Textarea
                  className="text-xs font-mono"
                  rows={4}
                  placeholder='{"key": "value"}'
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                />
              </div>
            )}
          </div>
        )}

        {/* Step 2: 응답 매핑 */}
        {step === 1 && (
          <div className="space-y-4">
            <Button
              variant="secondary"
              className="w-full"
              disabled={previewLoading}
              onClick={handlePreview}
            >
              {previewLoading ? (
                <Loader2 className="h-4 w-4 mr-2 animate-spin" />
              ) : (
                <Play className="h-4 w-4 mr-2" />
              )}
              {previewLoading ? '호출 중...' : '테스트 호출'}
            </Button>

            {previewResult && <ApiCallPreview result={previewResult} />}

            {/* Field Mappings */}
            <div className="space-y-2">
              <Label className="text-sm font-medium">필드 매핑</Label>
              <p className="text-xs text-muted-foreground">
                테스트 호출 후 자동으로 채워집니다. 직접 수정할 수도 있습니다.
              </p>

              {fieldMappings.length > 0 && (
                <div className="grid grid-cols-[1fr_1fr_auto_auto] gap-1 text-xs text-muted-foreground px-0.5">
                  <span>소스 필드</span>
                  <span>대상 컬럼</span>
                  <span>타입</span>
                  <span />
                </div>
              )}

              {fieldMappings.map((mapping, i) => (
                <div key={i} className="grid grid-cols-[1fr_1fr_auto_auto] gap-1 items-center">
                  <Input
                    className="h-7 text-xs"
                    placeholder="sourceField"
                    value={mapping.sourceField}
                    onChange={(e) =>
                      setFieldMappings((prev) =>
                        prev.map((m, idx) =>
                          idx === i ? { ...m, sourceField: e.target.value } : m,
                        ),
                      )
                    }
                  />
                  <Select
                    value={
                      datasetColumns.find((c) => c.columnName === mapping.targetColumn)
                        ? mapping.targetColumn
                        : '__custom__'
                    }
                    onValueChange={(v) =>
                      setFieldMappings((prev) =>
                        prev.map((m, idx) =>
                          idx === i ? { ...m, targetColumn: v === '__custom__' ? '' : v } : m,
                        ),
                      )
                    }
                  >
                    <SelectTrigger className="h-7 text-xs">
                      <SelectValue placeholder="컬럼 선택" />
                    </SelectTrigger>
                    <SelectContent>
                      {datasetColumns.map((col) => (
                        <SelectItem key={col.id} value={col.columnName}>
                          {col.columnName}
                        </SelectItem>
                      ))}
                      <SelectItem value="__custom__">직접 입력</SelectItem>
                    </SelectContent>
                  </Select>
                  <Select
                    value={mapping.dataType}
                    onValueChange={(v) =>
                      setFieldMappings((prev) =>
                        prev.map((m, idx) => (idx === i ? { ...m, dataType: v } : m)),
                      )
                    }
                  >
                    <SelectTrigger className="h-7 text-xs w-24">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="TEXT">TEXT</SelectItem>
                      <SelectItem value="INTEGER">INTEGER</SelectItem>
                      <SelectItem value="DECIMAL">DECIMAL</SelectItem>
                      <SelectItem value="BOOLEAN">BOOLEAN</SelectItem>
                      <SelectItem value="DATE">DATE</SelectItem>
                      <SelectItem value="TIMESTAMP">TIMESTAMP</SelectItem>
                    </SelectContent>
                  </Select>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7 shrink-0"
                    onClick={() =>
                      setFieldMappings((prev) => prev.filter((_, idx) => idx !== i))
                    }
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </div>
              ))}

              <Button
                variant="outline"
                size="sm"
                className="h-7 text-xs"
                onClick={() =>
                  setFieldMappings((prev) => [
                    ...prev,
                    { sourceField: '', targetColumn: '', dataType: 'TEXT' },
                  ])
                }
              >
                <Plus className="h-3 w-3 mr-1" />
                매핑 추가
              </Button>
            </div>
          </div>
        )}

        {/* Step 3: 미리보기 */}
        {step === 2 && (
          <div className="space-y-4">
            <div className="rounded-md border p-4 space-y-3">
              <h3 className="text-sm font-medium">설정 요약</h3>
              <div className="space-y-1.5 text-sm">
                <div className="flex gap-2">
                  <span className="text-muted-foreground w-24 shrink-0">URL</span>
                  <span className="font-mono text-xs break-all">{url}</span>
                </div>
                <div className="flex gap-2">
                  <span className="text-muted-foreground w-24 shrink-0">메서드</span>
                  <span>{method}</span>
                </div>
                {dataPath && (
                  <div className="flex gap-2">
                    <span className="text-muted-foreground w-24 shrink-0">데이터 경로</span>
                    <span className="font-mono text-xs">{dataPath}</span>
                  </div>
                )}
                <div className="flex gap-2">
                  <span className="text-muted-foreground w-24 shrink-0">인증</span>
                  <span>{authType === 'NONE' ? '없음' : authType === 'API_KEY' ? 'API Key' : 'Bearer Token'}</span>
                </div>
                <div className="flex gap-2">
                  <span className="text-muted-foreground w-24 shrink-0">필드 매핑</span>
                  <span>{fieldMappings.length}개</span>
                </div>
              </div>
            </div>

            {previewResult ? (
              <ApiCallPreview result={previewResult} />
            ) : (
              <div className="text-center py-6">
                <p className="text-sm text-muted-foreground">
                  이전 단계에서 테스트 호출을 실행하면 미리보기 결과가 표시됩니다.
                </p>
                <Button
                  variant="secondary"
                  size="sm"
                  className="mt-3"
                  disabled={previewLoading}
                  onClick={handlePreview}
                >
                  {previewLoading ? (
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  ) : (
                    <Play className="h-4 w-4 mr-2" />
                  )}
                  테스트 호출
                </Button>
              </div>
            )}
          </div>
        )}

        {/* Step 4: 실행 옵션 */}
        {step === 3 && (
          <div className="space-y-4">
            <div className="space-y-1.5">
              <Label>파이프라인 이름</Label>
              <Input
                value={pipelineName}
                onChange={(e) => setPipelineName(e.target.value)}
                placeholder={`${datasetName} API Import`}
              />
            </div>

            <div className="space-y-1.5">
              <Label>적재 전략</Label>
              <Select value={loadStrategy} onValueChange={setLoadStrategy}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="REPLACE">교체 (REPLACE) — 기존 데이터 삭제 후 삽입</SelectItem>
                  <SelectItem value="APPEND">추가 (APPEND) — 기존 데이터에 추가</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="flex items-center gap-2">
              <Checkbox
                id="executeImmediately"
                checked={executeImmediately}
                onCheckedChange={(v) => setExecuteImmediately(!!v)}
              />
              <Label htmlFor="executeImmediately" className="cursor-pointer">
                생성 후 즉시 실행
              </Label>
            </div>

            <div className="border rounded-md p-3 space-y-3">
              <div className="flex items-center gap-2">
                <Checkbox
                  id="scheduleEnabled"
                  checked={scheduleEnabled}
                  onCheckedChange={(v) => setScheduleEnabled(!!v)}
                />
                <Label htmlFor="scheduleEnabled" className="cursor-pointer">
                  스케줄 설정 (선택)
                </Label>
              </div>

              {scheduleEnabled && (
                <div className="space-y-2 pt-1">
                  <div className="space-y-1.5">
                    <Label className="text-xs">Cron 표현식</Label>
                    <Input
                      placeholder="0 0 * * * (매일 자정)"
                      value={cronExpression}
                      onChange={(e) => setCronExpression(e.target.value)}
                      className="font-mono text-sm"
                    />
                    <p className="text-xs text-muted-foreground">
                      예: <code>0 9 * * 1-5</code> — 평일 오전 9시
                    </p>
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">스케줄 이름 (선택)</Label>
                    <Input
                      placeholder="매일 API 동기화"
                      value={scheduleName}
                      onChange={(e) => setScheduleName(e.target.value)}
                    />
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        <DialogFooter className="flex justify-between sm:justify-between">
          <Button
            variant="outline"
            onClick={step === 0 ? () => onOpenChange(false) : handleBack}
            disabled={createApiImport.isPending}
          >
            {step === 0 ? '취소' : '이전'}
          </Button>
          <div className="flex gap-2">
            {step < STEPS.length - 1 ? (
              <Button onClick={handleNext}>다음</Button>
            ) : (
              <Button onClick={handleComplete} disabled={createApiImport.isPending}>
                {createApiImport.isPending ? (
                  <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                ) : null}
                완료
              </Button>
            )}
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
