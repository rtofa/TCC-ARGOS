import { useEffect, useState } from 'react'
import { api } from '../api.js'

/**
 * Runs `load(key)` whenever `key` changes and keeps the last good data on screen while the next
 * one is loading. `load` must be a stable (module-level) function. Pass a null key to skip.
 */
export function useAsync(key, load) {
  const [reloads, setReloads] = useState(0)
  const [result, setResult] = useState({})

  useEffect(() => {
    if (key === null) return undefined
    let cancelled = false
    load(key).then(
      (data) => !cancelled && setResult({ key, reloads, data }),
      (error) => !cancelled && setResult({ key, reloads, error }),
    )
    return () => {
      cancelled = true
    }
  }, [key, reloads, load])

  const current = result.key === key && result.reloads === reloads
  return {
    data: result.data,
    error: current ? result.error : undefined,
    loading: key !== null && !current,
    reload: () => setReloads((n) => n + 1),
  }
}

/** GET `path` from the API. */
export const useApi = (path) => useAsync(path, api.get)
