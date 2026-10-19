import { useEffect } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth/useAuth.js'
import { ROLE_LABEL } from '../format.js'
import { pageFor, recordVisit, visiblePages } from '../navigation.js'
import { Icon } from './Icon.jsx'
import { Logo } from './Logo.jsx'

function NavItem({ page }) {
  return (
    <NavLink to={page.path} end={page.path === '/'} className="nav-item">
      <Icon name={page.icon} />
      <span>{page.label}</span>
    </NavLink>
  )
}

export default function Layout() {
  const { session, logout } = useAuth()
  const { pathname } = useLocation()
  const pages = visiblePages(session)

  // Feeds the "recently visited" widget on the console home.
  useEffect(() => {
    const page = pageFor(pathname)
    if (page && page.path !== '/') recordVisit(page.path)
  }, [pathname])

  return (
    <div className="shell">
      <nav className="sidebar" aria-label="Navegação principal">
        <Logo />
        {pages.filter((page) => !page.section).map((page) => <NavItem key={page.path} page={page} />)}

        <span className="nav-section">Integrações</span>
        {pages.filter((page) => page.section === 'integrations').map((page) => <NavItem key={page.path} page={page} />)}

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
      </nav>
      <main className="content">
        <Outlet />
      </main>
    </div>
  )
}
