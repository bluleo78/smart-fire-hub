import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  useSavedQueries,
  useDeleteSavedQuery,
  useQueryFolders,
  useExecuteSavedQuery,
} from '../../hooks/queries/useAnalytics';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import {
  Table,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { SearchInput } from '../../components/ui/search-input';
import { TableSkeletonRows } from '../../components/ui/table-skeleton';
import { TableEmptyRow } from '../../components/ui/table-empty';
import { DeleteConfirmDialog } from '../../components/ui/delete-confirm-dialog';
import { SimplePagination } from '../../components/ui/simple-pagination';
import { Tabs, TabsList, TabsTrigger } from '../../components/ui/tabs';
import {
  Plus,
  Trash2,
  Pencil,
  Play,
  FileText,
  Folder,
  BarChart2,
  Share2,
} from 'lucide-react';
import { toast } from 'sonner';
import { handleApiError } from '../../lib/api-error';
import { formatDateShort } from '../../lib/formatters';

function getRelativeTime(dateStr: string): string {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return '방금 전';
  if (mins < 60) return `${mins}분 전`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}일 전`;
  const months = Math.floor(days / 30);
  return `${months}개월 전`;
}

export default function QueryListPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [folder, setFolder] = useState('');
  const [tab, setTab] = useState<'mine' | 'shared'>('mine');
  const [page, setPage] = useState(0);
  const size = 10;

  const sharedOnly = tab === 'shared';

  const { data: queriesData, isLoading } = useSavedQueries({
    search: search || undefined,
    folder: folder || undefined,
    sharedOnly: sharedOnly || undefined,
    page,
    size,
  });

  const { data: foldersData } = useQueryFolders();
  const deleteQuery = useDeleteSavedQuery();
  const executeSavedQuery = useExecuteSavedQuery();

  const queries = queriesData?.content ?? [];
  const totalPages = queriesData?.totalPages ?? 0;
  const totalElements = queriesData?.totalElements;
  const folders = foldersData ?? [];

  const handleDelete = async (id: number, name: string) => {
    try {
      await deleteQuery.mutateAsync(id);
      toast.success(`쿼리 "${name}"이(가) 삭제되었습니다.`);
    } catch (error) {
      handleApiError(error, '쿼리 삭제에 실패했습니다.');
    }
  };

  const handleRun = async (e: React.MouseEvent, id: number, name: string) => {
    e.stopPropagation();
    try {
      await executeSavedQuery.mutateAsync(id);
      toast.success(`쿼리 "${name}" 실행 완료`);
      navigate(`/analytics/queries/${id}`);
    } catch (error) {
      handleApiError(error, '쿼리 실행에 실패했습니다.');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">저장된 쿼리</h1>
        <Button onClick={() => navigate('/analytics/queries/new')}>
          <Plus className="mr-2 h-4 w-4" />
          새 쿼리
        </Button>
      </div>

      {/* Tabs */}
      <Tabs
        value={tab}
        onValueChange={(v) => {
          setTab(v as 'mine' | 'shared');
          setPage(0);
        }}
      >
        <TabsList>
          <TabsTrigger value="mine">내 쿼리</TabsTrigger>
          <TabsTrigger value="shared">공유됨</TabsTrigger>
        </TabsList>
      </Tabs>

      {/* Filters */}
      <div className="flex items-center gap-3 flex-wrap">
        <SearchInput
          placeholder="쿼리 검색..."
          value={search}
          onChange={(value) => {
            setSearch(value);
            setPage(0);
          }}
        />
        <Select
          value={folder || '__all__'}
          onValueChange={(value) => {
            setFolder(value === '__all__' ? '' : value);
            setPage(0);
          }}
        >
          <SelectTrigger className="w-[180px]">
            <SelectValue placeholder="전체 폴더" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="__all__">전체 폴더</SelectItem>
            {folders.map((f) => (
              <SelectItem key={f} value={f}>
                {f}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Table */}
      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>이름</TableHead>
              <TableHead>폴더</TableHead>
              <TableHead>데이터셋</TableHead>
              <TableHead>차트</TableHead>
              <TableHead>수정일</TableHead>
              <TableHead className="w-[100px]" />
            </TableRow>
          </TableHeader>
          <tbody>
            {isLoading ? (
              <TableSkeletonRows columns={6} rows={5} />
            ) : queries.length > 0 ? (
              queries.map((query) => (
                <TableRow
                  key={query.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors group"
                  onClick={() => navigate(`/analytics/queries/${query.id}`)}
                >
                  <TableCell className="font-medium">
                    <div className="flex items-center gap-2">
                      <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
                      <div>
                        <div className="flex items-center gap-1.5">
                          <span>{query.name}</span>
                          {query.isShared && (
                            <Badge variant="secondary" className="text-xs gap-1 py-0">
                              <Share2 className="h-2.5 w-2.5" />
                              공유
                            </Badge>
                          )}
                        </div>
                        {query.description && (
                          <p className="text-xs text-muted-foreground truncate max-w-xs mt-0.5">
                            {query.description}
                          </p>
                        )}
                        <p className="text-xs text-muted-foreground mt-0.5">
                          {query.createdByName}
                        </p>
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    {query.folder ? (
                      <div className="flex items-center gap-1 text-sm text-muted-foreground">
                        <Folder className="h-3.5 w-3.5" />
                        {query.folder}
                      </div>
                    ) : (
                      <span className="text-muted-foreground text-sm">-</span>
                    )}
                  </TableCell>
                  <TableCell>
                    {query.datasetName ? (
                      <Badge variant="outline" className="text-xs">
                        {query.datasetName}
                      </Badge>
                    ) : (
                      <span className="text-muted-foreground text-sm">cross-dataset</span>
                    )}
                  </TableCell>
                  <TableCell>
                    {query.chartCount > 0 ? (
                      <div className="flex items-center gap-1 text-sm text-muted-foreground">
                        <BarChart2 className="h-3.5 w-3.5" />
                        {query.chartCount}개
                      </div>
                    ) : (
                      <span className="text-muted-foreground text-sm">-</span>
                    )}
                  </TableCell>
                  <TableCell>
                    <span className="text-sm text-muted-foreground" title={formatDateShort(query.updatedAt)}>
                      {getRelativeTime(query.updatedAt)}
                    </span>
                  </TableCell>
                  <TableCell>
                    {/* Hover action buttons */}
                    <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 w-7 p-0"
                        title="실행"
                        onClick={(e) => handleRun(e, query.id, query.name)}
                      >
                        <Play size={13} />
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 w-7 p-0"
                        title="편집"
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/analytics/queries/${query.id}`);
                        }}
                      >
                        <Pencil size={13} />
                      </Button>
                      <DeleteConfirmDialog
                        entityName="쿼리"
                        itemName={query.name}
                        onConfirm={() => handleDelete(query.id, query.name)}
                        trigger={
                          <Button
                            variant="ghost"
                            size="sm"
                            className="h-7 w-7 p-0"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                          </Button>
                        }
                      />
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow
                colSpan={6}
                message={search || folder ? '검색 결과가 없습니다.' : '저장된 쿼리가 없습니다.'}
              />
            )}
          </tbody>
        </Table>
      </div>

      <SimplePagination
        page={page}
        totalPages={totalPages}
        onPageChange={setPage}
        totalElements={totalElements}
        pageSize={size}
      />
    </div>
  );
}
