import type { AxiosInstance } from 'axios';

export function createMiscApi(client: AxiosInstance) {
  return {
    /** 데이터셋 가져오기 이력 한 페이지(최신 순 — WD-535). page/size 를 생략하면 서버 기본값(0, 20)을 쓴다. */
    async listImports(datasetId: number, params?: { page?: number; size?: number }): Promise<unknown> {
      const response = await client.get(`/datasets/${datasetId}/imports`, { params });
      return response.data;
    },
    async getDashboard(): Promise<unknown> {
      const response = await client.get('/dashboard/stats');
      return response.data;
    },
  };
}
