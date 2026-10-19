const STORAGE_KEY = 'argos.token'

/** Reads the claims of the JWT without verifying it: the API is the one that validates the signature. */
export function decodeToken(token) {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const claims = JSON.parse(atob(payload))
    if (!claims.exp || claims.exp * 1000 <= Date.now()) return null
    return {
      userId: claims.sub,
      tenantId: claims.tenant_id,
      role: String(claims.role ?? '').replace(/^ROLE_/, ''),
      expiresAt: claims.exp * 1000,
    }
  } catch {
    return null
  }
}

export function loadToken() {
  try {
    const token = localStorage.getItem(STORAGE_KEY)
    return token && decodeToken(token) ? token : null
  } catch {
    return null
  }
}

export function saveToken(token) {
  try {
    localStorage.setItem(STORAGE_KEY, token)
  } catch {
    // Private mode / blocked storage: the session simply won't survive a reload.
  }
}

export function clearToken() {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing to clear.
  }
}
