import { Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { handleApiError } from '@/lib/api-error';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
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
import {
  Table,
  TableBody,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { useApiConnections, useCreateApiConnection, useDeleteApiConnection } from '../../hooks/queries/useApiConnections';
import type { CreateApiConnectionRequest } from '../../types/api-connection';

export default function ApiConnectionListPage() {
  const navigate = useNavigate();
  const { data: connections, isLoading } = useApiConnections();
  const createMutation = useCreateApiConnection();
  const deleteMutation = useDeleteApiConnection();

  const [dialogOpen, setDialogOpen] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [authType, setAuthType] = useState<'API_KEY' | 'BEARER'>('API_KEY');
  const [placement, setPlacement] = useState('header');
  const [headerName, setHeaderName] = useState('');
  const [paramName, setParamName] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [token, setToken] = useState('');

  const resetForm = () => {
    setName('');
    setDescription('');
    setAuthType('API_KEY');
    setPlacement('header');
    setHeaderName('');
    setParamName('');
    setApiKey('');
    setToken('');
  };

  const handleCreate = async () => {
    if (!name.trim()) {
      toast.error('연결 이름을 입력하세요.');
      return;
    }

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

    const data: CreateApiConnectionRequest = {
      name: name.trim(),
      description: description.trim() || undefined,
      authType,
      authConfig,
    };

    try {
      await createMutation.mutateAsync(data);
      toast.success('API 연결이 생성되었습니다.');
      setDialogOpen(false);
      resetForm();
    } catch (error) {
      handleApiError(error, 'API 연결 생성에 실패했습니다.');
    }
  };

  const handleDelete = async (id: number, connName: string) => {
    try {
      await deleteMutation.mutateAsync(id);
      toast.success(`"${connName}" 연결이 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, 'API 연결 삭제에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">API 연결 관리</h1>
        <Dialog open={dialogOpen} onOpenChange={(open) => { setDialogOpen(open); if (!open) resetForm(); }}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="mr-2 h-4 w-4" />
              새 연결
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-md">
            <DialogHeader>
              <DialogTitle>새 API 연결 생성</DialogTitle>
            </DialogHeader>
            <div className="space-y-4">
              <div className="space-y-2">
                <Label>연결 이름</Label>
                <Input
                  placeholder="예: Make.com API"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label>설명 (선택)</Label>
                <Input
                  placeholder="연결에 대한 설명"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </div>
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
                      placeholder="API 키를 입력하세요"
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
                    placeholder="토큰을 입력하세요"
                    value={token}
                    onChange={(e) => setToken(e.target.value)}
                  />
                </div>
              )}

              <Button
                className="w-full"
                onClick={handleCreate}
                disabled={createMutation.isPending}
              >
                {createMutation.isPending ? '생성 중...' : '생성'}
              </Button>
            </div>
          </DialogContent>
        </Dialog>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>인증 유형</TableHead>
              <TableHead>설명</TableHead>
              <TableHead>생성일</TableHead>
              <TableHead className="w-[60px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={5} rows={3} />
            ) : connections && connections.length > 0 ? (
              connections.map((conn) => (
                <TableRow
                  key={conn.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => navigate(`/admin/api-connections/${conn.id}`)}
                >
                  <td className="p-4 font-medium">{conn.name}</td>
                  <td className="p-4">
                    <Badge variant={conn.authType === 'BEARER' ? 'default' : 'secondary'}>
                      {conn.authType === 'BEARER' ? 'Bearer' : 'API Key'}
                    </Badge>
                  </td>
                  <td className="p-4 text-muted-foreground">{conn.description ?? '-'}</td>
                  <td className="p-4 text-muted-foreground text-sm">
                    {new Date(conn.createdAt).toLocaleDateString('ko-KR')}
                  </td>
                  <td className="p-4">
                    <DeleteConfirmDialog
                      entityName="API 연결"
                      itemName={conn.name}
                      onConfirm={() => handleDelete(conn.id, conn.name)}
                      trigger={
                        <Button variant="outline" size="sm">
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      }
                    />
                  </td>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={5} message="등록된 API 연결이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
