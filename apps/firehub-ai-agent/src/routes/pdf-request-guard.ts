/**
 * html-to-pdf 렌더러의 네트워크 요청 판정 (WD-617)
 *
 * 무엇을: Puppeteer 가 HTML 을 그리면서 내보내는 모든 요청(하위 리소스·iframe·이동·리다이렉트)을
 * 허용할지 판정한다. 자기완결형 콘텐츠(data:·blob:·about:)만 허용하고 나머지는 전부 막는다.
 * 왜 전면 차단인가: 리포트 HTML 은 사용자 프롬프트로 조종될 수 있는 AI 산출물이라 렌더러가 대신
 * 요청을 보내면 내부망·클라우드 메타데이터(169.254.169.254) SSRF 가 된다. 그런데 정상 리포트는
 * 외부 리소스가 필요 없다 — report-writer 규칙이 "외부 리소스(CDN·이미지 URL·외부 스크립트) 금지,
 * <style> 인라인, 차트는 인라인 SVG" 로 고정돼 있고, 폰트는 서버에 설치된 로컬 폰트를 쓴다.
 * 그래서 호스트 허용 목록·DNS 검사 없이 스킴만으로 막는 가장 엄격한 정책을 쓴다(우회 여지가 없다).
 */

/** 네트워크를 타지 않는 자기완결형 스킴 — 문서 안에 이미 들어 있는 콘텐츠만 가리킨다 */
const SELF_CONTAINED_SCHEMES = new Set(['data:', 'blob:', 'about:']);

/**
 * 렌더러 요청 URL 을 허용할지 판정한다.
 * @returns data:/blob:/about: 이면 true, 그 밖의 모든 스킴(http·https·file·ws·ftp 등)과 해석 불가 URL 은 false
 */
export function isPdfRequestAllowed(url: string): boolean {
  try {
    return SELF_CONTAINED_SCHEMES.has(new URL(url).protocol);
  } catch {
    // 해석할 수 없는 URL 은 무엇을 가리키는지 알 수 없으므로 막는다(fail-closed)
    return false;
  }
}
