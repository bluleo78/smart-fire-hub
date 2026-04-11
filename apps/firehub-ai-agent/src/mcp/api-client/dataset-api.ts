import type { AxiosInstance } from 'axios';

/**
 * 데이터셋 컬럼 추가/조회용 입력 타입.
 * 백엔드 `AddColumnRequest` 레코드와 동일한 필드 셋을 사용한다.
 */
export interface DatasetColumnInput {
  columnName: string;
  displayName: string;
  dataType: string;
  maxLength?: number;
  isNullable?: boolean;
  isIndexed?: boolean;
  isPrimaryKey?: boolean;
  description?: string;
}

/**
 * 백엔드 `DatasetColumnResponse` 레코드의 TypeScript 미러.
 * addColumn 응답 및 기타 컬럼 조회 응답의 형태.
 */
export interface DatasetColumnResponse {
  id: number;
  columnName: string;
  displayName: string;
  dataType: string;
  maxLength?: number | null;
  isNullable: boolean;
  isIndexed: boolean;
  description?: string | null;
  columnOrder: number;
  isPrimaryKey: boolean;
}

/**
 * 백엔드 `DatasetReferencesResponse` 레코드의 TypeScript 미러.
 * 데이터셋을 참조하는 파이프라인/대시보드/스마트잡 정보를 반환한다.
 * 삭제 전 영향 범위 확인용.
 */
export interface DatasetReferences {
  datasetId: number;
  pipelines: Array<{ id: number; name: string }>;
  dashboards: Array<{ id: number; name: string }>;
  proactiveJobs: Array<{ id: number; name: string }>;
  totalCount: number;
}

export function createDatasetApi(client: AxiosInstance) {
  return {
    async listDatasets(params?: {
      categoryId?: number;
      datasetType?: string;
      search?: string;
      status?: string;
      favoriteOnly?: boolean;
      page?: number;
      size?: number;
    }): Promise<unknown> {
      const response = await client.get('/datasets', { params });
      return response.data;
    },
    async getDataset(id: number): Promise<unknown> {
      const response = await client.get(`/datasets/${id}`);
      return response.data;
    },
    async queryDatasetData(
      id: number,
      params?: {
        search?: string;
        sortBy?: string;
        sortDir?: string;
        includeTotalCount?: boolean;
        page?: number;
        size?: number;
      },
    ): Promise<unknown> {
      const response = await client.get(`/datasets/${id}/data`, { params });
      return response.data;
    },
    async createDataset(data: {
      name: string;
      tableName: string;
      description?: string;
      categoryId?: number;
      datasetType?: string;
      columns: Array<{
        columnName: string;
        displayName: string;
        dataType: string;
        maxLength?: number;
        isNullable?: boolean;
        isIndexed?: boolean;
        isPrimaryKey?: boolean;
        description?: string;
      }>;
    }): Promise<unknown> {
      const response = await client.post('/datasets', data);
      return response.data;
    },
    async updateDataset(
      id: number,
      data: {
        name?: string;
        description?: string;
        categoryId?: number;
      },
    ): Promise<unknown> {
      const response = await client.put(`/datasets/${id}`, data);
      return response.data;
    },
    /** 데이터셋 삭제. data 스키마의 물리 테이블도 함께 DROP된다. */
    async deleteDataset(id: number): Promise<{ success: true }> {
      await client.delete(`/datasets/${id}`);
      return { success: true };
    },
    /**
     * 데이터셋에 컬럼을 추가한다.
     * 백엔드는 201 + DatasetColumnResponse 를 반환한다.
     */
    async addDatasetColumn(
      datasetId: number,
      column: DatasetColumnInput,
    ): Promise<DatasetColumnResponse> {
      const { data } = await client.post(`/datasets/${datasetId}/columns`, column);
      return data;
    },
    /**
     * 데이터셋에서 컬럼을 제거한다. 실제 data 스키마 테이블의 컬럼도
     * 함께 DROP되므로 파괴 작업이다. 호출자는 사용자 확인 후 호출해야 한다.
     */
    async dropDatasetColumn(datasetId: number, columnId: number): Promise<{ success: true }> {
      await client.delete(`/datasets/${datasetId}/columns/${columnId}`);
      return { success: true };
    },
    /**
     * 데이터셋을 참조하는 파이프라인/대시보드/스마트잡 목록을 조회한다.
     * 데이터셋 삭제 전 영향 범위를 확인하는 용도로 사용한다.
     */
    async getDatasetReferences(id: number): Promise<DatasetReferences> {
      const { data } = await client.get(`/datasets/${id}/references`);
      return data;
    },
  };
}
