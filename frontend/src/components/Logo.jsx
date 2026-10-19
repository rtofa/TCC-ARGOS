/**
 * Argos mark: the watchful eye of Argos Panoptes, the hundred-eyed guardian of Greek myth.
 * Drawn with currentColor; the glint takes the tile's background so the mark works in both themes.
 */
export function LogoMark({ size = 32 }) {
  return (
    <svg viewBox="0 0 32 32" width={size} height={size} fill="none" aria-hidden="true" className="logo-mark">
      <g stroke="currentColor" strokeWidth="2" strokeLinecap="round">
        <path d="M16 5.2V3M10.2 6.6 9 4.8M21.8 6.6 23 4.8" />
        <path d="M3.5 17c3.1-5.4 7.3-8.2 12.5-8.2s9.4 2.8 12.5 8.2c-3.1 5.4-7.3 8.2-12.5 8.2S6.6 22.4 3.5 17Z" strokeLinejoin="round" />
        <circle cx="16" cy="17" r="4.8" />
      </g>
      <circle cx="16" cy="17" r="2.3" fill="currentColor" />
      <circle cx="17.9" cy="15.1" r="1.1" className="logo-glint" />
    </svg>
  )
}

export function Logo({ large = false }) {
  return (
    <span className={`brand ${large ? 'brand-large' : ''}`}>
      <span className="logo-tile">
        <LogoMark size={large ? 30 : 24} />
      </span>
      <span className="brand-name">Argos</span>
    </span>
  )
}
