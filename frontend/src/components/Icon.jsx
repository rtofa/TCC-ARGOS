const PATHS = {
  home: 'M3 11.5 12 4l9 7.5M5.5 10v9.5h4.5v-5h4v5h4.5V10',
  overview: 'M4 19v-7M9.5 19V5M15 19v-5M20.5 19V9M3 21h18',
  incidents: 'M12 3 2.5 20h19L12 3Zm0 6.5v5m0 2.5v.01',
  deploys: 'M12 3c3 2 5 5.5 5 9.5l-2 3.5H9l-2-3.5C7 8.5 9 5 12 3Zm0 6.5v.01M9.5 16l-2 4M14.5 16l2 4',
  alerts: 'M6 16v-5a6 6 0 1 1 12 0v5l1.5 2h-15L6 16Zm4.5 4.5h3',
  keys: 'M14.5 4a5.5 5.5 0 1 1-4.9 8L4 17.6V20h3v-2h2v-2h2l1.1-1.1A5.5 5.5 0 0 1 14.5 4Zm2 3.5v.01',
  search: 'M10.5 18a7.5 7.5 0 1 1 0-15 7.5 7.5 0 0 1 0 15Zm5.3-2.2L21 21',
  service: 'M12 3 20 7.5v9L12 21l-8-4.5v-9L12 3Zm0 9 8-4.5M12 12v9M12 12 4 7.5',
  arrow: 'M5 12h14M13 6l6 6-6 6',
  check: 'M5 12.5 10 17.5 19 7.5',
  clock: 'M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18Zm0-13.5V12l3 2',
  monitor: 'M3 12h4l2.5-6 4 12 2.5-6H21',
  kpi: 'M4 20h16M5 16l4.5-4.5 3 3L19 8m-4 0h4v4',
  sparkle: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Zm6.5 11 .8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2Z',
  good: 'M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18Zm-4-9 2.8 2.8L16.5 9',
  warning: 'M12 3 2.5 20h19L12 3Zm0 6.5v5m0 2.5v.01',
  critical: 'M8.5 3h7L21 8.5v7L15.5 21h-7L3 15.5v-7L8.5 3Zm1 6 5 6m0-6-5 6',
}

export function Icon({ name, size = 20, strokeWidth = 1.8, className }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={className}
    >
      <path d={PATHS[name]} />
    </svg>
  )
}

/** Status is never color alone: each state has its own shape, and callers render a text label beside it. */
export function StatusIcon({ status, size = 18 }) {
  return <Icon name={status} size={size} strokeWidth={2} className={`status-icon status-icon-${status}`} />
}
