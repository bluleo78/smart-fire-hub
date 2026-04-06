/**
 * AI 인사이트 도메인 모킹 데이터 팩토리
 * src/api/proactive.ts 타입 기반으로 테스트용 객체를 생성한다.
 * overrides 파라미터로 특정 필드만 덮어쓸 수 있다.
 */

import type {
  ProactiveJob,
  ProactiveJobExecution,
  ProactiveMessage,
  ReportTemplate,
  TemplateSection,
} from '@/api/proactive';

/** 템플릿 섹션(TemplateSection) 객체 생성 */
export function createTemplateSection(overrides?: Partial<TemplateSection>): TemplateSection {
  return {
    key: 'summary',
    type: 'text',
    label: '요약',
    description: '전체 상황 요약',
    instruction: '현재 상황을 간략하게 요약해주세요.',
    required: true,
    static: false,
    ...overrides,
  };
}

/** 리포트 템플릿(ReportTemplate) 객체 생성 */
export function createTemplate(overrides?: Partial<ReportTemplate>): ReportTemplate {
  return {
    id: 1,
    name: '기본 리포트 템플릿',
    description: '기본 분석 리포트 템플릿',
    sections: [
      createTemplateSection(),
      createTemplateSection({
        key: 'details',
        type: 'list',
        label: '상세 내용',
        instruction: '주요 항목을 목록으로 나열해주세요.',
      }),
    ],
    style: null,
    builtin: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

/** 프로액티브 잡(ProactiveJob) 객체 생성 */
export function createJob(overrides?: Partial<ProactiveJob>): ProactiveJob {
  return {
    id: 1,
    userId: 1,
    templateId: 1,
    templateName: '기본 리포트 템플릿',
    name: '매일 현황 리포트',
    prompt: '오늘의 데이터 현황을 분석하고 인사이트를 제공해주세요.',
    cronExpression: '0 9 * * *',
    timezone: 'Asia/Seoul',
    enabled: true,
    triggerType: 'SCHEDULE',
    config: {},
    lastExecutedAt: '2024-01-01T09:00:00Z',
    nextExecuteAt: '2024-01-02T09:00:00Z',
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    lastExecution: null,
    ...overrides,
  };
}

/** 프로액티브 잡 실행 결과(ProactiveJobExecution) 객체 생성 */
export function createJobExecution(overrides?: Partial<ProactiveJobExecution>): ProactiveJobExecution {
  return {
    id: 1,
    jobId: 1,
    status: 'COMPLETED',
    result: { summary: '분석이 완료되었습니다.' },
    deliveredChannels: ['email'],
    errorMessage: null,
    startedAt: '2024-01-01T09:00:00Z',
    completedAt: '2024-01-01T09:01:00Z',
    ...overrides,
  };
}

/** 프로액티브 메시지(ProactiveMessage) 객체 생성 */
export function createMessage(overrides?: Partial<ProactiveMessage>): ProactiveMessage {
  return {
    id: 1,
    userId: 1,
    executionId: 1,
    jobName: '매일 현황 리포트',
    title: '2024년 1월 1일 현황 리포트',
    content: { summary: '오늘의 데이터 분석 결과입니다.' },
    messageType: 'REPORT',
    read: false,
    createdAt: '2024-01-01T09:01:00Z',
    ...overrides,
  };
}

/** ProactiveJob 여러 개를 한 번에 생성 */
export function createJobs(count: number): ProactiveJob[] {
  return Array.from({ length: count }, (_, i) =>
    createJob({
      id: i + 1,
      name: `잡 ${i + 1}`,
    }),
  );
}

/** 기본 리포트 템플릿 목록 2개 생성 */
export function createTemplates(): ReportTemplate[] {
  return [
    createTemplate({ id: 1, name: '일일 현황 리포트', builtin: true }),
    createTemplate({
      id: 2,
      name: '주간 통계 리포트',
      builtin: false,
      sections: [
        createTemplateSection({ key: 'weekly_summary', label: '주간 요약' }),
        createTemplateSection({ key: 'trends', type: 'chart', label: '트렌드 차트' }),
      ],
    }),
  ];
}
