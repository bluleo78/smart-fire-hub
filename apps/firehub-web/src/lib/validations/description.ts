/**
 * 자산 설명(description) 길이 상한 — 서버 DescriptionLimits.MAX_LENGTH 와 같은 값이다(WD-520).
 *
 * 저장 쿼리·차트·데이터셋·파이프라인·API 연결·대시보드의 목록 응답이 설명 전문을 싣기 때문에, 서버가 2,000자를 넘는 설명을
 * 400 으로 거부한다. 입력란(maxLength)과 zod 스키마가 이 한 곳의 값을 써서 서버와 어긋나지 않게 한다.
 * 이미 더 낮은 상한을 쓰는 입력(카테고리·칼럼 255자, 리포트 양식 500자)은 그대로 둔다.
 */
export const MAX_DESCRIPTION_LENGTH = 2_000;

/**
 * 데이터셋 설명 상한 초과 안내 — 생성·수정·복제 zod 스키마가 같이 쓴다.
 * 다른 자산의 입력란은 zod 폼이 아니어서 maxLength 로만 막으므로 문구가 필요 없다.
 */
export const DATASET_DESCRIPTION_TOO_LONG = '데이터셋 설명은 최대 2,000자까지 입력할 수 있습니다.';
