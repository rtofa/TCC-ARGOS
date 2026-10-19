import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, api, onUnauthorized, setToken } from '../api.js'
import { clearToken, decodeToken, loadToken, saveToken } from '../session.js'
import { AuthContext } from './AuthContext.js'

const anonymous = { status: 'anonymous', session: null }

function initialState() {
  const token = loadToken()
  if (!token) return anonymous
  setToken(token)
  return { status: 'authenticated', session: decodeToken(token) }
}

/** status: 'authenticated' or 'anonymous'; the token lives in localStorage between reloads. */
export function AuthProvider({ children }) {
  const [state, setState] = useState(initialState)

  const logout = useCallback(() => {
    clearToken()
    setToken(null)
    setState(anonymous)
  }, [])

  useEffect(() => onUnauthorized(logout), [logout])

  // Log out exactly when the JWT expires instead of waiting for the next failing request.
  useEffect(() => {
    if (!state.session) return undefined
    const timer = setTimeout(logout, Math.max(0, state.session.expiresAt - Date.now()))
    return () => clearTimeout(timer)
  }, [state.session, logout])

  const authenticate = useCallback(async (path, body, rejectedMessage) => {
    let response
    try {
      response = await api.post(path, body)
    } catch (error) {
      // The API answers 401 with an empty body for bad credentials and for a duplicate e-mail.
      if (error.status === 401 || error.status === 400) throw new ApiError(error.status, rejectedMessage)
      throw error
    }
    const session = decodeToken(response.token)
    if (!session) throw new ApiError(0, 'Token inválido recebido da API')
    saveToken(response.token)
    setToken(response.token)
    setState({ status: 'authenticated', session })
  }, [])

  const value = useMemo(
    () => ({
      ...state,
      login: (email, password) => authenticate('/auth/login', { email, password }, 'E-mail ou senha inválidos'),
      register: (fields) =>
        authenticate('/auth/register', fields, 'Não foi possível criar a conta (e-mail já em uso?)'),
      logout,
    }),
    [state, authenticate, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
