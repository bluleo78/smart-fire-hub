import type { AxiosInstance } from 'axios';

// 에이전트의 메서드 변경 검사는 요청 인자만 보는 첫 거름망이다. 저장된 연결(base URL 쿼리·인증 이름)과
// 리다이렉트는 firehub-api 미리보기가 최종으로 막는다(WD-970).
// 서버가 GET 요청을 다른 메서드로 처리하게 만드는 이름(글자·숫자만 남긴 소문자 꼴).
const METHOD_OVERRIDE_NAMES = new Set(['xhttpmethodoverride', 'xhttpmethod', 'xmethodoverride']);
// 글자·숫자가 아닌 문자(이름 견주기 전에 지운다).
const NON_ALNUM = /[^a-z0-9]/g;
// 미리보기 거절 문구의 공통 머리 — 세 거절(메서드·헤더·쿼리)이 같은 말로 시작하게 한곳에 둔다.
const GET_ONLY = 'API_CALL: 에이전트 미리보기는 GET 만 보냅니다 — ';

/** 서버의 strip·대소문자 무시와 `_`/`-` 를 같게 읽는 대상 서버까지 덮으려고 글자·숫자만 남겨 견준다. */
function isMethodOverrideName(name: string): boolean {
  return METHOD_OVERRIDE_NAMES.has(name.toLowerCase().replace(NON_ALNUM, ''));
}

/** 인증 칸처럼 비어 있을 수 있는 이름을 빼고 실제로 실릴 이름만 남긴다. */
function presentNames(names: Array<string | undefined>): string[] {
  return names.filter((name): name is string => !!name);
}

/**
 * 미리보기 요청에 실릴 헤더 이름 중 메서드를 바꾸는 것을 찾는다.
 * 헤더는 headers 말고도 인라인 인증의 headerName·key_name·`extraHeader:` 키로 실린다.
 */
function findMethodOverrideHeader(
  headers: Record<string, string> = {},
  inlineAuth: Record<string, string> = {},
): string | undefined {
  const names = presentNames([
    ...Object.keys(headers),
    inlineAuth.headerName,
    inlineAuth.key_name,
    ...Object.keys(inlineAuth).map((key) => key.replace(/^extraHeader:/i, '')),
  ]);
  return names.find(isMethodOverrideName);
}

/** 쿼리 이름의 + 를 공백으로, %XX 를 문자로 읽는다. 잘못된 인코딩이면 원문 그대로 둔다(예외 금지). */
function decodeQueryName(name: string): string {
  const spaced = name.replace(/\+/g, ' ');
  try {
    return decodeURIComponent(spaced);
  } catch {
    return spaced;
  }
}

/**
 * 미리보기 요청에 실릴 쿼리 이름 중 메서드를 바꾸는 것을 찾는다.
 * 쿼리는 주소·queryParams 말고도 인라인 인증의 paramName·key_name 으로 실린다.
 * `_method` 는 글자만 남기면 흔한 `method` 파라미터와 겹치므로 이름 그대로 견준다.
 */
function findMethodOverrideQuery(
  url: string,
  queryParams: Record<string, string> = {},
  inlineAuth: Record<string, string> = {},
): string | undefined {
  // 주소의 ? 뒤(# 앞) 원시 쿼리를 & 와 ; 로 나눈다 — ; 를 구분자로 읽는 서버(Rack 등)가 있어 (WD-970)
  // URLSearchParams 로는 `a=1;_method=DELETE` 가 한 이름으로 뭉쳐 놓친다.
  // # 뒤는 프래그먼트라 쿼리가 아니다 — 먼저 잘라낸 뒤 ? 를 찾는다(…/#a?_method=DELETE 는 쿼리 없음).
  const beforeFragment = url.split('#')[0];
  const start = beforeFragment.indexOf('?');
  const rawQuery = start < 0 ? '' : beforeFragment.slice(start + 1);
  const urlNames = rawQuery
    .split(/[&;]/)
    .filter((piece) => piece !== '')
    .map((piece) => piece.split('=')[0]);
  const names = presentNames([
    ...urlNames,
    ...Object.keys(queryParams),
    inlineAuth.paramName,
    inlineAuth.key_name,
  ]);
  return names.find((raw) => {
    const name = decodeQueryName(raw).trim().toLowerCase();
    return name === '_method' || isMethodOverrideName(name);
  });
}

export function createPipelineApi(client: AxiosInstance) {
  return {
    async listPipelines(params?: { page?: number; size?: number }): Promise<unknown> {
      const response = await client.get('/pipelines', { params });
      return response.data;
    },
    async getPipeline(id: number): Promise<unknown> {
      const response = await client.get(`/pipelines/${id}`);
      return response.data;
    },
    async createPipeline(data: {
      name: string;
      description?: string;
      steps: Array<{
        name: string;
        description?: string;
        scriptType: string;
        scriptContent?: string;
        outputDatasetId?: number;
        inputDatasetIds?: number[];
        dependsOnStepNames?: string[];
        loadStrategy?: string;
        apiConfig?: Record<string, unknown>;
        aiConfig?: Record<string, unknown>;
        apiConnectionId?: number;
      }>;
    }): Promise<unknown> {
      const response = await client.post('/pipelines', data);
      return response.data;
    },
    async updatePipeline(
      id: number,
      data: {
        name?: string;
        description?: string;
        isActive?: boolean;
        steps?: Array<{
          name: string;
          description?: string;
          scriptType: string;
          scriptContent?: string;
          outputDatasetId?: number;
          inputDatasetIds?: number[];
          dependsOnStepNames?: string[];
          loadStrategy?: string;
          apiConfig?: Record<string, unknown>;
          aiConfig?: Record<string, unknown>;
          apiConnectionId?: number;
        }>;
      },
    ): Promise<unknown> {
      await client.put(`/pipelines/${id}`, data);
      return { success: true };
    },
    async deletePipeline(id: number): Promise<unknown> {
      await client.delete(`/pipelines/${id}`);
      return { success: true };
    },
    /**
     * API 호출 미리보기.
     * 서버는 주소를 `url` 로만 읽으므로 도구 인자(path/customUrl)를 실행기와 같은 우선순위
     * (연결이면 path, 아니면 customUrl)로 `url` 에 담는다. 주소가 없으면 연결의 기본 주소를
     * 잘못 부르지 않도록 보내지 않고 거절한다.
     */
    async previewApiCall(data: {
      path?: string;
      customUrl?: string;
      method?: string;
      headers?: Record<string, string>;
      queryParams?: Record<string, string>;
      body?: string;
      dataPath: string;
      fieldMappings?: Array<{ sourceField: string; targetColumn: string; dataType?: string }>;
      apiConnectionId?: number;
      inlineAuth?: Record<string, string>;
      timeoutMs?: number;
    }): Promise<unknown> {
      // GET 만 보낸다: 다른 메서드는 저장된 인증으로 실제 데이터를 바꾸는데 이 도구에는 확인 절차가 없다.
      const method = (data.method ?? 'GET').toUpperCase();
      if (method !== 'GET') {
        throw new Error(
          `${GET_ONLY}${method} 요청은 실제 데이터를 바꿀 수 있어 보낼 수 없습니다(POST 는 사용자가 파이프라인 편집기의 「테스트 호출」로 확인, PUT·DELETE 는 미리보기 없음)`,
        );
      }
      const override = findMethodOverrideHeader(data.headers, data.inlineAuth);
      if (override !== undefined) {
        throw new Error(
          `${GET_ONLY}${override} 헤더는 GET 을 다른 메서드로 바꿀 수 있어 보낼 수 없습니다`,
        );
      }
      const { path, customUrl, ...rest } = data;
      const connected = rest.apiConnectionId != null;
      const url = connected ? path : customUrl;
      if (url == null || url.trim() === '') {
        throw new Error(
          connected
            ? 'API_CALL: apiConnectionId 설정 시 path가 필수입니다(연결의 기본 주소 그대로 부르려면 \'/\')'
            : 'API_CALL: apiConnectionId 없이 호출하려면 customUrl이 필수입니다',
        );
      }
      const overrideQuery = findMethodOverrideQuery(url, data.queryParams, data.inlineAuth);
      if (overrideQuery !== undefined) {
        throw new Error(
          `${GET_ONLY}${overrideQuery} 쿼리는 GET 을 다른 메서드로 바꿀 수 있어 보낼 수 없습니다`,
        );
      }
      // 위 검사를 지난 요청은 늘 GET 이라 method 를 'GET' 으로 고정해 보낸다.
      // url 을 마지막에 둬 입력에 섞인 옛 url 보다 검증한 주소가 이긴다.
      const response = await client.post('/pipelines/api-call/preview', {
        ...rest,
        method: 'GET',
        url,
      });
      return response.data;
    },
    async executePipeline(id: number): Promise<unknown> {
      const response = await client.post(`/pipelines/${id}/execute`);
      return response.data;
    },
    async getExecutionStatus(pipelineId: number, executionId: number): Promise<unknown> {
      const response = await client.get(`/pipelines/${pipelineId}/executions/${executionId}`);
      return response.data;
    },
  };
}
