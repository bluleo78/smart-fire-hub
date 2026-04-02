export interface ClassifiedError {
  type: 'ai' | 'data' | 'channel' | 'unknown';
  icon: string;
  label: string;
  guide: string;
}

interface ErrorPattern extends ClassifiedError {
  keywords: string[];
}

const ERROR_PATTERNS: ErrorPattern[] = [
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

const UNKNOWN_NO_MESSAGE: ClassifiedError = { type: 'unknown', icon: '⚪', label: '알 수 없는 오류', guide: '관리자에게 문의해주세요.' };
const UNKNOWN_FALLBACK: ClassifiedError = { type: 'unknown', icon: '⚪', label: '기타 오류', guide: '관리자에게 문의해주세요.' };

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
