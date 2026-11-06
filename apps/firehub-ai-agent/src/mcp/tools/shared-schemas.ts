import { z } from 'zod/v4';

export const canvasSchema = z.object({
  width: z.enum(['full', 'half', 'third']),
  height: z.enum(['full', 'half', 'third']),
  page: z.enum(['current', 'new']).optional().default('current'),
  pageLabel: z.string().optional(),
  replace: z.string().optional(),
}).optional().describe('Canvas layout hint for native mode');

/**
 * 자산 설명 길이 상한 — 서버 DescriptionLimits.MAX_LENGTH 와 같은 값(WD-520).
 * 서버는 2,000자를 넘는 설명을 400 으로 거부한다. 도구 입력 단계에서 먼저 막아, 모델이 대화 중간의 400 대신
 * 어느 칸이 왜 틀렸는지 분명한 검증 오류를 받고 설명을 줄여 다시 호출하게 한다.
 */
export const MAX_DESCRIPTION_LENGTH = 2000;

/**
 * 선택 입력인 자산 설명 파라미터(WD-520). {@link MAX_DESCRIPTION_LENGTH} 상한과 한국어 안내를 붙이고,
 * 모델이 미리 알 수 있도록 설명(describe)에도 상한을 적는다.
 *
 * @param asset 안내 문구에 들어갈 자산 이름(예: '쿼리', '데이터셋')
 */
export function descriptionParam(asset: string) {
  return z
    .string()
    .max(MAX_DESCRIPTION_LENGTH, `${asset} 설명은 최대 2,000자까지 입력할 수 있습니다.`)
    .optional()
    .describe(`${asset} 설명 (최대 2,000자)`);
}
