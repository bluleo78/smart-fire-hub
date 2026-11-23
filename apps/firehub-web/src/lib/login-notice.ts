/**
 * 로그인 화면에 한 번 보일 안내(WD-276).
 *
 * <p>로그아웃은 /login 으로 하드 리로드하므로(api/client.ts 의 endSessionAndReloadToLogin) 로그아웃 직전에 띄운 토스트는 리로드와 함께
 * 사라진다. 로그아웃 이유를 알려야 하는 경우(예: 비밀번호 변경 뒤 세션 갱신 실패)에 문구를 탭 저장소(sessionStorage)에
 * 맡겨 두면 LoginPage 가 마운트될 때 한 번 꺼내 보여 준다. 저장소를 쓸 수 없는 환경(사생활 보호 모드 등)에서는
 * 조용히 건너뛴다 — 안내가 빠질 뿐 로그아웃은 그대로 된다.
 */
const LOGIN_NOTICE_KEY = 'loginNotice';

/**
 * refresh 가 401 로 거절돼 로그인 화면으로 보내질 때의 안내 문구(WD-830).
 * 401 만 만료로 본다 — 네트워크 오류·5xx 는 일시 장애일 수 있고, refresh 이후 /users/me 의 401 은 인터셉터의 refresh 가 맡는다.
 */
export const SESSION_EXPIRED_NOTICE = '세션이 만료되어 다시 로그인해 주세요';

/** 다음 로그인 화면에서 보일 안내를 맡긴다. */
export function setLoginNotice(message: string) {
  try {
    sessionStorage.setItem(LOGIN_NOTICE_KEY, message);
  } catch {
    // 저장소를 쓸 수 없으면 안내 없이 넘어간다
  }
}

/** 맡겨 둔 안내를 꺼내고 지운다 — 한 번만 보이게. 없으면 null. */
export function takeLoginNotice(): string | null {
  try {
    const message = sessionStorage.getItem(LOGIN_NOTICE_KEY);
    if (message !== null) sessionStorage.removeItem(LOGIN_NOTICE_KEY);
    return message;
  } catch {
    return null;
  }
}

/**
 * 맡겨 둔 안내를 보이지 않고 지운다(WD-830).
 *
 * <p>안내는 LoginPage 가 부팅 인증이 끝난 뒤 꺼내 보인다. 그 전에 로그인에 성공하거나 안내 없이 로그아웃하면
 * 소비되지 않은 안내가 남아 엉뚱한 때(예: 직접 로그아웃 뒤)에 보일 수 있어 그 시점에 비운다.
 */
export function clearLoginNotice() {
  try {
    sessionStorage.removeItem(LOGIN_NOTICE_KEY);
  } catch {
    // 저장소를 쓸 수 없으면 지울 것도 없다
  }
}
