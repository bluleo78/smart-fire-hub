import { isDuplicateName } from './api-error';

/**
 * 「복제」로 만드는 사본 이름(WD-430).
 *
 * 저장 쿼리·스마트 작업·리포트 양식은 이름 한도가 200자(DB varchar(200))인데, 복제는 원래 이름 뒤에 「 (복사본)」 같은 접미사를 붙여
 * 194자를 넘는 이름은 복제할 수 없었다 — 사용자는 이름을 쓴 적이 없는데 「200자 이내」·「예기치 않은 오류」로 실패했다. 접미사가 들어가도록
 * 원래 이름 끝을 잘라 한도 안에 맞춘다.
 *
 * 글자 수는 UTF-16 단위(String.length)로 센다(WD-699). 웹 zod `.max()` 와 서버 `@Size` 가 모두 UTF-16 으로 세므로, 코드 포인트로 자르면
 * 이모지가 든 이름의 기본 사본 이름이 손대지 않았는데도 검증에 걸렸다. DB VARCHAR 는 코드 포인트로 세지만 UTF-16 길이 ≤ N 이면
 * 코드 포인트 수도 ≤ N 이라 늘 들어간다(더 엄격한 쪽에 맞춤). 자르는 자리에 서로게이트 쌍이 걸리면 쌍을 통째로 빼 깨진 글자가 남지 않게 한다.
 */
export const NAME_MAX_LENGTH = 200;

/** 문자열을 UTF-16 `maxUnits` 단위 안으로 자른다 — 끝이 서로게이트 쌍의 앞쪽(high surrogate)이면 그것까지 빼 쌍을 가르지 않는다. */
function truncateUtf16(text: string, maxUnits: number): string {
  if (text.length <= maxUnits) return text;
  let end = Math.max(0, maxUnits);
  const last = text.charCodeAt(end - 1);
  if (end > 0 && last >= 0xd800 && last <= 0xdbff) end -= 1;
  return text.slice(0, end);
}

/**
 * @param separator 원래 이름과 접미사 사이 글자 — 기본은 공백(「이름 (복사본)」), 데이터셋 복제는 빈 문자열(「이름_copy」, WD-523).
 */
export function cloneName(name: string, suffix: string, maxLength = NAME_MAX_LENGTH, separator = ' '): string {
  const tail = `${separator}${suffix}`;
  const room = maxLength - tail.length;
  const base = name.length > room ? truncateUtf16(name, room).trimEnd() : name;
  return `${base}${tail}`;
}

/**
 * 이름 겹침 재시도(`createWithNumberedName`)의 시도 상한(WD-824) — 복제뿐 아니라 파생 이름(자동 양식·AI 차트 저장)도 쓴다. 겹치는 이름이 이만큼 쌓여 있으면 더 시도하지 않고 서버 오류를 그대로 보인다.
 * 서버가 복사본을 몇 개든 허용하므로 끝없이 두드리지 않게 막는 안전장치다.
 */
export const NAME_RETRY_MAX_ATTEMPTS = 20;

/**
 * 복제 이름 후보(WD-824) — 1번째는 「이름 (복사본)」, 2번째부터 「이름 (복사본 2)」「(복사본 3)」 … 서버 ResourceNameGuard.copyName 과 같은 모양이다.
 * 번호가 붙으면 접미사가 길어지므로 접미사별로 원래 이름 쪽을 잘라 200자 안에 맞춘다(자르는 규칙은 cloneName 과 같다).
 *
 * @param label 접미사 안의 말 — 「복사본」(스마트 작업·양식·쿼리 공통)
 * @param attempt 1부터 시작하는 시도 번호
 */
export function numberedCloneName(name: string, label: string, attempt: number): string {
  return cloneName(name, attempt <= 1 ? `(${label})` : `(${label} ${attempt})`);
}

/**
 * 번호 붙은 이름 후보(WD-824) — 1번째는 기본 이름 그대로, 2번째부터 「이름 (2)」「이름 (3)」 …
 * 사용자가 직접 쓰지 않고 다른 이름에서 파생한 이름(작업 이름의 자동 양식 「<작업> 양식」, AI 차트 저장의 「AI 생성: <차트>」)용이다.
 * 번호가 들어갈 만큼 기본 이름 끝을 잘라 200자 안에 맞춘다(자르는 규칙은 cloneName 과 같다).
 */
export function numberedName(base: string, attempt: number, maxLength = NAME_MAX_LENGTH): string {
  return attempt <= 1 ? base : cloneName(base, `(${attempt})`, maxLength);
}

/**
 * 이름 겹침 재시도(WD-824) — 후보 이름으로 만들고, 서버가 409 DUPLICATE_NAME 으로 거절하면 다음 번호 이름으로 다시 시도한다.
 * 이름 중복이 아닌 오류나 시도 상한(NAME_RETRY_MAX_ATTEMPTS)을 넘긴 마지막 오류는 그대로 던져 호출부의 일반 오류 처리로 넘긴다.
 *
 * @param nameFor 1부터 시작하는 시도 번호로 후보 이름을 만든다
 * @param create 후보 이름으로 생성 API 를 호출한다
 */
export async function createWithNumberedName<T>(
  nameFor: (attempt: number) => string,
  create: (candidate: string) => Promise<T>,
): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await create(nameFor(attempt));
    } catch (error) {
      if (!isDuplicateName(error) || attempt >= NAME_RETRY_MAX_ATTEMPTS) throw error;
    }
  }
}

/**
 * 복제 생성 + 이름 겹침 재시도(WD-824) — 서버에 복제 전용 API 가 없어 웹이 이름을 만들어 일반 생성 API 로 보낸다.
 * 서버가 같은 소유자 안의 같은 이름을 409 DUPLICATE_NAME 으로 막으므로, 이미 복사본이 있으면 번호를 올려 다시 시도한다.
 *
 * @param create 후보 이름으로 생성 API 를 호출한다
 */
export function createWithUniqueCloneName<T>(
  name: string,
  label: string,
  create: (candidate: string) => Promise<T>,
): Promise<T> {
  return createWithNumberedName((attempt) => numberedCloneName(name, label, attempt), create);
}
