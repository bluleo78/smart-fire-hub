import { z } from 'zod';

/**
 * 테넌트 생성 폼 검증. 경계는 서버 `CreateTenantRequest` 의 Bean Validation 과 **같아야** 한다
 * (@Size(max=64) slug / @Size(max=255) name / @Pattern(^[a-z0-9][a-z0-9-]*$)).
 * 어긋나면 클라이언트가 통과시킨 값이 서버 400 으로 떨어져 사용자가 이유 없이 막힌다.
 */
export const createTenantSchema = z.object({
  name: z.string().min(1, '이름을 입력하세요.').max(255, '이름은 255자 이하여야 합니다.'),
  slug: z
    .string()
    .min(1, 'slug 는 소문자·숫자·하이픈만 사용할 수 있으며 소문자 또는 숫자로 시작해야 합니다.')
    .max(64, 'slug 는 64자 이하여야 합니다.')
    .regex(
      /^[a-z0-9][a-z0-9-]*$/,
      'slug 는 소문자·숫자·하이픈만 사용할 수 있으며 소문자 또는 숫자로 시작해야 합니다.',
    ),
  /**
   * 초기 Owner 는 **폼 필드다**. 입력 위젯이 텍스트 박스가 아니라 픽커일 뿐이다.
   * 별도 state + 제출 시 수동 검사로 두면 검증 권위가 zod 와 컴포넌트 둘로 갈리고,
   * `handleSubmit` 은 zod 가 실패하면 콜백을 아예 부르지 않으므로 **세 필드를 모두 비운
   * 사용자에게는 Owner 안내가 영영 뜨지 않는다**. 여기 올려서 zod 를 유일한 권위로 만든다.
   */
  ownerUserId: z.number({ error: 'Owner 사용자를 선택하세요.' }),
});

export type CreateTenantFormData = z.infer<typeof createTenantSchema>;
