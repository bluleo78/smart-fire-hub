/**
 * SQL 편집기 실행 단축키 안내 문구(WD-87). macOS 면 Mod 가 Cmd, 그 밖에서는 Ctrl 이다.
 *
 * 분석 쿼리 편집기와 데이터셋 SQL 편집기가 같은 문구를 쓴다 — 전에는 데이터셋 쪽이 "Cmd" 고정이라 Windows 에서
 * 안내대로 누르면 아무 일도 없었다. CodeMirror 를 import 하지 않는 모듈이어야 데이터셋 SQL 편집기의 지연 로드가 유지된다.
 */
export const RUN_SHORTCUT_LABEL = `${/mac/i.test(navigator.userAgent) ? 'Cmd' : 'Ctrl'}+Enter로 실행`;
