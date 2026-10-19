export class ApiError extends Error {
  constructor(status, detail) {
    super(detail)
    this.status = status
  }
}

let token = null
let unauthorizedListener = () => {}

/** The JWT sent as Bearer on every request; null when logged out. */
export function setToken(value) {
  token = value
}

/** Registers the callback that runs when the API says the session is gone. */
export function onUnauthorized(listener) {
  unauthorizedListener = listener
  return () => {
    unauthorizedListener = () => {}
  }
}

function parseJson(text) {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

async function request(method, path, body) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'Sem conexão com o servidor')
  }

  const data = parseJson(await response.text())
  if (response.ok) return data

  // On /auth/* a 401 means rejected credentials, not an expired session.
  if (response.status === 401 && !path.startsWith('/auth/')) {
    unauthorizedListener()
    throw new ApiError(401, 'Sua sessão expirou. Entre novamente.')
  }
  if (response.status === 403) throw new ApiError(403, 'Você não tem permissão para esta ação.')
  throw new ApiError(response.status, data?.detail || 'Algo deu errado. Tente novamente.')
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body = {}) => request('POST', path, body),
  patch: (path, body) => request('PATCH', path, body),
  put: (path, body) => request('PUT', path, body),
  delete: (path) => request('DELETE', path),
}

/** Base URL external systems should call; in development it is the Vite server, which proxies /api. */
export const publicApiUrl = () => `${window.location.origin}/api`

/** Builds "?a=1&a=2" from an object whose values may be arrays; empty values are skipped. */
export function query(params) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    for (const item of [value].flat()) {
      if (item !== undefined && item !== null && item !== '') search.append(key, item)
    }
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}
