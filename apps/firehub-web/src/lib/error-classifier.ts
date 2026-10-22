/**
 * 스마트 작업 실행 실패 사유 분류기 — 서버가 남긴 실패 사유(errorMessage)를 보고 제목·조언을 고른다.
 *
 * 서버(firehub-api)는 실행 이력에 사유 문구만 남기고 범주 코드는 주지 않는다. 그래서 여기서 문구로 범주를 판단한다.
 * 예전에는 영어 키워드만 봤는데 서버 사유가 한국어로 바뀌면서, 「AI API 키 또는 OAuth 토큰이 설정되지 않았습니다」가
 * 'api'·'token' 에 걸려 「잠시 후 수동 실행」(다시 실행해도 안 고쳐짐)으로, 의도된 건너뜀이 「기타 오류」로 보였다 (WD-191).
 *
 * 아래 한국어 규칙은 **서버가 실제로 남기는 문구**에서 뽑은 고정 앵커다(느슨한 단어 금지 — 예: 「AI 에이전트 호출 실패」 문구에는
 * 「잠시 후 다시 시도」와 「AI 설정을 확인」이 함께 들어 있어, 「설정」 같은 넓은 단어로 잡으면 범주가 엇갈린다).
 * 서버 문구를 바꾸면 이 파일과 테스트(error-classifier.test.ts)의 문구 목록을 함께 고쳐야 한다. 출처:
 * - 건너뜀: ProactiveJobAsyncRunner.REASON_TENANT_SUSPENDED, ProactiveOwnerEligibility.REASON_OWNER_*
 * - AI 인증·설정: AiCredential.*.incompleteMessage()/modelProblem(), UnknownAgentTypeException,
 *   AiCredentialDocument.parse(), EncryptionService(복호화 실패), ProactiveResultValidator.USER_FACING_FAILURE_MESSAGE
 * - AI 호출 실패: ProactiveAiClient.AGENT_CALL_FAILED_MESSAGE
 * ai-agent 가 돌려준 오류 원문은 실행 이력에 저장되지 않는다 — ProactiveAiClient 가 위 두 문구 중 하나로 바꿔 남긴다.
 */
export interface ClassifiedError {
  /**
   * 범주.
   * - skipped: 오류가 아니라 실행 조건이 안 맞아 의도적으로 건너뜀(작업 주인 정지·권한 없음·워크스페이스 정지)
   * - ai-config: AI 인증 정보·설정이 없거나 잘못됨 — 다시 실행해도 안 고쳐지고 설정을 고쳐야 한다
   * - ai-transient: AI 에이전트 호출 자체가 실패(네트워크·시간 초과·일시 장애) — 잠시 후 재실행이 우선
   * - ai/data/channel: 예전 영어 사유(기존 실행 이력 행)를 위한 범주
   */
  type: 'skipped' | 'ai-config' | 'ai-transient' | 'ai' | 'data' | 'channel' | 'unknown';
  icon: string;
  label: string;
  guide: string;
}

interface ErrorPattern extends ClassifiedError {
  /** 부분일치 앵커 — 비교 전에 사유와 함께 소문자로 바꾼다(한국어는 영향 없음). */
  keywords: string[];
}

/** 관리 화면의 AI 인증 정보 위치 — 실제 메뉴(관리 > 설정)와 탭 이름(「AI 에이전트」)을 그대로 쓴다. */
const AI_SETTINGS_PATH = '관리 > 설정 > AI 에이전트';

/** 건너뜀 분류 — 서버가 실행 상태로 SKIPPED 를 줄 때도 같은 표시를 쓴다. */
const SKIPPED: ClassifiedError = {
  type: 'skipped',
  icon: '⏭️',
  label: '건너뜀',
  // 사유 원문이 없을 수도 있어(SKIPPED 상태) 「위 사유」 대신 조건을 직접 말한다. 정지·권한 회수는 작업 활성·스케줄을 건드리지 않으므로
  // 예약 작업은 사유가 풀리면 다음 예약부터 저절로 돈다(WD-166·WD-174) — 비활성 작업의 「지금 실행」은 다시 눌러야 하므로 「예약된 작업」으로 한정한다.
  guide:
    '오류가 아닙니다. 실행 조건(작업 주인·워크스페이스 상태·권한)이 맞지 않아 이번 실행을 건너뛰었습니다. 사유가 해소되면 예약된 작업은 설정을 바꾸지 않아도 다음 예약 실행부터 다시 실행됩니다.',
};

// 순서가 곧 우선순위다. 한국어 앵커(건너뜀 → AI 인증·설정 → AI 호출 실패)를 영어 규칙보다 먼저 본다 —
// 한국어 문구 안에 'API'·'OAuth'·'Claude'·모델 id(예: anthropic/claude-…) 같은 영어가 섞여 있어, 영어 규칙이 먼저면 다시 잘못 분류된다.
const ERROR_PATTERNS: ErrorPattern[] = [
  {
    ...SKIPPED,
    // 세 건너뜀 사유가 모두 「… 실행하지 않았습니다」로 끝난다(워크스페이스 정지·작업 주인 정지/제거·작업 주인 권한 없음).
    keywords: ['실행하지 않았습니다'],
  },
  {
    type: 'ai-config',
    keywords: [
      'ai api 키', // sdk·cli-api 미설정
      'oauth 토큰이 설정되지 않았습니다', // cli 미설정
      'ai 공급자 설정', // opencode 공급자/기본 URL 미완성
      'opencode 형식', // opencode 모델 형식 불일치
      'ai 자격증명 유형', // 알 수 없는 자격증명 유형
      'ai 인증 정보', // 인증·쿼터 실패, 결과가 비었거나 에이전트 오류문뿐인 경우
      'ai.credential', // 저장된 AI 설정 JSON 손상
      // 프로액티브 실행 경로에서 복호화하는 값은 AI 자격증명뿐이다 — 손상된 암호문이면 다시 저장해야 고쳐진다.
      'decryption failed',
      'invalid encrypted format',
    ],
    icon: '🔴',
    label: 'AI 인증 정보 확인 필요',
    guide: `${AI_SETTINGS_PATH} 탭에서 AI 인증 정보(API 키·토큰·모델)를 등록하거나 확인하세요. 설정을 고치기 전에는 다시 실행해도 같은 오류가 납니다.`,
  },
  {
    type: 'ai-transient',
    keywords: ['ai 에이전트 호출에 실패'],
    icon: '🟠',
    label: 'AI 에이전트 호출 실패',
    guide: `일시적인 문제일 수 있습니다. 잠시 후 다시 실행해 보고, 계속 실패하면 ${AI_SETTINGS_PATH} 탭에서 AI 인증 정보를 확인하세요.`,
  },
  // ── 아래는 예전 영어 사유용 규칙(기존 실행 이력 행). 순서·키워드를 바꾸지 않는다. ──
  {
    type: 'ai',
    keywords: ['rate limit', 'token', 'claude', 'api', 'overloaded', 'anthropic', 'model'],
    icon: '🔴',
    label: 'AI 모델 오류',
    guide: '잠시 후 수동 실행을 시도하거나, 스케줄 간격을 늘려보세요.',
  },
  {
    type: 'data',
    keywords: ['connection', 'timeout', 'database', 'query', 'sql', 'datasource'],
    icon: '🟠',
    label: '데이터 접근 실패',
    guide: '데이터 연결 상태를 확인해주세요.',
  },
  {
    type: 'channel',
    keywords: ['email', 'smtp', 'delivery', 'channel', 'mail'],
    icon: '🟡',
    label: '채널 전달 실패',
    guide: '채널 설정(이메일/SMTP)을 확인해주세요.',
  },
];

// 미분류 폴백은 흰색(⚪) 대신 검은색(⚫)을 사용한다 (#525).
// destructive 카드(연한 빨강 배경)에서 흰색 원은 시각적으로 거의 안 보이고,
// "심각도가 낮다"는 오인을 주는 역설적 신호였다.
const UNKNOWN_NO_MESSAGE: ClassifiedError = { type: 'unknown', icon: '⚫', label: '알 수 없는 오류', guide: '관리자에게 문의해주세요.' };
const UNKNOWN_FALLBACK: ClassifiedError = { type: 'unknown', icon: '⚫', label: '기타 오류', guide: '관리자에게 문의해주세요.' };

/** 실패 사유 문구로 범주를 고른다. 앞선 규칙이 우선한다. */
export function classifyError(errorMessage: string | null | undefined): ClassifiedError {
  if (!errorMessage) return UNKNOWN_NO_MESSAGE;

  const lower = errorMessage.toLowerCase();

  for (const pattern of ERROR_PATTERNS) {
    if (pattern.keywords.some((kw) => lower.includes(kw))) {
      return pattern;
    }
  }

  return UNKNOWN_FALLBACK;
}

/**
 * 실행 상태와 사유를 함께 보고 결과 카드 분류를 고른다. 카드를 그리지 않는 상태(실행 중·완료 등)면 null.
 *
 * 서버가 상태값으로 건너뜀을 알려 주면(SKIPPED) 문구보다 그 값을 우선한다. 지금 서버는 건너뜀도 FAILED 로 남기므로
 * (ProactiveJobExecutionRepository.updateError 가 상태를 FAILED 로 고정) FAILED 일 때는 문구로 건너뜀을 가려낸다.
 */
export function classifyExecutionOutcome(
  status: string,
  errorMessage: string | null | undefined,
): ClassifiedError | null {
  if (status === 'SKIPPED') return SKIPPED;
  if (status === 'FAILED') return classifyError(errorMessage);
  return null;
}

/**
 * 화면에 보일 실행 상태 — 의도된 건너뜀이면 FAILED 대신 SKIPPED 를 돌려준다 (WD-191).
 *
 * 실행 상세·실행 이력 탭·작업 개요 탭·작업 목록이 모두 이 값으로 뱃지·색·문구를 고른다. 한 화면만 「건너뜀」이고 다른 화면이 같은
 * 실행을 「실패」(빨강)로 보이면 사용자가 헷갈리므로, 판정은 classifyExecutionOutcome 하나에 맡기고 여기서 상태 문자열로 바꿔 준다.
 * getStatusLabel/getStatusBadgeVariant/getExecutionStatusType 는 이미 SKIPPED 를 「건너뜀」·회색으로 매핑한다.
 */
export function getExecutionDisplayStatus(status: string, errorMessage: string | null | undefined): string {
  return classifyExecutionOutcome(status, errorMessage)?.type === 'skipped' ? 'SKIPPED' : status;
}
