import { describe, expect, it } from 'vitest';

import {
  REPORT_HTML_MISSING_MESSAGE,
  REPORT_LOAD_ERROR_MESSAGE,
  USER_SWITCHED_MESSAGE,
  UserSwitchedError,
} from './api-error';
import {
  REPORT_NOT_FOUND_NOTICE,
  reportBodyState,
  reportHtmlErrorKind,
  reportHtmlErrorText,
} from './proactive-utils';

/** axios.isAxiosError 는 isAxiosError === true 인 객체를 인정한다 — 응답 모양만 재현한다. */
function axiosError(status: number, data: unknown) {
  return { isAxiosError: true, response: { status, data } };
}

// 리포트 HTML 조회 오류 → 안내 문구 규칙 (WD-1098)
describe('reportHtmlErrorText', () => {
  it('사용자 전환 오류는 전환 안내 문구를 쓴다', () => {
    expect(reportHtmlErrorText(new UserSwitchedError())).toBe(USER_SWITCHED_MESSAGE);
  });

  it('400 은 서버 문구를 그대로 쓴다', () => {
    const msg = '완료된 실행만 리포트를 열거나 내려받을 수 있습니다.';
    expect(reportHtmlErrorText(axiosError(400, { message: msg }))).toBe(msg);
  });

  it('400 인데 message 가 없으면 고정 문구로 떨어진다', () => {
    expect(reportHtmlErrorText(axiosError(400, {}))).toBe(REPORT_LOAD_ERROR_MESSAGE);
  });

  it('404 이면서 HTML 없음 문구면 그 문구를 쓴다', () => {
    expect(reportHtmlErrorText(axiosError(404, { message: REPORT_HTML_MISSING_MESSAGE }))).toBe(
      REPORT_HTML_MISSING_MESSAGE,
    );
  });

  it('그 밖의 404 는 작성자 안내를 쓴다(내부 문구 숨김)', () => {
    expect(reportHtmlErrorText(axiosError(404, { message: 'Job not found: 1' }))).toBe(REPORT_NOT_FOUND_NOTICE);
  });

  it('500 은 서버 원문을 숨기고 고정 문구를 쓴다', () => {
    expect(reportHtmlErrorText(axiosError(500, { message: 'NullPointerException at ...' }))).toBe(
      REPORT_LOAD_ERROR_MESSAGE,
    );
  });

  it('403 은 고정 문구를 쓴다', () => {
    expect(reportHtmlErrorText(axiosError(403, { message: 'forbidden' }))).toBe(REPORT_LOAD_ERROR_MESSAGE);
  });

  it('네트워크 오류는 고정 문구를 쓴다', () => {
    expect(reportHtmlErrorText(new Error('network'))).toBe(REPORT_LOAD_ERROR_MESSAGE);
  });
});

// 리포트 HTML 조회 오류 → 화면 갈래 분류 (WD-1098)
describe('reportHtmlErrorKind', () => {
  it('오류가 없으면 null', () => {
    expect(reportHtmlErrorKind(null)).toBeNull();
    expect(reportHtmlErrorKind(undefined)).toBeNull();
  });

  it('등급 차단 403 은 level-blocked 가 권한 부족보다 먼저다', () => {
    const err = axiosError(403, { code: 'REPORT_SECURITY_LEVEL_BLOCKED' });
    expect(reportHtmlErrorKind(err)).toEqual({ kind: 'level-blocked' });
  });

  it('일반 403 은 forbidden', () => {
    expect(reportHtmlErrorKind(axiosError(403, {}))).toEqual({ kind: 'forbidden' });
  });

  it('그 밖의 오류는 message 갈래로 공용 문구를 담는다', () => {
    expect(reportHtmlErrorKind(axiosError(500, {}))).toEqual({
      kind: 'message',
      text: REPORT_LOAD_ERROR_MESSAGE,
    });
  });
});

// 리포트 본문 표시 상태 판정 — 실행 상세·리포트 모달·리포트 뷰어 공통 우선순위 (WD-1177)
describe('reportBodyState (WD-1177)', () => {
  it('level-blocked 403 + html 있음 + 폴백 있음 → level-blocked', () => {
    const err = axiosError(403, { code: 'REPORT_SECURITY_LEVEL_BLOCKED' });
    expect(reportBodyState(err, '<html>cached</html>', true)).toEqual({ state: 'level-blocked' });
  });

  it('일반 403 + html 있음 + 폴백 있음 → forbidden', () => {
    expect(reportBodyState(axiosError(403, {}), '<html>cached</html>', true)).toEqual({ state: 'forbidden' });
  });

  it('500 + html 있음 → html (캐시 HTML 이 이긴다 — 403 아님)', () => {
    expect(reportBodyState(axiosError(500, {}), '<html>cached</html>', true)).toEqual({ state: 'html' });
  });

  it('오류 없음 + html 없음 + 폴백 있음 → fallback', () => {
    expect(reportBodyState(null, null, true)).toEqual({ state: 'fallback' });
  });

  it('404(HTML 없음) + html 없음 + 폴백 없음 → message(서버 HTML 없음 문구)', () => {
    expect(reportBodyState(axiosError(404, { message: REPORT_HTML_MISSING_MESSAGE }), null, false)).toEqual({
      state: 'message',
      text: '이 실행에는 HTML 리포트가 없습니다.',
    });
  });

  it('오류 없음 + html 없음 + 폴백 없음 → message(리포트가 없습니다.)', () => {
    expect(reportBodyState(null, null, false)).toEqual({ state: 'message', text: '리포트가 없습니다.' });
  });

  it('실행을 못 읽었을 때(실행 조회 오류) → 그 문구가 HTML 오류 문구보다 먼저다 (WD-1098)', () => {
    // HTML 쪽은 404(HTML 없음) 문구, 실행 쪽은 500 로 두어 어느 쪽이 이기는지 가르는 문구를 비교한다.
    expect(
      reportBodyState(axiosError(404, { message: REPORT_HTML_MISSING_MESSAGE }), null, false, {
        error: axiosError(500, {}),
        executionLoaded: false,
      }),
    ).toEqual({ state: 'message', text: REPORT_LOAD_ERROR_MESSAGE });
  });

  it('실행을 읽었는데 html 오류만 있으면 → html 오류 문구가 나온다 (WD-1098)', () => {
    expect(
      reportBodyState(axiosError(404, { message: REPORT_HTML_MISSING_MESSAGE }), null, false, {
        executionLoaded: true,
      }),
    ).toEqual({ state: 'message', text: REPORT_HTML_MISSING_MESSAGE });
  });

  it('실행 조회 403(권한) + html 성공 → forbidden — 한 화면에서 한 상태만 보인다 (WD-1177)', () => {
    expect(
      reportBodyState(null, '<html>cached</html>', true, { error: axiosError(403, {}), executionLoaded: true }),
    ).toEqual({ state: 'forbidden' });
  });

  it('실행 조회 등급 차단 403 → level-blocked 가 가장 먼저다 (WD-198)', () => {
    expect(
      reportBodyState(
        axiosError(403, {}),
        '<html>cached</html>',
        true,
        { error: axiosError(403, { code: 'REPORT_SECURITY_LEVEL_BLOCKED' }), executionLoaded: false },
      ),
    ).toEqual({ state: 'level-blocked' });
  });
});
