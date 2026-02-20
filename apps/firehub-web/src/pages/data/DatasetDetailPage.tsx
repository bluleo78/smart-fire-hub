import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useDataset, useCategories } from '../../hooks/queries/useDatasets';
import { Skeleton } from '../../components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { DatasetInfoTab } from './tabs/DatasetInfoTab';
import { DatasetColumnsTab } from './tabs/DatasetColumnsTab';
import { DatasetDataTab } from './tabs/DatasetDataTab';
import { DatasetHistoryTab } from './tabs/DatasetHistoryTab';

export default function DatasetDetailPage() {
  const { id } = useParams();
  const datasetId = Number(id);
  const [activeTab, setActiveTab] = useState('info');

  const { data: dataset, isLoading } = useDataset(datasetId);
  const { data: categoriesData } = useCategories();

  const categories = categoriesData || [];

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
        <h1 className="text-2xl font-bold">{dataset.name}</h1>
        <p className="text-sm text-muted-foreground mt-1">
          테이블: <span className="font-mono">{dataset.tableName}</span>
        </p>
      </div>

      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList className="border-b justify-start h-10">
          <TabsTrigger value="info">정보</TabsTrigger>
          <TabsTrigger value="columns">필드</TabsTrigger>
          <TabsTrigger value="data">데이터</TabsTrigger>
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
        {activeTab === 'history' && (
          <div className="mt-6">
            <DatasetHistoryTab dataset={dataset} datasetId={datasetId} />
          </div>
        )}
      </Tabs>
    </div>
  );
}
