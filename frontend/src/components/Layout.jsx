import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth.js'
import { ROLE_LABEL } from '../format.js'
import { pageFor, recordVisit, visiblePages } from '../navigation.js'
import { Icon } from './Icon.jsx'
import { Logo } from './Logo.jsx'

function NavItem({ page, onNavigate }) {
  return (
    <NavLink to={page.path} end={page.path === '/'} className="side-item" onClick={onNavigate} title={page.label}>
      <Icon name={page.icon} size={20} />
      <span className="side-label">{page.label}</span>
    </NavLink>
  )
}

const PINNED_KEY = 'argos.sidebarPinned'

/** Whether the sidebar is pinned open (per browser; storage failures fall back to the hover rail). */
function loadPinned() {
  try {
    return localStorage.getItem(PINNED_KEY) === 'true'
  } catch {
    return false
  }
}

/** Light/dark theme switch; the choice is a per-browser convenience, so storage failures are ignored. */
function ThemeToggle() {
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme ?? 'light')
  const next = theme === 'dark' ? 'light' : 'dark'
  function toggle() {
    document.documentElement.dataset.theme = next
    try {
      localStorage.setItem('argos.theme', next)
    } catch {
      // Private mode: the theme just won't survive a reload.
    }
    setTheme(next)
  }
  return (
    <button type="button" className="theme-toggle" onClick={toggle}
      aria-label={next === 'dark' ? 'Ativar modo escuro' : 'Ativar modo claro'} title={next === 'dark' ? 'Modo escuro' : 'Modo claro'}>
      <Icon name={next === 'dark' ? 'moon' : 'sun'} size={18} />
    </button>
  )
}

export default function Layout() {
  const { session, logout } = useAuth()
  const { pathname } = useLocation()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [pinned, setPinned] = useState(loadPinned)
  const pages = visiblePages(session)
  const main = pages.filter((page) => !page.section)
  const settings = pages.filter((page) => page.section === 'integrations')
  const closeMobile = () => setMobileOpen(false)

  function togglePinned() {
    setPinned(!pinned)
    try {
      localStorage.setItem(PINNED_KEY, String(!pinned))
    } catch {
      // Private mode: the choice just won't survive a reload.
    }
  }

  // Feeds the "recently visited" list of the command search.
  useEffect(() => {
    const page = pageFor(pathname)
    if (page && page.path !== '/') recordVisit(page.path)
  }, [pathname])

  return (
    <div className={`shell ${pinned ? 'is-pinned' : 'is-rail'}`}>
      <aside className="sidebar">
        <div className="sidebar-head">
          <Link to="/" className="sidebar-brand" aria-label="Argos: início" onClick={closeMobile}>
            <Logo />
          </Link>
          <button type="button" className="sidebar-pin" onClick={togglePinned} aria-pressed={pinned}
            aria-label={pinned ? 'Desafixar menu' : 'Fixar menu aberto'} title={pinned ? 'Desafixar menu' : 'Fixar menu aberto'}>
            <Icon name="pin" size={17} />
          </button>
          <button type="button" className="sidebar-toggle" aria-expanded={mobileOpen} aria-controls="sidebar-body"
            aria-label={mobileOpen ? 'Fechar menu' : 'Abrir menu'} onClick={() => setMobileOpen(!mobileOpen)}>
            <Icon name={mobileOpen ? 'close' : 'menu'} size={22} />
          </button>
        </div>

        <div id="sidebar-body" className={`sidebar-body ${mobileOpen ? 'is-open' : ''}`}>
          <nav className="side-nav" aria-label="Navegação principal">
            {main.map((page) => <NavItem key={page.path} page={page} onNavigate={closeMobile} />)}

            {settings.map((page) => <NavItem key={page.path} page={page} onNavigate={closeMobile} />)}
          </nav>

          <div className="sidebar-footer">
            <div className="user">
              <span className="avatar" aria-hidden="true">{session.role[0]}</span>
              <span>
                <strong>{ROLE_LABEL[session.role] ?? session.role}</strong>
                <small>sessão ativa</small>
              </span>
            </div>
            <button type="button" className="link-button" onClick={logout}>Sair</button>
          </div>
        </div>
      </aside>
      <main className="content">
        <div className="content-top">
          <ThemeToggle />
        </div>
        <Outlet />
      </main>
    </div>
  )
}
