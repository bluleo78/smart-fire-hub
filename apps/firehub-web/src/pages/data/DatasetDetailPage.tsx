import { Copy,Plus, Shield, Star, X } from 'lucide-react';
import { useEffect,useState } from 'react';
import { useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '../../components/ui/popover';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Skeleton } from '../../components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '../../components/ui/tabs';
import {
  useAddTag,
  useCategories,
  useDataset,
  useRemoveTag,
  useTags,
  useToggleFavorite,
  useUpdateStatus,
} from '../../hooks/queries/useDatasets';
import { useAuth } from '../../hooks/useAuth';
import { useRecentDatasets } from '../../hooks/useRecentDatasets';
import { CloneDatasetDialog } from './components/CloneDatasetDialog';
import { LinkedPipelineStatus } from './components/LinkedPipelineStatus';
import { DatasetColumnsTab } from './tabs/DatasetColumnsTab';
import { DatasetDataTab } from './tabs/DatasetDataTab';
import { DatasetHistoryTab } from './tabs/DatasetHistoryTab';
import { DatasetInfoTab } from './tabs/DatasetInfoTab';
import { DatasetMapTab } from './tabs/DatasetMapTab';

export default function DatasetDetailPage() {
  const { id } = useParams();
  const datasetId = Number(id);
  const [activeTab, setActiveTab] = useState('info');

  const { data: dataset, isLoading } = useDataset(datasetId);
  const { data: categoriesData } = useCategories();
  const { addRecent } = useRecentDatasets();
  const { isAdmin } = useAuth();

  const toggleFavorite = useToggleFavorite();
  const updateStatus = useUpdateStatus(datasetId);
  const addTag = useAddTag(datasetId);
  const removeTag = useRemoveTag(datasetId);
  const { data: allTags = [] } = useTags();

  const [tagInputOpen, setTagInputOpen] = useState(false);
  const [tagInput, setTagInput] = useState('');
  const [statusEditOpen, setStatusEditOpen] = useState(false);
  const [statusValue, setStatusValue] = useState<string>('NONE');
  const [statusNote, setStatusNote] = useState('');
  const [cloneDialogOpen, setCloneDialogOpen] = useState(false);

  const categories = categoriesData || [];

  useEffect(() => {
    if (dataset) {
      addRecent({
        id: dataset.id,
        name: dataset.name,
        tableName: dataset.tableName,
        accessedAt: new Date().toISOString(),
      });
      setStatusValue(dataset.status || 'NONE');
      setStatusNote(dataset.statusNote || '');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataset?.id]);

  const handleToggleFavorite = async () => {
    if (!dataset) return;
    try {
      await toggleFavorite.mutateAsync(dataset.id);
      toast.success(dataset.isFavorite ? '즐겨찾기 해제되었습니다.' : '즐겨찾기에 추가되었습니다.');
    } catch {
      toast.error('즐겨찾기 변경에 실패했습니다.');
    }
  };

  const handleAddTag = async () => {
    const tag = tagInput.trim();
    if (!tag) return;
    if (dataset?.tags?.includes(tag)) {
      toast.error('이미 추가된 태그입니다.');
      return;
    }
    try {
      await addTag.mutateAsync(tag);
      setTagInput('');
      setTagInputOpen(false);
      toast.success(`태그 "${tag}" 추가`);
    } catch {
      toast.error('태그 추가에 실패했습니다.');
    }
  };

  const handleRemoveTag = async (tag: string) => {
    try {
      await removeTag.mutateAsync(tag);
      toast.success(`태그 "${tag}" 제거`);
    } catch {
      toast.error('태그 제거에 실패했습니다.');
    }
  };

  const handleUpdateStatus = async () => {
    try {
      await updateStatus.mutateAsync({ status: statusValue, note: statusNote || undefined });
      setStatusEditOpen(false);
      toast.success('상태가 변경되었습니다.');
    } catch {
      toast.error('상태 변경에 실패했습니다.');
    }
  };

  const filteredTagSuggestions = allTags.filter(
    (t) => t.toLowerCase().includes(tagInput.toLowerCase()) && !dataset?.tags?.includes(t)
  );

  const hasGeometry = dataset?.columns.some(c => c.dataType === 'GEOMETRY') ?? false;

  if (isLoading || !dataset) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        {/* Header with name and favorite */}
        <div className="flex items-start gap-3">
          <button
            className="mt-1 p-1 rounded hover:bg-muted transition-colors flex-shrink-0"
            onClick={handleToggleFavorite}
            title={dataset.isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
          >
            <Star
              className={`h-5 w-5 transition-colors ${
                dataset.isFavorite
                  ? 'fill-yellow-400 text-yellow-400'
                  : 'text-muted-foreground hover:text-yellow-400'
              }`}
            />
          </button>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h1 className="text-2xl font-bold">{dataset.name}</h1>
              {/* Status badge */}
              {dataset.status === 'CERTIFIED' && (
                <Badge className="bg-green-100 text-green-800 border-0">
                  ✓ Certified
                </Badge>
              )}
              {dataset.status === 'DEPRECATED' && (
                <Badge className="bg-red-100 text-red-800 border-0">
                  Deprecated
                </Badge>
              )}
              {/* Clone button */}
              <Button variant="outline" size="sm" className="h-7 gap-1 text-xs" onClick={() => setCloneDialogOpen(true)}>
                <Copy className="h-3 w-3" />
                복제
              </Button>
              {/* Admin: status change button */}
              {isAdmin && (
                <Popover open={statusEditOpen} onOpenChange={setStatusEditOpen}>
                  <PopoverTrigger asChild>
                    <Button variant="ghost" size="sm" className="h-7 gap-1 text-xs text-muted-foreground">
                      <Shield className="h-3 w-3" />
                      상태 변경
                    </Button>
                  </PopoverTrigger>
                  <PopoverContent className="w-72 space-y-3">
                    <p className="text-sm font-medium">데이터셋 상태 변경</p>
                    <Select value={statusValue} onValueChange={setStatusValue}>
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="NONE">상태 없음</SelectItem>
                        <SelectItem value="CERTIFIED">인증됨 (Certified)</SelectItem>
                        <SelectItem value="DEPRECATED">사용 중단 (Deprecated)</SelectItem>
                      </SelectContent>
                    </Select>
                    <Input
                      placeholder="상태 노트 (선택)"
                      value={statusNote}
                      onChange={(e) => setStatusNote(e.target.value)}
                    />
                    <div className="flex gap-2">
                      <Button size="sm" onClick={handleUpdateStatus} disabled={updateStatus.isPending}>
                        저장
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => setStatusEditOpen(false)}>
                        취소
                      </Button>
                    </div>
                  </PopoverContent>
                </Popover>
              )}
            </div>
            <p className="text-sm text-muted-foreground mt-1">
              테이블: <span className="font-mono">{dataset.tableName}</span>
            </p>
            {dataset.statusNote && (
              <p className="text-xs text-muted-foreground mt-0.5 italic">{dataset.statusNote}</p>
            )}

            {/* Tags */}
            <div className="flex items-center gap-1.5 flex-wrap mt-2">
              {(dataset.tags || []).map((tag) => (
                <Badge key={tag} variant="secondary" className="text-xs gap-1 pr-1">
                  {tag}
                  <button
                    className="ml-0.5 rounded-full hover:bg-muted-foreground/20 transition-colors"
                    onClick={() => handleRemoveTag(tag)}
                    title="태그 제거"
                  >
                    <X className="h-3 w-3" />
                  </button>
                </Badge>
              ))}

              {/* Linked pipelines */}
              <LinkedPipelineStatus pipelines={dataset.linkedPipelines ?? []} />

              {/* Add tag popover */}
              <Popover open={tagInputOpen} onOpenChange={setTagInputOpen}>
                <PopoverTrigger asChild>
                  <Button variant="ghost" size="sm" className="h-6 w-6 p-0 rounded-full" title="태그 추가">
                    <Plus className="h-3 w-3" />
                  </Button>
                </PopoverTrigger>
                <PopoverContent className="w-64 p-2 space-y-2">
                  <p className="text-xs font-medium text-muted-foreground px-1">태그 추가</p>
                  <Input
                    placeholder="태그 입력..."
                    value={tagInput}
                    onChange={(e) => setTagInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') handleAddTag();
                      if (e.key === 'Escape') setTagInputOpen(false);
                    }}
                    className="h-8 text-sm"
                    autoFocus
                  />
                  {filteredTagSuggestions.length > 0 && (
                    <div className="space-y-0.5">
                      {filteredTagSuggestions.slice(0, 6).map((suggestion) => (
                        <button
                          key={suggestion}
                          className="w-full text-left text-sm px-2 py-1 rounded hover:bg-muted transition-colors"
                          onClick={async () => {
                            if (dataset?.tags?.includes(suggestion)) {
                              toast.error('이미 추가된 태그입니다.');
                              return;
                            }
                            try {
                              await addTag.mutateAsync(suggestion);
                              setTagInput('');
                              setTagInputOpen(false);
                              toast.success(`태그 "${suggestion}" 추가`);
                            } catch {
                              toast.error('태그 추가에 실패했습니다.');
                            }
                          }}
                        >
                          {suggestion}
                        </button>
                      ))}
                    </div>
                  )}
                  <Button size="sm" onClick={handleAddTag} disabled={!tagInput.trim() || addTag.isPending} className="w-full">
                    추가
                  </Button>
                </PopoverContent>
              </Popover>
            </div>
          </div>
        </div>
      </div>

      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList className="border-b justify-start h-10">
          <TabsTrigger value="info">정보</TabsTrigger>
          <TabsTrigger value="columns">필드</TabsTrigger>
          <TabsTrigger value="data">데이터</TabsTrigger>
          {hasGeometry && <TabsTrigger value="map">지도</TabsTrigger>}
          <TabsTrigger value="history">이력</TabsTrigger>
        </TabsList>

        {activeTab === 'info' && (
          <div className="mt-6">
            <DatasetInfoTab dataset={dataset} categories={categories} datasetId={datasetId} />
          </div>
        )}
        {activeTab === 'columns' && (
          <div className="mt-6">
            <DatasetColumnsTab dataset={dataset} datasetId={datasetId} />
          </div>
        )}
        {activeTab === 'data' && (
          <div className="mt-6">
            <DatasetDataTab dataset={dataset} datasetId={datasetId} />
          </div>
        )}
        {activeTab === 'map' && hasGeometry && (
          <div className="mt-6">
            <DatasetMapTab dataset={dataset} datasetId={datasetId} />
          </div>
        )}
        {activeTab === 'history' && (
          <div className="mt-6">
            <DatasetHistoryTab dataset={dataset} datasetId={datasetId} />
          </div>
        )}
      </Tabs>

      {/* Clone Dataset Dialog */}
      <CloneDatasetDialog
        open={cloneDialogOpen}
        onOpenChange={setCloneDialogOpen}
        dataset={dataset}
      />
    </div>
  );
}
