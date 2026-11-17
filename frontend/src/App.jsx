import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthProvider.jsx'
import { useAuth } from './auth/useAuth.js'
import Layout from './components/Layout.jsx'
import Login from './pages/Login.jsx'
import Console from './pages/Console.jsx'
import Overview from './pages/Overview.jsx'
import Incidents from './pages/Incidents.jsx'
import IncidentDetail from './pages/IncidentDetail.jsx'
import Deploys from './pages/Deploys.jsx'
import Telemetry from './pages/Telemetry.jsx'
import AlertSources from './pages/AlertSources.jsx'
import ApiKeys from './pages/ApiKeys.jsx'
import BusinessKpis from './pages/BusinessKpis.jsx'
import Dashboards from './pages/Dashboards.jsx'
import Integrations from './pages/Integrations.jsx'
import Users from './pages/Users.jsx'
import ConnectApp from './pages/ConnectApp.jsx'

function RequireAuth() {
  const { status } = useAuth()
  if (status === 'anonymous') return <Navigate to="/login" replace />
  return <Layout />
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route element={<RequireAuth />}>
            <Route index element={<Console />} />
            <Route path="overview" element={<Overview />} />
            <Route path="incidents" element={<Incidents />} />
            <Route path="incidents/:id" element={<IncidentDetail />} />
            <Route path="deploys" element={<Deploys />} />
            <Route path="telemetry" element={<Telemetry />} />
            <Route path="alert-sources" element={<AlertSources />} />
            <Route path="settings/api-keys" element={<ApiKeys />} />
            <Route path="settings/business" element={<BusinessKpis />} />
            <Route path="settings/users" element={<Users />} />
            <Route path="dashboards" element={<Dashboards />} />
            <Route path="integrations" element={<Integrations />} />
            <Route path="connect" element={<ConnectApp />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
