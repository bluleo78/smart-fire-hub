import { z } from 'zod';

import type { TemplateSection } from '@/api/proactive';
import { cloneName } from '@/lib/clone-name';
import { cronTooFrequentMessage } from '@/lib/cron-frequency';

/** 분석 프롬프트 길이 상한(자) — 서버 CreateProactiveJobRequest.PROMPT_MAX_LENGTH 와 같다(WD-425). */
export const MAX_JOB_PROMPT_LENGTH = 10_000;

export const channelConfigSchema = z.object({
  // WD-994: 서버가 받는 WEBHOOK 채널(API 로 설정)을 편집 중에도 잃지 않도록 enum 에 둔다.
  type: z.enum(['CHAT', 'EMAIL', 'KAKAO', 'SLACK', 'WEBHOOK']),
  // WD-994: 웹훅 주소 — 화면에서 편집하지 않고 받은 값을 그대로 다시 보낸다.
  // WD-994 리뷰 후속: 서버가 url:null 로 준 웹훅도 이름만 고쳐 저장되도록 null 을 받는다(거절하면 저장이 조용히 막힘).
  url: z.string().nullish(),
  recipientUserIds: z.array(z.number()),
  recipientEmails: z.array(z.string().email('올바른 이메일 형식이 아닙니다')),
  attachPdf: z.boolean().optional(),
  // WD-994: 웹훅 채널에는 화면이 모르는 키(secret 등)가 있을 수 있어 저장 때 지우지 않고 그대로 통과시킨다.
}).passthrough();

/** 전달 채널 타입별 화면 라벨 (WD-994) — 읽기 화면·목록·편집기가 같은 이름을 쓰도록 한곳에 둔다. */
export const CHANNEL_TYPE_LABELS: Record<string, string> = {
  CHAT: '채팅',
  EMAIL: '이메일',
  KAKAO: '카카오 알림톡',
  SLACK: 'Slack',
  WEBHOOK: '웹훅',
};

/** 라벨 조회 — 모르는 타입은 받은 문자열 그대로, undefined 는 빈 문자열. */
export function channelTypeLabel(type: string | undefined): string {
  return (type !== undefined && Object.hasOwn(CHANNEL_TYPE_LABELS, type) ? CHANNEL_TYPE_LABELS[type] : type) ?? '';
}

/**
 * 서버가 받지 않아 켤 수 없는 전달 채널 (WD-805).
 * 서버 화이트리스트(CHAT/EMAIL/WEBHOOK, #594)가 Slack·카카오를 400 으로 거절하고,
 * 기본 발송 경로에 Slack·카카오 발송기 구현이 없기 때문이다. 편집기(켜기 막기)와 스키마(저장 막기)가 같이 쓴다.
 * 나중에 서버가 지원하면 이 목록에서 빼면 된다.
 */
export const UNSUPPORTED_DELIVERY_CHANNELS: ReadonlySet<z.infer<typeof channelConfigSchema>['type']> =
  new Set(['KAKAO', 'SLACK']);

/** 예전 설정에 미지원 채널이 남은 채 저장하려 할 때 보이는 문구 (WD-805) */
export const UNSUPPORTED_CHANNEL_SAVE_MESSAGE =
  '이 채널은 아직 지원하지 않아 저장할 수 없습니다 — 체크를 해제하세요.';

export const anomalyMetricConfigSchema = z.object({
  id: z.string(),
  name: z.string().min(1, '메트릭 이름을 입력하세요'),
  source: z.enum(['system', 'dataset']),
  metricKey: z.string().optional(),
  datasetId: z.number().optional(),
  query: z.string().optional(),
  pollingInterval: z.number().min(60, '최소 60초 이상이어야 합니다'),
});

export const anomalyConfigSchema = z.object({
  enabled: z.boolean(),
  metrics: z.array(anomalyMetricConfigSchema),
  sensitivity: z.enum(['low', 'medium', 'high']),
  cooldownMinutes: z.number().min(1, '최소 1분 이상이어야 합니다'),
});

/** 실행 주기가 비었을 때의 안내 문구 — 스키마와 폼 칸 아래 안내가 같은 문구를 쓴다. */
export const CRON_REQUIRED_MESSAGE = '실행 주기를 설정하세요';

export const proactiveJobSchema = z
  .object({
    // trim — 공백만 채운 이름을 빈 값과 같이 거부하고, 앞뒤 공백은 잘라 저장한다(WD-500, user.ts WD-275 와 같은 패턴).
    name: z.string().trim().min(1, '작업 이름을 입력하세요').max(200, '이름은 200자 이내여야 합니다'),
    // 상한은 서버 CreateProactiveJobRequest.PROMPT_MAX_LENGTH 와 같다(WD-425). 입력란 maxLength 는 타이핑만 막으므로, 리포트에서
    // 프리필한 긴 질문처럼 코드로 채운 값도 저장 전에 여기서 잡는다.
    // trim — 공백·탭·줄바꿈만 있는 프롬프트는 빈 값으로 보고 거부, 앞뒤 공백은 잘라 저장한다(WD-500).
    prompt: z
      .string()
      .trim()
      .min(1, '분석 프롬프트를 입력하세요')
      .max(MAX_JOB_PROMPT_LENGTH, '분석 프롬프트는 10,000자 이내여야 합니다'),
    templateId: z.number().nullable().optional(),
    // 「목표 기반」으로 만든 섹션 구성(WD-297). 서버로 보내는 작업 필드가 아니라, 생성 때 리포트 양식으로 먼저 저장해 templateId 로
    // 연결하려고 폼에 둔다. null 이면 목표 기반 구성이 없다.
    generatedSections: z.array(z.custom<TemplateSection>()).nullable().optional(),
    cronExpression: z.string().optional(),
    timezone: z.string().min(1),
    triggerType: z.enum(['SCHEDULE', 'ANOMALY', 'BOTH']).optional(),
    config: z.object({
      // 미지원 채널(Slack·카카오)이 남아 있으면 서버 400 대신 저장 전에 막는다(WD-805)
      channels: z
        .array(channelConfigSchema)
        .refine((chs) => !chs.some((c) => UNSUPPORTED_DELIVERY_CHANNELS.has(c.type)), {
          message: UNSUPPORTED_CHANNEL_SAVE_MESSAGE,
        }),
      anomaly: anomalyConfigSchema.optional(),
    }),
  })
  .superRefine((data, ctx) => {
    // ANOMALY 전용이 아닌 경우 cronExpression 필수 (#38/#43)
    if (data.triggerType !== 'ANOMALY' && !data.cronExpression) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: CRON_REQUIRED_MESSAGE,
        path: ['cronExpression'],
      });
    }
    // 1분보다 잦은 주기(매초 등)는 서버가 거부한다 — 저장 전에 같은 문구로 막는다(WD-366)
    const tooFrequent = data.triggerType !== 'ANOMALY' ? cronTooFrequentMessage(data.cronExpression) : undefined;
    if (tooFrequent) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message: tooFrequent, path: ['cronExpression'] });
    }
  });

/** 리포트 양식 이름 한도(report_template.name VARCHAR(200)). */
const TEMPLATE_NAME_MAX = 200;
const GOAL_TEMPLATE_SUFFIX = ' 양식';

/**
 * 목표 기반 작업이 함께 저장하는 리포트 양식 이름(WD-297) — 「<작업 이름> 양식」. 화면 미리보기용이다.
 * 저장 1회차 이름(numberedGoalTemplateName(.., 1))과 글자 하나까지 같아야 한다 — 따로 자르면(slice) 긴 이름에서
 * 서로게이트 쌍·끝 공백 처리가 달라, 미리 본 이름과 실제 저장되는 이름이 어긋난다(WD-834). 그래서 같은 함수를 쓴다.
 */
export function goalTemplateName(jobName: string): string {
  return numberedGoalTemplateName(jobName, 1);
}

/**
 * 자동 양식 이름의 번호 후보(WD-824) — 1번째는 「<작업> 양식」, 2번째부터 「<작업> 양식 (2)」 …
 * 번호를 붙인 이름 전체를 다시 자르면 긴 작업 이름에서 「 양식」 꼬리가 사라지므로, 자르기는 작업 이름 쪽에만 적용하고
 * 꼬리(「 양식」+번호)는 항상 남긴다. 자르는 자리의 공백은 지우고 서로게이트 쌍은 가르지 않는다.
 */
export function numberedGoalTemplateName(jobName: string, attempt: number): string {
  const tail = attempt <= 1 ? GOAL_TEMPLATE_SUFFIX : `${GOAL_TEMPLATE_SUFFIX} (${attempt})`;
  // cloneName 은 「이름 + 구분자 + 접미사」를 만들고 자르는 자리(UTF-16·서로게이트·공백)를 한곳에서 처리한다 — 꼬리의 맨 앞 공백을 구분자로 넘긴다.
  return cloneName(jobName.trim(), tail.slice(1), TEMPLATE_NAME_MAX);
}

export type ProactiveJobFormValues = z.infer<typeof proactiveJobSchema>;
export type ChannelConfigValues = z.infer<typeof channelConfigSchema>;
export type AnomalyMetricConfigValues = z.infer<typeof anomalyMetricConfigSchema>;
export type AnomalyConfigValues = z.infer<typeof anomalyConfigSchema>;
