/**
 * cron 표현식 → 사람이 읽는 한국어 라벨.
 *
 * DB에는 5필드(Unix 표준)와 6필드(Spring, 초 필드 포함)가 섞여 저장돼 있다(#347/#354).
 * 문자열 완전일치 룩업만 쓰면 `0 0 9 * * *`(6필드)가 `0 9 * * *`(5필드)와 매칭되지 않아
 * 같은 스케줄이 한 화면에서 '매일 오전 9시'와 원시 cron 두 가지로 섞여 보인다.
 * 그래서 라벨을 찾기 전에 항상 5필드로 정규화한다.
 */

/** 자주 쓰는 스케줄의 고정 라벨. 키는 모두 5필드 표준 cron. */
const LABELS: Record<string, string> = {
  '0 9 * * *': '매일 오전 9시',
  '0 8 * * *': '매일 오전 8시',
  '0 7 * * *': '매일 오전 7시',
  '0 6 * * *': '매일 오전 6시',
  '0 18 * * *': '매일 오후 6시',
  '0 9 * * 1': '매주 월요일 오전 9시',
  '0 9 * * 5': '매주 금요일 오전 9시',
  '0 9 * * 1-5': '평일 오전 9시',
  '0 * * * *': '매시간',
  '*/30 * * * *': '30분마다',
  '0 0 * * *': '매일 자정',
  '0 0 1 * *': '매월 1일 자정',
  '0 9 1 * *': '매월 1일 오전 9시',
};

const WEEKDAY_NAMES: Record<string, string> = {
  '0': '일요일',
  '1': '월요일',
  '2': '화요일',
  '3': '수요일',
  '4': '목요일',
  '5': '금요일',
  '6': '토요일',
  '7': '일요일',
};

/**
 * 6필드 Spring cron을 5필드 표준 cron으로 환원한다.
 *
 * 초 필드가 정확히 `0`일 때만 떼어낸다. `30 0 9 * * *`(매일 09:00:30)의 초를 버리면
 * '매일 오전 9시'라는 틀린 라벨이 자신 있게 표시되는데, 이는 원시 문자열 노출보다 나쁘다.
 * 초가 0이 아니면 정규화를 포기하고 폴백으로 넘긴다.
 *
 * @returns 5필드 cron, 또는 안전하게 환원할 수 없으면 null
 */
function toFiveField(cron: string): string | null {
  const fields = cron.trim().split(/\s+/);
  if (fields.length === 5) return fields.join(' ');
  if (fields.length === 6) return fields[0] === '0' ? fields.slice(1).join(' ') : null;
  return null;
}

/** `0`~`23` 시를 '오전/오후 N시'로. */
function hourLabel(hour: number): string {
  if (hour === 0) return '자정';
  if (hour === 12) return '정오';
  return hour < 12 ? `오전 ${hour}시` : `오후 ${hour - 12}시`;
}

/** `분 시` 조합을 '오전 9시' / '오전 9시 30분'으로. */
function timeLabel(minute: number, hour: number): string {
  const base = hourLabel(hour);
  return minute === 0 ? base : `${base} ${minute}분`;
}

/** 순수 정수 필드면 그 값, 아니면 null (와일드카드·범위·스텝 표현은 별도 처리). */
function asInt(field: string, max: number): number | null {
  if (!/^\d+$/.test(field)) return null;
  const n = Number(field);
  return n <= max ? n : null;
}

/**
 * 정규 형태의 5필드 cron을 문장으로 만든다.
 * 해석에 100% 확신이 서는 형태만 처리하고, 나머지는 null을 반환해 폴백에 맡긴다.
 */
function describeCron(fiveField: string): string | null {
  const [min, hour, dom, month, dow] = fiveField.split(' ');

  // 월 지정이 있는 식은 표현이 다양해 오역 위험이 크므로 다루지 않는다
  if (month !== '*') return null;

  // N분마다 — `*/N * * * *`
  const everyNMin = /^\*\/(\d+)$/.exec(min);
  if (everyNMin && hour === '*' && dom === '*' && dow === '*') {
    return `${everyNMin[1]}분마다`;
  }

  // N시간마다 — `0 */N * * *`
  const everyNHour = /^\*\/(\d+)$/.exec(hour);
  if (min === '0' && everyNHour && dom === '*' && dow === '*') {
    return `${everyNHour[1]}시간마다`;
  }

  const m = asInt(min, 59);
  const h = asInt(hour, 23);

  // 매시간 N분 — `N * * * *`
  if (m !== null && hour === '*' && dom === '*' && dow === '*') {
    return m === 0 ? '매시간' : `매시간 ${m}분`;
  }

  // 여기부터는 분·시가 모두 확정값이어야 한다
  if (m === null || h === null) return null;

  // 매월 N일 — `M H N * *`
  const d = asInt(dom, 31);
  if (d !== null && dow === '*') {
    return `매월 ${d}일 ${timeLabel(m, h)}`;
  }

  if (dom !== '*') return null;

  // 매일 — `M H * * *`
  if (dow === '*') return `매일 ${timeLabel(m, h)}`;

  // 평일 — `M H * * 1-5`
  if (dow === '1-5') return `평일 ${timeLabel(m, h)}`;

  // 매주 특정 요일 — `M H * * D` (단일 요일만; 목록/범위는 폴백)
  const dayName = WEEKDAY_NAMES[dow];
  if (dayName) return `매주 ${dayName} ${timeLabel(m, h)}`;

  return null;
}

/**
 * cron 표현식을 한국어 라벨로 변환한다.
 *
 * 5필드/6필드를 모두 받아 같은 스케줄이면 같은 라벨을 낸다.
 * 해석할 수 없는 표현은 원문을 그대로 흘리지 않고 `주기: <원문>` 으로 감싸,
 * 사람이 읽는 라벨이 아니라 미해석 폴백임이 화면에서 드러나게 한다.
 */
export function cronToLabel(cron: string): string {
  // 스케줄 없는 작업(triggerType=ANOMALY)은 cron이 비어 있다 — 표시할 주기가 없다는 뜻.
  // 이 가드가 없으면 아래 trim()에서 TypeError 가 나 목록 페이지 전체가 죽는다.
  if (!cron || !cron.trim()) return '-';

  const fiveField = toFiveField(cron);
  if (fiveField) {
    const known = LABELS[fiveField];
    if (known) return known;
    const generated = describeCron(fiveField);
    if (generated) return generated;
  }
  return `주기: ${cron}`;
}
