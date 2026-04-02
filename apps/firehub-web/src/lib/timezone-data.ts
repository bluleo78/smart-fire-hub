export interface TimezoneOption {
  value: string;
  abbr: string;
  offset: string;
}

export const TIMEZONE_OPTIONS: TimezoneOption[] = [
  { value: 'Asia/Seoul', abbr: 'KST', offset: 'UTC+9' },
  { value: 'Asia/Tokyo', abbr: 'JST', offset: 'UTC+9' },
  { value: 'Asia/Shanghai', abbr: 'CST', offset: 'UTC+8' },
  { value: 'Asia/Singapore', abbr: 'SGT', offset: 'UTC+8' },
  { value: 'America/New_York', abbr: 'EST', offset: 'UTC-5' },
  { value: 'America/Chicago', abbr: 'CST', offset: 'UTC-6' },
  { value: 'America/Denver', abbr: 'MST', offset: 'UTC-7' },
  { value: 'America/Los_Angeles', abbr: 'PST', offset: 'UTC-8' },
  { value: 'Europe/London', abbr: 'GMT', offset: 'UTC+0' },
  { value: 'Europe/Paris', abbr: 'CET', offset: 'UTC+1' },
  { value: 'Europe/Berlin', abbr: 'CET', offset: 'UTC+1' },
  { value: 'Australia/Sydney', abbr: 'AEST', offset: 'UTC+10' },
  { value: 'Pacific/Auckland', abbr: 'NZST', offset: 'UTC+12' },
  { value: 'UTC', abbr: 'UTC', offset: 'UTC+0' },
];
