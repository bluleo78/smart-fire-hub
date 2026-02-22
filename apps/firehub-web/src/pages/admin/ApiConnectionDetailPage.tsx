import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useApiConnection, useUpdateApiConnection, useDeleteApiConnection } from '../../hooks/queries/useApiConnections';
import type { UpdateApiConnectionRequest } from '../../types/api-connection';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Badge } from '../../components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { ArrowLeft } from 'lucide-react';
import { toast } from 'sonner';
import { handleApiError } from '@/lib/api-error';

export default function ApiConnectionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: connection, isLoading } = useApiConnection(Number(id));
  const updateMutation = useUpdateApiConnection();
  const deleteMutation = useDeleteApiConnection();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  // Auth edit state
  const [isEditingAuth, setIsEditingAuth] = useState(false);
  const [authType, setAuthType] = useState<'API_KEY' | 'BEARER'>('API_KEY');
  const [placement, setPlacement] = useState('header');
  const [headerName, setHeaderName] = useState('');
  const [paramName, setParamName] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [token, setToken] = useState('');

  useEffect(() => {
    if (connection) {
      setName(connection.name);
      setDescription(connection.description ?? '');
      setAuthType(connection.authType);
      // Restore non-sensitive fields from masked config
      const mc = connection.maskedAuthConfig;
      setPlacement(mc.placement ?? 'header');
      setHeaderName(mc.headerName ?? '');
      setParamName(mc.paramName ?? '');
    }
  }, [connection]);

  const handleSaveInfo = async () => {
    if (!id || !name.trim()) return;
    try {
      await updateMutation.mutateAsync({
        id: Number(id),
        data: { name: name.trim(), description: description.trim() || undefined },
      });
      toast.success('연결 정보가 업데이트되었습니다.');
    } catch (error) {
      handleApiError(error, '연결 정보 업데이트에 실패했습니다.');
    }
  };

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
        <h1 className="text-2xl font-bold">API 연결 상세</h1>
        <Badge variant={connection.authType === 'BEARER' ? 'default' : 'secondary'}>
          {connection.authType === 'BEARER' ? 'Bearer' : 'API Key'}
        </Badge>
      </div>

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
          <div className="grid grid-cols-2 gap-4 text-sm text-muted-foreground">
            <div>생성일: {new Date(connection.createdAt).toLocaleString('ko-KR')}</div>
            <div>수정일: {new Date(connection.updatedAt).toLocaleString('ko-KR')}</div>
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
            /* Read-only masked view */
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
                    <span className="text-muted-foreground">키 값</span>
                    <span className="font-mono">{mc.apiKey ?? '****'}</span>
                  </div>
                </>
              )}
              {connection.authType === 'BEARER' && (
                <div className="grid grid-cols-[120px_1fr] gap-2 text-sm">
                  <span className="text-muted-foreground">토큰</span>
                  <span className="font-mono">{mc.token ?? '****'}</span>
                </div>
              )}
            </div>
          ) : (
            /* Edit mode */
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
