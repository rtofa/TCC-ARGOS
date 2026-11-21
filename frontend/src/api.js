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
  throw failure(response.status, path, data)
}

function failure(status, path, data) {
  // On /auth/* a 401 means rejected credentials, not an expired session.
  if (status === 401 && !path.startsWith('/auth/')) {
    unauthorizedListener()
    return new ApiError(401, 'Sua sessão expirou. Entre novamente.')
  }
  if (status === 403) return new ApiError(403, 'Você não tem permissão para esta ação.')
  return new ApiError(status, data?.detail || 'Algo deu errado. Tente novamente.')
}

/** One "event:/data:" block of a server-sent events stream. */
function parseEvent(block) {
  let name = 'message'
  const data = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  return data.length ? { name, data: parseJson(data.join('\n')) } : null
}

/**
 * POSTs `body` and reads the response as server-sent events, calling `onEvent(name, data)` for each one as it
 * arrives. EventSource cannot send the Authorization header, hence fetch + a stream reader.
 */
async function stream(path, body, onEvent, { signal } = {}) {
  const headers = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
  if (token) headers.Authorization = `Bearer ${token}`

  let response
  try {
    response = await fetch(`/api${path}`, { method: 'POST', headers, body: JSON.stringify(body), signal })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new ApiError(0, 'Sem conexão com o servidor')
  }
  if (!response.ok) throw failure(response.status, path, parseJson(await response.text()))

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let match
    while ((match = /\r?\n\r?\n/.exec(buffer))) {
      const event = parseEvent(buffer.slice(0, match.index))
      buffer = buffer.slice(match.index + match[0].length)
      if (event) onEvent(event.name, event.data)
    }
  }
  const last = parseEvent(buffer)
  if (last) onEvent(last.name, last.data)
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body = {}) => request('POST', path, body),
  patch: (path, body) => request('PATCH', path, body),
  put: (path, body) => request('PUT', path, body),
  delete: (path) => request('DELETE', path),
  stream,
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
