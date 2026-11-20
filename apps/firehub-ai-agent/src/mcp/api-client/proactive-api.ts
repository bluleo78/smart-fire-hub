import type { AxiosInstance } from 'axios';

/** 자동 양식 이름 겹침 재시도 상한(WD-824) — 웹 NAME_RETRY_MAX_ATTEMPTS 와 같다. */
const TEMPLATE_NAME_MAX_ATTEMPTS = 20;
/** 양식 이름 길이 한도(서버 varchar(200)). */
const TEMPLATE_NAME_MAX_LENGTH = 200;

/**
 * 409 + code DUPLICATE_NAME 인지 본다(WD-824) — 다른 409·오류는 재시도 대상이 아니다.
 * api-client 응답 인터셉터가 오류를 `status`·`response`(원본 axios 응답)를 가진 Error 로 다시 던지므로 그 모양을 읽는다.
 */
function isDuplicateNameError(error: unknown): boolean {
  const e = error as { status?: number; response?: { data?: { code?: string } } } | null;
  return e?.status === 409 && e.response?.data?.code === 'DUPLICATE_NAME';
}

/**
 * 시도 번호별 양식 이름 — 1번째는 그대로, 2번째부터 「이름 (2)」「이름 (3)」 …(WD-824)
 * 200 단위(UTF-16, 서버 @Size 기준 — WD-699)를 넘으면 이름 쪽만 잘라 맞추고, 「 양식」 꼬리와 번호는 항상 남긴다.
 * 길이는 String.length(UTF-16 단위)로 센다. 이모지는 2단위라 코드 포인트로 세면 서버보다 짧게 세어 400 이 난다.
 * 자르는 자리 끝이 surrogate 쌍의 앞쪽(high surrogate)이면 쌍이 깨지므로 그 한 단위도 뺀다.
 * 자르는 자리의 공백은 지운다. 웹 numberedGoalTemplateName 과 같은 모양이다.
 */
export function numberedTemplateName(base: string, attempt: number): string {
  const kept = base.endsWith(' 양식') ? ' 양식' : '';
  const stem = kept ? base.slice(0, -kept.length) : base;
  const tail = kept + (attempt <= 1 ? '' : ` (${attempt})`);
  // 꼬리 길이도 UTF-16 단위로 잰다 — 꼬리는 BMP 문자뿐이라 String.length 와 같다.
  const room = TEMPLATE_NAME_MAX_LENGTH - tail.length;
  if (stem.length <= room) return stem + tail;
  let end = room;
  // 자르는 자리 바로 앞 단위가 high surrogate(0xD800–0xDBFF)면 쌍의 앞부분만 남으므로 한 단위 더 뺀다.
  const before = stem.charCodeAt(end - 1);
  if (before >= 0xd800 && before <= 0xdbff) end -= 1;
  return stem.slice(0, end).trimEnd() + tail;
}

/** 양식 이름 목록 읽기 — 한 페이지 크기·page 2 부터의 병렬 폭·최대 페이지 수(웹과 같다, WD-837·WD-842). */
const TEMPLATE_LIST_PAGE_SIZE = 200;
const TEMPLATE_LIST_PARALLEL = 4;
const TEMPLATE_LIST_MAX_PAGES = 20;

/** 이름 비교 기준 — 앞뒤 공백·대소문자를 무시한다(서버 중복 판정과 맞춘다). */
function normalizeName(value: string): string {
  return value.trim().toLowerCase();
}

/**
 * 양식 이름을 읽어 온다(WD-837·WD-842) — 범위는 서버 중복 판정과 같다(본인 + 기본 양식, user_id NULL).
 * page 0·1 은 하나씩(201건이면 요청 2건), 그 뒤는 4페이지씩 병렬로 최대 20페이지(page 0~19). 이름은 힌트일 뿐이라
 * page 0 이 실패하면 빈 목록(순차 재시도로 복귀), 그 뒤 실패에는 읽은 이름을 돌려준다(버리면 번호 건너뛰기가 헛수고가 된다).
 */
async function loadTemplateNames(client: AxiosInstance): Promise<string[]> {
  // 한 페이지의 행을 읽는다 — 이름 담기는 아래가 페이지 순서대로 한다(병렬 읽기 순서와 무관하게 결정적이게)
  const readPage = async (page: number): Promise<Array<{ name?: unknown }>> => {
    const response = await client.get('/proactive/templates', {
      params: { page, size: TEMPLATE_LIST_PAGE_SIZE },
    });
    return Array.isArray(response.data) ? response.data : [];
  };
  const names: string[] = [];
  // 페이지 이름을 담고, 짧은 페이지(마지막)였는지 돌려준다
  const collect = (rows: Array<{ name?: unknown }>): boolean => {
    for (const row of rows) if (typeof row?.name === 'string') names.push(row.name);
    return rows.length < TEMPLATE_LIST_PAGE_SIZE;
  };
  // page 0: 실패하면 읽은 것이 없으므로 빈 목록
  try {
    if (collect(await readPage(0))) return names;
  } catch {
    return [];
  }
  // page 1 은 너비 1 묶음, 그 뒤는 너비 4 묶음 — 같은 규칙으로 본다
  for (let start = 1; start < TEMPLATE_LIST_MAX_PAGES; ) {
    const width = start === 1 ? 1 : Math.min(TEMPLATE_LIST_PARALLEL, TEMPLATE_LIST_MAX_PAGES - start);
    const pages = Array.from({ length: width }, (_, i) => readPage(start + i));
    start += width;
    // 이름은 순서 없는 힌트라 실패한 페이지만 빼고 담는다. 짧은 페이지(그 이름까지)에서 끝내고, 실패가 있던 묶음 뒤로는 더 읽지 않는다.
    let failed = false;
    for (const result of await Promise.allSettled(pages)) {
      if (result.status === 'rejected') {
        failed = true;
        continue;
      }
      if (collect(result.value)) return names;
    }
    if (failed) return names;
  }
  return names;
}

/**
 * 번호 붙인 양식 이름으로 생성하고, 409 DUPLICATE_NAME 이면 다음 번호 이름으로 다시 시도한다(WD-824).
 * 양식 이름은 작업 이름에서 파생된 것이라 사용자가 정한 적이 없다 — 작업을 지워도 양식은 남아 있어 같은 이름으로 다시 만들면 겹친다.
 * WD-837: 첫 겹침 뒤 양식 이름(서버 중복 판정과 같은 범위: 본인 + 기본 양식, user_id NULL)을 한 번만 읽어(겹치지 않으면 읽지 않는다), 이미 있거나 이미 실패한 이름의 번호를 건너뛰고
 * 가장 작은 빈 번호로 시도한다. 생성 호출은 20번까지이며, 소진하면 사용자가 할 일을 안내한다. 다른 오류는 그대로 던진다.
 */
async function createWithNumberedTemplateName<T>(
  base: string,
  create: (name: string) => Promise<T>,
  loadExistingNames: () => Promise<string[]>,
): Promise<T> {
  let existing: Set<string> | null = null;
  let attempt = 1;
  for (let calls = 1; ; calls++) {
    try {
      return await create(numberedTemplateName(base, attempt));
    } catch (error) {
      if (!isDuplicateNameError(error)) throw error;
      if (calls >= TEMPLATE_NAME_MAX_ATTEMPTS) break;
      // 첫 겹침 뒤에 한 번만 읽는다.
      existing ??= new Set((await loadExistingNames()).map(normalizeName));
      // 방금 겹친 번호 다음부터 목록에 없는 가장 작은 번호 — 그 아래는 모두 목록에 있거나 이미 겹쳤다.
      // 낡은 목록에 없던 이름에 걸려도 같은 번호로 돌아가지 않고, 목록이 유한해 찾기는 늘 끝난다.
      let n = attempt + 1;
      while (existing.has(normalizeName(numberedTemplateName(base, n)))) n++;
      attempt = n;
    }
  }
  // 지울 양식을 찾을 수 있게 실제로 보낸 1회차 이름(200자 자르기 반영)을 넣는다 — 웹 goalTemplateName 과 같다.
  throw new Error(
    `자동 양식 이름을 정하지 못했습니다. 쓰지 않는 「${numberedTemplateName(base, 1)}」 양식을 지우거나 작업 이름을 바꾼 뒤 다시 만들어 주세요.`,
  );
}

export function createProactiveApi(client: AxiosInstance) {
  return {
    async listSmartJobs(): Promise<unknown> {
      const response = await client.get('/proactive/jobs');
      return response.data;
    },
    async createSmartJob(data: {
      name: string;
      prompt: string;
      cronExpression: string;
      timezone?: string;
      templateId?: number;
      channels?: string[];
      config?: Record<string, unknown>;
    }): Promise<unknown> {
      // 서버 CreateProactiveJobRequest.config는 @NotNull이므로 누락 시 400 발생.
      // MCP 도구 호출 측에서 config를 생략해도 빈 객체를 기본값으로 주입한다 (#244).
      const payload = { ...data, config: data.config ?? {} };
      const response = await client.post('/proactive/jobs', payload);
      return response.data;
    },
    async updateSmartJob(
      id: number,
      data: {
        name?: string;
        prompt?: string;
        cronExpression?: string;
        timezone?: string;
        templateId?: number;
        channels?: string[];
        enabled?: boolean;
        config?: Record<string, unknown>;
      },
    ): Promise<unknown> {
      // WD-192: 끄기만 하는 요청은 전용 끄기 API(proactive:read)로 보낸다 — PUT 은 proactive:write 가 필요해, 쓰기 권한을
      // 잃은 작성자가 채팅으로 "작업 꺼 줘"라고 하면 403 이 났다. 다른 필드가 함께 오면 편집이므로 PUT 그대로.
      const keys = Object.keys(data).filter((k) => data[k as keyof typeof data] !== undefined);
      if (keys.length === 1 && keys[0] === 'enabled' && data.enabled === false) {
        await client.post(`/proactive/jobs/${id}/disable`);
        return { id, enabled: false, success: true };
      }
      // UpdateProactiveJobRequest.config는 nullable이지만, MCP에서 명시적으로 전달된 경우만 보냄.
      const response = await client.put(`/proactive/jobs/${id}`, data);
      return response.data;
    },
    async deleteSmartJob(id: number): Promise<unknown> {
      await client.delete(`/proactive/jobs/${id}`);
      return { success: true };
    },
    async executeSmartJob(id: number): Promise<unknown> {
      const response = await client.post(`/proactive/jobs/${id}/execute`);
      return response.data;
    },
    // #632: 목록 조회 응답이 요약(섹션 개수만 포함, sections/style 제외)으로 축소됐다.
    // page/size로 결과 규모를 제어할 수 있다(기본: 서버 기본값 page=0/size=50).
    // 섹션 상세 구조가 필요하면 getReportTemplate(id)를 별도 호출해야 한다.
    async listReportTemplates(params?: { page?: number; size?: number }): Promise<unknown> {
      const response = await client.get('/proactive/templates', { params });
      return response.data;
    },
    async createReportTemplate(data: {
      name: string;
      description?: string;
      style?: string;
      structure: {
        sections: Array<{
          key: string;
          label: string;
          required?: boolean;
          type?: string;
          // #248: 섹션별 AI 작성 지침. 백엔드는 Map<String, Object>로 받으므로
          // 객체 전체를 그대로 forward하면 보존되지만, TS 타입에서 누락되면
          // 호출부에서 strip되거나 후속 리팩토링에서 의도치 않게 빠질 위험이 있다.
          instruction?: string;
        }>;
        output_format: string;
      };
    }): Promise<unknown> {
      // 백엔드 CreateReportTemplateRequest는 {name, description, sections, style}을 flat으로 받는다.
      // MCP 도구는 LLM 친화를 위해 structure 래퍼를 유지하지만, 전송 직전에 평탄화해야 400을 피한다. (#245)
      const payload = {
        name: data.name,
        description: data.description,
        sections: data.structure.sections,
        style: data.style,
      };
      const response = await client.post('/proactive/templates', payload);
      return response.data;
    },
    async createSmartJobWithTemplate(data: {
      name: string;
      prompt: string;
      cronExpression?: string;
      timezone?: string;
      channels?: string[];
      templateName: string;
      templateStructure: {
        sections: Array<{
          key: string;
          label: string;
          required?: boolean;
          type?: string;
          instruction?: string;
          children?: unknown[];
        }>;
        output_format: string;
      };
      templateStyle?: string;
    }): Promise<unknown> {
      // 1. Create template — 백엔드는 sections를 최상위로 받으므로 structure 래퍼를 평탄화한다. (#245)
      const template = await createWithNumberedTemplateName(data.templateName, (name) =>
        client.post('/proactive/templates', {
          name,
          description: `AI 자동 생성 — "${data.prompt}"`,
          style: data.templateStyle,
          sections: data.templateStructure.sections,
        }),
        () => loadTemplateNames(client),
      );
      const templateId = (template.data as { id: number }).id;
      // 2. Create smart job
      const jobPayload: Record<string, unknown> = {
        name: data.name,
        prompt: data.prompt,
        templateId,
        cronExpression: data.cronExpression ?? '0 9 * * *',
        timezone: data.timezone ?? 'Asia/Seoul',
        config: {
          channels: (data.channels ?? ['CHAT']).map((ch) => ({
            type: ch,
            recipientUserIds: [],
            recipientEmails: [],
          })),
        },
      };
      if (!data.cronExpression) jobPayload.enabled = false;
      // (#603) 템플릿 생성과 작업 생성은 서로 다른 백엔드 엔드포인트로 원자적 결합 불가(별도 트랜잭션).
      // 작업 생성이 실패하면(예: 미지원 채널 400) 방금 만든 템플릿이 고아로 남으므로,
      // 애플리케이션 레벨 보상 트랜잭션으로 방금 생성한 템플릿을 롤백(삭제)한다.
      try {
        const job = await client.post('/proactive/jobs', jobPayload);
        return { template: template.data, job: job.data };
      } catch (error) {
        await client.delete(`/proactive/templates/${templateId}`).catch(() => {
          // 롤백 삭제 실패는 원래 에러를 가리지 않도록 무시한다. 고아 템플릿이 남을 수 있으나
          // 원인(작업 생성 실패)은 그대로 상위로 전파되어 사용자/모델이 알 수 있다.
        });
        throw error;
      }
    },

    /**
     * 리포트 템플릿 단건 조회
     * @param id 조회할 템플릿 ID
     */
    async getReportTemplate(id: number): Promise<unknown> {
      const response = await client.get(`/proactive/templates/${id}`);
      return response.data;
    },

    /**
     * 리포트 템플릿 수정
     * @param id 수정할 템플릿 ID
     * @param data 수정할 필드 (모두 선택적)
     */
    async updateReportTemplate(
      id: number,
      data: {
        name?: string;
        description?: string;
        style?: string;
        structure?: {
          sections?: Array<{
            key: string;
            label: string;
            required?: boolean;
            type?: string;
            // #248: 섹션별 AI 작성 지침 (create와 동일 사유)
            instruction?: string;
          }>;
          output_format?: string;
        };
      },
    ): Promise<unknown> {
      // 백엔드 UpdateReportTemplateRequest도 flat 구조({name, description, sections, style})를 요구한다. (#245)
      const payload: Record<string, unknown> = {
        name: data.name,
        description: data.description,
        style: data.style,
      };
      if (data.structure?.sections !== undefined) {
        payload.sections = data.structure.sections;
      }
      const response = await client.put(`/proactive/templates/${id}`, payload);
      return response.data;
    },

    /**
     * 리포트 템플릿 삭제
     * @param id 삭제할 템플릿 ID
     */
    async deleteReportTemplate(id: number): Promise<unknown> {
      await client.delete(`/proactive/templates/${id}`);
      return { success: true };
    },

    /**
     * 스마트 잡 실행 이력 목록 조회
     * @param jobId 잡 ID
     * @param params 페이지네이션 파라미터 (limit, offset)
     */
    async listJobExecutions(
      jobId: number,
      params?: { limit?: number; offset?: number },
    ): Promise<unknown> {
      const response = await client.get(`/proactive/jobs/${jobId}/executions`, { params });
      return response.data;
    },

    /**
     * 스마트 잡 단건 실행 이력 조회
     * @param jobId 잡 ID
     * @param executionId 실행 이력 ID
     */
    async getExecution(jobId: number, executionId: number): Promise<unknown> {
      const response = await client.get(`/proactive/jobs/${jobId}/executions/${executionId}`);
      return response.data;
    },
  };
}
