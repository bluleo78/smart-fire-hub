/**
 * 권한 카테고리(permission category) 한국어 라벨 모듈.
 *
 * 왜 웹에 맵을 두는가: 백엔드는 카테고리를 영문 키(user, dataset 등)로만 내려주고
 * 표시용 한글명은 제공하지 않는다. 지금은 역할 상세 화면만 쓰지만, 페이지 안에 부분 맵을 두어
 * 생긴 누락(WD-814)이 되풀이되지 않게 시드된 카테고리 전부를 한 곳에 모은다.
 */
const PERMISSION_CATEGORY_LABELS: Readonly<Record<string, string>> = {
  user: '사용자',
  role: '역할',
  permission: '권한',
  dataset: '데이터셋',
  data: '데이터',
  pipeline: '파이프라인',
  trigger: '트리거',
  apiconnection: 'API 연결',
  analytics: '분석',
  ai: 'AI',
  chat: '채팅',
  settings: '설정',
  security: '보안',
  proactive: '스마트 작업',
  notification: '알림',
  audit: '감사 로그',
  ontology: '지식 모델',
  platform: '플랫폼',
};

/**
 * 카테고리 키를 화면 표시용 한국어 라벨로 바꾼다.
 *
 * 맵에 없는 키(새 카테고리 등)는 키를 그대로 돌려줘 화면이 비지 않게 한다.
 * 대소문자 변환은 하지 않는다. 조회는 Object.hasOwn 으로 해서
 * `__proto__`, `constructor` 같은 프로토타입 키가 라벨로 새어 나오지 않게 한다.
 */
export function permissionCategoryLabel(category: string): string {
  return Object.hasOwn(PERMISSION_CATEGORY_LABELS, category)
    ? PERMISSION_CATEGORY_LABELS[category]
    : category;
}
