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
      storageType?: string;
      originType?: string;
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
      storageType?: string;
      originType?: string;
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
    // 데이터셋에 저장된 표→그래프 매핑 조회(GET /datasets/{id}/mapping). 표 투영이 소비.
    async getDatasetMapping(id: number): Promise<{ datasetId: number; ontologyId: number; spec: unknown; status: string }> {
      const response = await client.get(`/datasets/${id}/mapping`);
      return response.data;
    },
    // 데이터셋↔온톨로지 바인딩 조회(GET /datasets/{id}/ontology). ontologyId=null이면 미바인딩.
    async getDatasetOntology(id: number): Promise<{ datasetId: number; ontologyId: number | null }> {
      const response = await client.get(`/datasets/${id}/ontology`);
      return response.data;
    },
    // 추론된 매핑을 draft로 저장(PUT /datasets/{id}/mapping). body는 MappingSpec 그대로.
    async saveDatasetMapping(id: number, spec: unknown): Promise<void> {
      await client.put(`/datasets/${id}/mapping`, spec);
    },
    // 데이터셋↔온톨로지 바인딩 설정(PUT /datasets/{id}/ontology, 멱등 UPSERT).
    // 왜 필요한가: 매핑 추론(graphrag_infer_mapping)이 바인딩 없으면 거부하므로, 바인딩 수단이
    // 없으면 에이전트 단독으로는 구축 파이프라인을 시작조차 못 하는 데드엔드가 된다.
    async bindDatasetOntology(id: number, ontologyId: number): Promise<void> {
      await client.put(`/datasets/${id}/ontology`, { ontologyId });
    },
    // draft 매핑을 활성화(POST /datasets/{id}/mapping/activate). 백엔드가 conformance 재검증 후 전환.
    // 표 투영(graphrag_project_table)이 active 만 허용하므로 파이프라인 완결에 필수.
    async activateDatasetMapping(
      id: number,
    ): Promise<{ datasetId: number; ontologyId: number; spec: unknown; status: string }> {
      const response = await client.post(`/datasets/${id}/mapping/activate`);
      return response.data;
    },
  };
}
