/**
 * 로그인 화면에 한 번 보일 안내(WD-276).
 *
 * <p>로그아웃은 /login 으로 하드 리로드하므로(api/client.ts 의 endSessionAndReloadToLogin) 로그아웃 직전에 띄운 토스트는 리로드와 함께
 * 사라진다. 로그아웃 이유를 알려야 하는 경우(예: 비밀번호 변경 뒤 세션 갱신 실패)에 문구를 탭 저장소(sessionStorage)에
 * 맡겨 두면 LoginPage 가 마운트될 때 한 번 꺼내 보여 준다. 저장소를 쓸 수 없는 환경(사생활 보호 모드 등)에서는
 * 조용히 건너뛴다 — 안내가 빠질 뿐 로그아웃은 그대로 된다.
 */
const LOGIN_NOTICE_KEY = 'loginNotice';

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
