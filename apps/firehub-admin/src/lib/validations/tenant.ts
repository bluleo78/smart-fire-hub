import { z } from 'zod';

/** 저장소 입력 패턴. 서버 `DataStoreSetupRequest` 의 @Pattern 과 같은 정규식이다(JDBC URL·DDL 에 들어가는 값). */
const HOST_PATTERN = /^[A-Za-z0-9.-]{1,255}$/;
const SID_PATTERN = /^[A-Za-z0-9_$#.-]{1,128}$/;
const TABLESPACE_PATTERN = /^[A-Za-z][A-Za-z0-9_$#]{0,29}$/;
/** 포트는 숫자만 — `Number()` 는 `1e3`·`0x1F`·공백 섞인 값도 숫자로 바꾸므로 먼저 모양을 본다. */
const PORT_PATTERN = /^\d+$/;

/**
 * 저장소 선택 입력 검증.
 *
 * **서버 `DataStoreSetupRequest` 의 검증과 같아야 한다**(Global Constraints, WD-73):
 * host `^[A-Za-z0-9.-]{1,255}$`, port 1–65535, database(SID) `^[A-Za-z0-9_$#.-]{1,128}$`,
 * tablespace(선택) `^[A-Za-z][A-Za-z0-9_$#]{0,29}$`. 어긋나면 클라이언트가 통과시킨 값이 서버 400 이 된다.
 *
 * 판별 유니온(backend → mode 중첩) 대신 평평한 객체 + `superRefine` 을 쓴다 — react-hook-form 은
 * 입력 필드가 전부 같은 형태의 값 객체(`dataStore.host` …)를 가져야 하고, 백엔드·모드를 바꿔도 이미 입력한
 * 값이 사라지지 않아야 하기 때문이다. 교차 필드 규칙(Tibero 만 필수, DBA_AUTO 만 DBA 계정 필수)은
 * superRefine 이 오류를 **해당 필드 경로**에 붙여 메시지가 알맞은 입력 아래에 뜬다.
 * port 는 입력 위젯이 문자열이라 문자열로 받고, 제출 시 숫자로 바꾼다.
 */
export const dataStoreSchema = z
  .object({
    backend: z.enum(['POSTGRESQL', 'TIBERO', 'MYSQL']),
    host: z.string(),
    port: z.string(),
    database: z.string(),
    tablespace: z.string(),
    mode: z.enum(['DBA_AUTO', 'SCRIPT']),
    dbaUsername: z.string(),
    dbaPassword: z.string(),
  })
  .superRefine((v, ctx) => {
    // PostgreSQL(기본)은 추가 입력이 없다 — 남아 있는 값은 무시하고 제출 때 dataStore 자체를 뺀다.
    if (v.backend !== 'TIBERO') return;
    const fail = (path: string, message: string) => ctx.addIssue({ code: 'custom', path: [path], message });
    if (!HOST_PATTERN.test(v.host.trim())) fail('host', '호스트를 올바르게 입력하세요.');
    const portText = v.port.trim();
    const port = Number(portText);
    if (!PORT_PATTERN.test(portText) || port < 1 || port > 65535) {
      fail('port', '포트는 1~65535 범위여야 합니다.');
    }
    if (!SID_PATTERN.test(v.database.trim())) fail('database', 'SID 를 올바르게 입력하세요.');
    // 비우면 서버가 USR 을 쓴다 — 입력했다면 규칙을 지켜야 한다.
    if (v.tablespace.trim() !== '' && !TABLESPACE_PATTERN.test(v.tablespace.trim())) {
      fail('tablespace', '테이블스페이스를 올바르게 입력하세요.');
    }
    if (v.mode === 'DBA_AUTO') {
      if (v.dbaUsername.trim() === '') fail('dbaUsername', 'DBA 계정을 입력하세요.');
      if (v.dbaPassword === '') fail('dbaPassword', 'DBA 비밀번호를 입력하세요.');
    }
  });

export type DataStoreFormData = z.infer<typeof dataStoreSchema>;

/**
 * 테넌트 생성 폼 검증. 경계는 서버 `CreateTenantRequest` 의 Bean Validation 과 **같아야** 한다
 * (@Size(max=64) slug / @Size(max=255) name / @Pattern(^[a-z0-9][a-z0-9-]*$)).
 * 어긋나면 클라이언트가 통과시킨 값이 서버 400 으로 떨어져 사용자가 이유 없이 막힌다.
 */
export const createTenantSchema = z.object({
  // 공백만 넣은 이름은 서버 @NotBlank 가 400 으로 거부한다 — 앞뒤 공백을 잘라 여기서 먼저 막는다(WD-311, 계정 생성 스키마와 같음).
  name: z.string().trim().min(1, '이름을 입력하세요.').max(255, '이름은 255자 이하여야 합니다.'),
  slug: z
    .string()
    .min(1, '식별자는 소문자·숫자·하이픈만 사용할 수 있으며 소문자 또는 숫자로 시작해야 합니다.')
    .max(64, '식별자는 64자 이하여야 합니다.')
    .regex(
      /^[a-z0-9][a-z0-9-]*$/,
      '식별자는 소문자·숫자·하이픈만 사용할 수 있으며 소문자 또는 숫자로 시작해야 합니다.',
    ),
  /**
   * 초기 Owner 는 **폼 필드다**. 입력 위젯이 텍스트 박스가 아니라 픽커일 뿐이다.
   * 별도 state + 제출 시 수동 검사로 두면 검증 권위가 zod 와 컴포넌트 둘로 갈리고,
   * `handleSubmit` 은 zod 가 실패하면 콜백을 아예 부르지 않으므로 **세 필드를 모두 비운
   * 사용자에게는 Owner 안내가 영영 뜨지 않는다**. 여기 올려서 zod 를 유일한 권위로 만든다.
   */
  ownerUserId: z.number({ error: 'Owner 사용자를 선택하세요.' }),
  dataStore: dataStoreSchema,
});

export type CreateTenantFormData = z.infer<typeof createTenantSchema>;
