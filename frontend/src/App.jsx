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
import AlertSources from './pages/AlertSources.jsx'
import ApiKeys from './pages/ApiKeys.jsx'

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
            <Route path="alert-sources" element={<AlertSources />} />
            <Route path="settings/api-keys" element={<ApiKeys />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  )
}
