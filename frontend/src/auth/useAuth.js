import { useContext } from 'react'
import { AuthContext } from './AuthContext.js'

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>')
  return value
}

export const canWrite = (session) => session?.role === 'ADMIN' || session?.role === 'EDITOR'
