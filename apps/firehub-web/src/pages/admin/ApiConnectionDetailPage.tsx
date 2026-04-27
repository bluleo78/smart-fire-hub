import { ArrowLeft, Copy, HelpCircle } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { StatusBadge } from '@/components/api-connection/StatusBadge';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { handleApiError } from '@/lib/api-error';
import { formatDate } from '@/lib/formatters';

import { Badge } from '../../components/ui/badge';
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
import { Skeleton } from '../../components/ui/skeleton';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../../components/ui/tooltip';
import {
  useApiConnection,
  useDeleteApiConnection,
  useTestApiConnection,
  useUpdateApiConnection,
} from '../../hooks/queries/useApiConnections';
import type { TestConnectionResponse } from '../../types/api-connection';
import type { UpdateApiConnectionRequest } from '../../types/api-connection';

/** API 연결 상세 페이지 — 기본 정보/인증/연결 상태 확인/삭제 */
export default function ApiConnectionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: connection, isLoading, isError } = useApiConnection(Number(id));
  const updateMutation = useUpdateApiConnection();
  const deleteMutation = useDeleteApiConnection();
  const testMutation = useTestApiConnection();
  // 마지막 "지금 확인" 응답을 저장 — 상태코드/응답 본문/헤더/요청 URL 노출 (#76)
  const [lastTestResult, setLastTestResult] = useState<TestConnectionResponse | null>(null);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  // 신규 필드: baseUrl, healthCheckPath
  const [baseUrl, setBaseUrl] = useState('');
  const [healthCheckPath, setHealthCheckPath] = useState('');

  // Auth edit state
  const [isEditingAuth, setIsEditingAuth] = useState(false);
  const [authType, setAuthType] = useState<'API_KEY' | 'BEARER'>('API_KEY');
  const [placement, setPlacement] = useState('header');
  const [headerName, setHeaderName] = useState('');
  const [paramName, setParamName] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [token, setToken] = useState('');

  /** 404 등 오류 응답 시 목록으로 리다이렉트 — UserDetailPage와 동일 패턴 */
  useEffect(() => {
    if (!isError) return;
    toast.error('연결 정보를 불러오는데 실패했습니다.');
    navigate('/admin/api-connections');
  }, [isError, navigate]);

  /* eslint-disable react-hooks/set-state-in-effect -- 서버 응답으로 폼 초기값 시드. connection 참조는 TanStack Query가 id 동일 시 공유하므로 실제 재호출은 라우트/리패치 변경 시에만 발생. */
  useEffect(() => {
    if (!connection) return;
    setName(connection.name);
    setDescription(connection.description ?? '');
    setBaseUrl(connection.baseUrl);
    setHealthCheckPath(connection.healthCheckPath ?? '');
    setAuthType(connection.authType);
    const mc = connection.maskedAuthConfig;
    setPlacement(mc.placement ?? 'header');
    setHeaderName(mc.headerName ?? '');
    setParamName(mc.paramName ?? '');
  }, [connection]);
  /* eslint-enable react-hooks/set-state-in-effect */

  /** 기본 정보(이름/설명/baseUrl/healthCheckPath) 저장 */
  const handleSaveInfo = async () => {
    if (!id) return;
    if (!name.trim()) {
      toast.error('연결 이름은 필수입니다.');
      return;
    }
    try {
      await updateMutation.mutateAsync({
        id: Number(id),
        data: {
          name: name.trim(),
          description: description.trim() || undefined,
          baseUrl: baseUrl.trim() || undefined,
          healthCheckPath: healthCheckPath.trim() || undefined,
        },
      });
      toast.success('연결 정보가 업데이트되었습니다.');
    } catch (error) {
      handleApiError(error, '연결 정보 업데이트에 실패했습니다.');
    }
  };

  /** 인증 설정 저장 */
  const handleSaveAuth = async () => {
    if (!id) return;

    const authConfig: Record<string, string> = { authType };
    if (authType === 'API_KEY') {
      authConfig.placement = placement;
      if (placement === 'header') {
        if (!headerName.trim() || !apiKey.trim()) {
          toast.error('헤더 이름과 키 값을 입력하세요.');
          return;
        }
        authConfig.headerName = headerName;
        authConfig.apiKey = apiKey;
      } else {
        if (!paramName.trim() || !apiKey.trim()) {
          toast.error('파라미터 이름과 키 값을 입력하세요.');
          return;
        }
        authConfig.paramName = paramName;
        authConfig.apiKey = apiKey;
      }
    } else {
      if (!token.trim()) {
        toast.error('토큰을 입력하세요.');
        return;
      }
      authConfig.token = token;
    }

    const data: UpdateApiConnectionRequest = { authType, authConfig };
    try {
      await updateMutation.mutateAsync({ id: Number(id), data });
      toast.success('인증 정보가 업데이트되었습니다.');
      setIsEditingAuth(false);
      setApiKey('');
      setToken('');
    } catch (error) {
      handleApiError(error, '인증 정보 업데이트에 실패했습니다.');
    }
  };

  /** 연결 즉시 테스트 */
  const handleTest = async () => {
    if (!connection) return;
    try {
      const result = await testMutation.mutateAsync(connection.id);
      // 응답 본문/헤더 등 디버깅 정보를 카드로 노출 (#76)
      setLastTestResult(result);
      if (result.ok) {
        toast.success(`연결 정상 (HTTP ${result.status ?? '-'}, ${result.latencyMs}ms)`);
      } else {
        toast.error(`연결 이상: ${result.errorMessage ?? '알 수 없는 오류'}`);
      }
    } catch (error) {
      handleApiError(error, '연결 테스트에 실패했습니다.');
    }
  };

  /**
   * 응답 본문을 Content-Type 또는 첫 글자 기반으로 JSON pretty-print.
   * 파싱 실패 시 원본을 그대로 반환 (HTML 에러 페이지 등).
   */
  const formatResponseBody = (body: string | null, contentType: string | null): string => {
    if (!body) return '';
    const looksJson =
      (contentType ?? '').toLowerCase().includes('json') ||
      body.trimStart().startsWith('{') ||
      body.trimStart().startsWith('[');
    if (!looksJson) return body;
    try {
      return JSON.stringify(JSON.parse(body), null, 2);
    } catch {
      return body;
    }
  };

  /** 클립보드 복사 — 비동기 실패 시 토스트 안내 */
  const handleCopy = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`${label} 복사됨`);
    } catch {
      toast.error('복사에 실패했습니다.');
    }
  };

  const handleDelete = async () => {
    if (!id || !connection) return;
    try {
      await deleteMutation.mutateAsync(Number(id));
      toast.success(`"${connection.name}" 연결이 삭제되었습니다.`);
      navigate('/admin/api-connections');
    } catch (error) {
      handleApiError(error, 'API 연결 삭제에 실패했습니다.');
    }
  };

  if (isLoading) {
    return (
      <div className="mx-auto max-w-2xl space-y-6">
        <Skeleton className="h-8 w-48" />
        <Card>
          <CardContent className="space-y-4 pt-6">
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-4 w-48" />
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!connection) return null;

  const mc = connection.maskedAuthConfig;

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => navigate('/admin/api-connections')}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">API 연결 상세</h1>
        <Badge variant={connection.authType === 'BEARER' ? 'default' : 'secondary'}>
          {connection.authType === 'BEARER' ? 'Bearer' : 'API Key'}
        </Badge>
      </div>

      {/* 연결 상태 카드: lastStatus/latency/error 표시 + 즉시 테스트 버튼 */}
      <Card>
        <CardHeader>
          <CardTitle>연결 상태</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-3">
            <StatusBadge status={connection.lastStatus} checkedAt={connection.lastCheckedAt} />
            {connection.lastLatencyMs !== null && (
              <p className="text-sm text-muted-foreground">지연: {connection.lastLatencyMs}ms</p>
            )}
          </div>
          {connection.lastErrorMessage && (
            <p className="text-sm text-destructive">{connection.lastErrorMessage}</p>
          )}
          <Button
            variant="outline"
            size="sm"
            onClick={handleTest}
            disabled={testMutation.isPending}
          >
            {testMutation.isPending ? '확인 중...' : '지금 확인'}
          </Button>
        </CardContent>
      </Card>

      {/* 최근 테스트 결과 카드 (#76): 응답 상태코드/본문/헤더/요청 URL을 함께 노출하여 디버깅 지원 */}
      {lastTestResult && (
        <Card data-testid="test-result-card">
          <CardHeader>
            <CardTitle>최근 테스트 결과</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge
                variant={lastTestResult.ok ? 'default' : 'destructive'}
                data-testid="test-result-status"
              >
                {lastTestResult.ok ? '성공' : '실패'}
              </Badge>
              <span className="text-sm font-mono">
                HTTP {lastTestResult.status ?? '-'}
              </span>
              <span className="text-sm text-muted-foreground">
                · {lastTestResult.latencyMs}ms
              </span>
            </div>

            {lastTestResult.errorMessage && (
              <p className="text-sm text-destructive">{lastTestResult.errorMessage}</p>
            )}

            {/* 요청 URL */}
            {lastTestResult.requestUrl && (
              <div className="space-y-1">
                <div className="flex items-center justify-between">
                  <Label className="text-xs text-muted-foreground">요청 URL</Label>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 px-2"
                    onClick={() => handleCopy(lastTestResult.requestUrl ?? '', '요청 URL')}
                    aria-label="요청 URL 복사"
                  >
                    <Copy className="h-3 w-3" />
                  </Button>
                </div>
                <p className="text-xs font-mono break-all rounded bg-muted px-2 py-1">
                  {lastTestResult.requestUrl}
                </p>
              </div>
            )}

            {/* 응답 헤더 */}
            {Object.keys(lastTestResult.responseHeaders ?? {}).length > 0 && (
              <details className="space-y-2" data-testid="test-result-headers">
                <summary className="cursor-pointer text-sm font-medium">
                  응답 헤더 ({Object.keys(lastTestResult.responseHeaders).length})
                </summary>
                <div className="overflow-x-auto rounded border">
                  <table className="w-full text-xs">
                    <tbody>
                      {Object.entries(lastTestResult.responseHeaders).map(([k, v]) => (
                        <tr key={k} className="border-b last:border-b-0">
                          <td className="px-2 py-1 font-mono text-muted-foreground whitespace-nowrap align-top">
                            {k}
                          </td>
                          <td className="px-2 py-1 font-mono break-all">{v}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            )}

            {/* 응답 본문 */}
            {lastTestResult.responseBodyPreview !== null &&
              lastTestResult.responseBodyPreview !== undefined && (
                <div className="space-y-1" data-testid="test-result-body">
                  <div className="flex items-center justify-between">
                    <Label className="text-xs text-muted-foreground">응답 본문</Label>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-6 px-2"
                      onClick={() =>
                        handleCopy(lastTestResult.responseBodyPreview ?? '', '응답 본문')
                      }
                      aria-label="응답 본문 복사"
                    >
                      <Copy className="h-3 w-3" />
                    </Button>
                  </div>
                  <pre className="max-h-[400px] overflow-auto rounded bg-muted px-3 py-2 text-xs font-mono whitespace-pre-wrap break-all">
                    {formatResponseBody(
                      lastTestResult.responseBodyPreview,
                      lastTestResult.responseContentType,
                    ) || '(빈 응답)'}
                  </pre>
                </div>
              )}
          </CardContent>
        </Card>
      )}

      {/* Basic Info */}
      <Card>
        <CardHeader>
          <CardTitle>기본 정보</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label>연결 이름</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-2">
            <Label>설명</Label>
            <Input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="설명 (선택)" />
          </div>
          {/* Base URL: 외부 API 기본 주소 */}
          <div className="space-y-2">
            <Label>Base URL</Label>
            <Input
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="https://api.example.com"
            />
          </div>
          {/* 헬스체크 경로 */}
          <div className="space-y-2">
            <Label>헬스체크 경로</Label>
            <Input
              value={healthCheckPath}
              onChange={(e) => setHealthCheckPath(e.target.value)}
              placeholder="/health (선택)"
            />
            <p className="text-xs text-muted-foreground">
              10분마다 자동 상태 점검. 비워두면 점검 안 함.
            </p>
          </div>
          <div className="grid grid-cols-2 gap-4 text-sm text-muted-foreground">
            <div>생성일: {formatDate(connection.createdAt)}</div>
            <div>수정일: {formatDate(connection.updatedAt)}</div>
          </div>
          <Button onClick={handleSaveInfo} disabled={updateMutation.isPending}>
            {updateMutation.isPending ? '저장 중...' : '저장'}
          </Button>
        </CardContent>
      </Card>

      <Separator />

      {/* Auth Config */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>인증 설정</CardTitle>
            {!isEditingAuth && (
              <Button variant="outline" size="sm" onClick={() => setIsEditingAuth(true)}>
                인증 정보 변경
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          {!isEditingAuth ? (
            /* 읽기 전용 마스킹 뷰 */
            <div className="space-y-3">
              <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                <span className="text-muted-foreground">인증 유형</span>
                <span>{connection.authType === 'BEARER' ? 'Bearer Token' : 'API Key'}</span>
              </div>
              {connection.authType === 'API_KEY' && (
                <>
                  <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                    <span className="text-muted-foreground">위치</span>
                    <span>{mc.placement === 'query' ? 'Query Parameter' : 'Header'}</span>
                  </div>
                  <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                    <span className="text-muted-foreground">{mc.placement === 'query' ? '파라미터 이름' : '헤더 이름'}</span>
                    <span>{mc.placement === 'query' ? mc.paramName : mc.headerName}</span>
                  </div>
                  <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                    <span className="text-muted-foreground flex items-center gap-1">
                      키 값
                      {/* (#92) 마스킹 정책 안내 — 평문 노출은 보안상 차단되며 변경 시 재입력 필요. */}
                      <TooltipProvider>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <button
                              type="button"
                              aria-label="키 값 마스킹 정책 안내"
                              data-testid="masked-key-help"
                              className="text-muted-foreground/70 hover:text-foreground"
                            >
                              <HelpCircle className="h-3.5 w-3.5" />
                            </button>
                          </TooltipTrigger>
                          <TooltipContent className="max-w-[260px] text-xs">
                            보안 정책상 마지막 4자리만 표시됩니다. 전체 키 확인이 필요하면 "인증 정보 변경"으로 재입력하세요.
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    </span>
                    <span className="font-mono">{mc.apiKey ?? '****'}</span>
                  </div>
                </>
              )}
              {connection.authType === 'BEARER' && (
                <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                  <span className="text-muted-foreground flex items-center gap-1">
                    토큰
                    {/* (#92) 마스킹 정책 안내 — Bearer 토큰도 동일 정책 적용. */}
                    <TooltipProvider>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <button
                            type="button"
                            aria-label="토큰 마스킹 정책 안내"
                            data-testid="masked-token-help"
                            className="text-muted-foreground/70 hover:text-foreground"
                          >
                            <HelpCircle className="h-3.5 w-3.5" />
                          </button>
                        </TooltipTrigger>
                        <TooltipContent className="max-w-[260px] text-xs">
                          보안 정책상 마지막 4자리만 표시됩니다. 전체 토큰 확인이 필요하면 "인증 정보 변경"으로 재입력하세요.
                        </TooltipContent>
                      </Tooltip>
                    </TooltipProvider>
                  </span>
                  <span className="font-mono">{mc.token ?? '****'}</span>
                </div>
              )}
            </div>
          ) : (
            /* 편집 모드 */
            <div className="space-y-4">
              <div className="space-y-2">
                <Label>인증 유형</Label>
                <Select value={authType} onValueChange={(v) => setAuthType(v as 'API_KEY' | 'BEARER')}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="API_KEY">API Key</SelectItem>
                    <SelectItem value="BEARER">Bearer Token</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {authType === 'API_KEY' && (
                <>
                  <div className="space-y-2">
                    <Label>위치</Label>
                    <Select value={placement} onValueChange={setPlacement}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="header">Header</SelectItem>
                        <SelectItem value="query">Query Parameter</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>{placement === 'query' ? '파라미터 이름' : '헤더 이름'}</Label>
                    <Input
                      placeholder={placement === 'query' ? 'api_key' : 'Authorization'}
                      value={placement === 'query' ? paramName : headerName}
                      onChange={(e) =>
                        placement === 'query'
                          ? setParamName(e.target.value)
                          : setHeaderName(e.target.value)
                      }
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>키 값</Label>
                    <Input
                      type="password"
                      placeholder="새 API 키를 입력하세요"
                      value={apiKey}
                      onChange={(e) => setApiKey(e.target.value)}
                    />
                  </div>
                </>
              )}

              {authType === 'BEARER' && (
                <div className="space-y-2">
                  <Label>Bearer Token</Label>
                  <Input
                    type="password"
                    placeholder="새 토큰을 입력하세요"
                    value={token}
                    onChange={(e) => setToken(e.target.value)}
                  />
                </div>
              )}

              <div className="flex gap-2">
                <Button onClick={handleSaveAuth} disabled={updateMutation.isPending}>
                  {updateMutation.isPending ? '저장 중...' : '인증 저장'}
                </Button>
                <Button variant="outline" onClick={() => { setIsEditingAuth(false); setApiKey(''); setToken(''); }}>
                  취소
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Separator />

      {/* Delete */}
      <Card>
        <CardContent className="pt-6">
          <DeleteConfirmDialog
            entityName="API 연결"
            itemName={connection.name}
            onConfirm={handleDelete}
            trigger={
              <Button variant="destructive">
                이 연결 삭제
              </Button>
            }
          />
        </CardContent>
      </Card>
    </div>
  );
}
