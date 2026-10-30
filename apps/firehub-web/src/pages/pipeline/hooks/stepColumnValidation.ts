import { columnNameLengthError, RESERVED_COLUMN_NAME_MESSAGE, SYSTEM_COLUMN_NAMES } from '@/lib/validations/dataset';

/**
 * 스텝 출력 컬럼(API_CALL 응답 필드 매핑·AI_CLASSIFY·PYTHON 출력 컬럼) 저장 시점 검증 (WD-154, PYTHON 은 WD-352).
 *
 * 예전에는 빈 매핑 행·빈 출력 컬럼 이름·출력 데이터셋에 없는 대상 컬럼이 그대로 저장되고, 실행 시점에야
 * 영어 원문 오류(`Invalid name: . Must match …`, `bad SQL grammar [INSERT INTO …]`)로 실패했다.
 * 서버(PipelineService)도 같은 규칙·같은 문장으로 거부하므로, 편집기가 먼저 막아 해당 행 아래에 안내한다.
 * 저장할 때만 검사한다 — 방금 「매핑 추가」한 빈 행이 바로 빨갛게 보이거나, 빈 행이 남은 기존 파이프라인을 열기만 해도 오류가 뜨지 않게.
 */

/** 검증 결과 한 건 — field 는 StepConfigPanel 의 오류 조회 키(행 단위) */
export interface StepFieldError {
  field: string;
  message: string;
}

export const EMPTY_MAPPING_ROW_MESSAGE = '비어 있는 매핑 행이 있습니다 — 채우거나 삭제하세요';
export const TARGET_COLUMN_REQUIRED_MESSAGE = '대상 컬럼 이름을 입력하세요';
export const SOURCE_FIELD_REQUIRED_MESSAGE = '응답 필드를 입력하세요';
export const OUTPUT_COLUMN_NAME_REQUIRED_MESSAGE = '출력 컬럼 이름을 입력하세요';

// 데이터셋 이름 규칙(백엔드 DataTableNames.isValid 와 같다)
const COLUMN_NAME_PATTERN = /^[a-z][a-z0-9_]*$/;

/** 형식 위반 안내 — 서버(WD-111 실행 오류 안내와 같은 문장)와 같다 */
export const invalidColumnNameMessage = (name: string) =>
  `컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다: ${name}`;
export const duplicateTargetColumnMessage = (name: string) => `대상 컬럼이 중복됩니다: ${name}`;
export const duplicateOutputColumnMessage = (name: string) => `출력 컬럼 이름이 중복됩니다: ${name}`;
export const missingOutputColumnMessage = (name: string) => `출력 데이터셋에 없는 컬럼입니다: ${name}`;

/** 매핑 행 오류 키 — 행 전체(빈 행) / 응답 필드 / 대상 컬럼 */
export const mappingRowField = (i: number) => `apiConfig.fieldMappings.${i}`;
export const mappingSourceField = (i: number) => `apiConfig.fieldMappings.${i}.sourceField`;
export const mappingTargetField = (i: number) => `apiConfig.fieldMappings.${i}.targetColumn`;
/** AI_CLASSIFY 출력 컬럼 이름 오류 키 */
export const aiOutputColumnNameField = (i: number) => `aiConfig.outputColumns.${i}.name`;
/** PYTHON 출력 컬럼 이름 오류 키(WD-352) */
export const pythonOutputColumnNameField = (i: number) => `pythonConfig.outputColumns.${i}.name`;

const isSystemColumn = (name: string) =>
  (SYSTEM_COLUMN_NAMES as readonly string[]).includes(name.toLowerCase());

/**
 * 컬럼 이름의 예약어·형식·길이 검사. 예약어는 출력을 자동 생성(TEMP)할 때만 막는다 — TEMP 생성이 예약어를 거부하기 때문이다.
 * 지정 출력 데이터셋에는 id 등 시스템 컬럼이 물리적으로 있어 적재할 수 있다(서버 rejectInvalidColumnName 과 같은 규칙).
 */
export function columnNameError(name: string, autoOutput: boolean): string | undefined {
  if (autoOutput && isSystemColumn(name)) return RESERVED_COLUMN_NAME_MESSAGE;
  if (!COLUMN_NAME_PATTERN.test(name)) return invalidColumnNameMessage(name);
  // 63자를 넘으면 TEMP 생성이 실행 시점에 거부한다 — 저장 전에 같은 상한으로 막는다(WD-352)
  return columnNameLengthError(name);
}

function asText(value: unknown): string {
  return typeof value === 'string' ? value : value == null ? '' : String(value);
}

/**
 * API_CALL 응답 필드 매핑 검증 — 행마다 첫 번째 오류 하나만 돌려준다(행 아래 오류 자리가 하나라서).
 *
 * @param mappings apiConfig.fieldMappings 원본 값(형식이 어긋나도 깨지지 않게 unknown 으로 받는다)
 * @param autoOutput 출력 데이터셋 미지정(자동 생성) 여부
 * @param outputColumns 지정 출력 데이터셋의 컬럼 이름 — 알 수 없으면(미지정·볼 수 없음·조회 실패) null, 그때는 존재 검사를 건너뛴다(서버가 판정)
 */
export function validateApiFieldMappings(
  mappings: unknown,
  autoOutput: boolean,
  outputColumns: readonly string[] | null,
): StepFieldError[] {
  if (!Array.isArray(mappings)) return [];
  const errors: StepFieldError[] = [];
  const seen = new Set<string>();
  const existing = outputColumns ? new Set([...outputColumns, ...SYSTEM_COLUMN_NAMES]) : null;
  mappings.forEach((raw, i) => {
    const row = (raw ?? {}) as Record<string, unknown>;
    const source = asText(row.sourceField);
    const target = asText(row.targetColumn);
    const sourceBlank = !source.trim();
    const targetBlank = !target.trim();
    if (sourceBlank && targetBlank) {
      errors.push({ field: mappingRowField(i), message: EMPTY_MAPPING_ROW_MESSAGE });
      return;
    }
    if (targetBlank) {
      errors.push({ field: mappingTargetField(i), message: TARGET_COLUMN_REQUIRED_MESSAGE });
      return;
    }
    if (sourceBlank) {
      errors.push({ field: mappingSourceField(i), message: SOURCE_FIELD_REQUIRED_MESSAGE });
      return;
    }
    const nameError = columnNameError(target, autoOutput);
    if (nameError) {
      errors.push({ field: mappingTargetField(i), message: nameError });
      return;
    }
    // 같은 대상 컬럼 둘은 INSERT 컬럼 중복(또는 TEMP 생성 실패)으로 실행이 실패한다 — 뒤에 나온 행에 안내한다.
    if (seen.has(target)) {
      errors.push({ field: mappingTargetField(i), message: duplicateTargetColumnMessage(target) });
      return;
    }
    seen.add(target);
    if (existing && !existing.has(target)) {
      errors.push({ field: mappingTargetField(i), message: missingOutputColumnMessage(target) });
    }
  });
  return errors;
}

/**
 * 출력 컬럼 이름 검증 — 빈 이름·예약어(자동 생성 시)·형식·중복. 행마다 첫 번째 오류 하나만 돌려준다.
 * AI_CLASSIFY·PYTHON 출력 컬럼이 같은 규칙을 쓴다 — 둘 다 이 이름으로 TEMP 를 만들어 실행 시점에 같은 이유로 실패했다.
 *
 * @param fieldOf 행 번호 → 오류 조회 키(스텝 종류마다 설정 경로가 다르다)
 */
function validateOutputColumns(
  columns: unknown,
  autoOutput: boolean,
  fieldOf: (i: number) => string,
): StepFieldError[] {
  if (!Array.isArray(columns)) return [];
  const errors: StepFieldError[] = [];
  const seen = new Set<string>();
  columns.forEach((raw, i) => {
    const name = asText(((raw ?? {}) as Record<string, unknown>).name);
    if (!name.trim()) {
      errors.push({ field: fieldOf(i), message: OUTPUT_COLUMN_NAME_REQUIRED_MESSAGE });
      return;
    }
    const nameError = columnNameError(name, autoOutput);
    if (nameError) {
      errors.push({ field: fieldOf(i), message: nameError });
      return;
    }
    if (seen.has(name)) {
      errors.push({ field: fieldOf(i), message: duplicateOutputColumnMessage(name) });
      return;
    }
    seen.add(name);
  });
  return errors;
}

/** AI_CLASSIFY 출력 컬럼 이름 검증. 컬럼이 0개인 경우는 호출부의 기존 검사가 맡는다. */
export const validateAiOutputColumns = (columns: unknown, autoOutput: boolean) =>
  validateOutputColumns(columns, autoOutput, aiOutputColumnNameField);

/**
 * PYTHON 출력 컬럼 이름 검증(WD-352). 출력 컬럼 정의는 출력을 자동 생성할 때만 쓰이므로(편집기도 그때만 보인다) 호출부는
 * 자동 생성 스텝에만 부른다 — 그래서 예약어도 늘 막는다.
 */
export const validatePythonOutputColumns = (columns: unknown) =>
  validateOutputColumns(columns, true, pythonOutputColumnNameField);

/** API_CALL OFFSET 페이지네이션의 페이지 크기 허용 범위(WD-362) — 서버 PipelineService.MIN/MAX_PAGE_SIZE 와 같다. */
export const API_PAGE_SIZE_RANGE = { min: 1, max: 10_000 } as const;
export const API_PAGE_SIZE_FIELD = 'apiConfig.pagination.pageSize';
export const API_PAGE_SIZE_MESSAGE = `페이지 크기는 ${API_PAGE_SIZE_RANGE.min}~${API_PAGE_SIZE_RANGE.max} 사이의 정수여야 합니다`;

/**
 * API_CALL 페이지 크기 오류 문구(WD-362) — OFFSET 이 아니거나 비어 있으면(실행기 기본값 100) 없음.
 * 0 이하 페이지 크기는 실행기의 「다음 페이지 없음」 판정이 참이 되지 않아 외부 API 를 끝없이 다시 호출하므로 저장 전에 막는다.
 * 재시도·타임아웃처럼 입력 즉시 고치지 않는 이유: 페이지 크기는 외부 API 의 limit 로 그대로 나가 조용히 바꾸면 사용자가 모른다.
 */
export function apiPageSizeError(pagination: unknown): string | undefined {
  const pag = (pagination ?? {}) as Record<string, unknown>;
  if (asText(pag.type).toUpperCase() !== 'OFFSET' || pag.pageSize == null) return undefined;
  const size = pag.pageSize;
  const valid =
    typeof size === 'number' &&
    Number.isInteger(size) &&
    size >= API_PAGE_SIZE_RANGE.min &&
    size <= API_PAGE_SIZE_RANGE.max;
  return valid ? undefined : API_PAGE_SIZE_MESSAGE;
}
