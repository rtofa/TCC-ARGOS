export function tourStorageKey(userId) {
  return `argos.tourDone.${userId || 'anon'}`
}

export function hasCompletedTour(userId) {
  try {
    return localStorage.getItem(tourStorageKey(userId)) === 'true'
  } catch {
    return false
  }
}

export function markTourCompleted(userId) {
  try {
    localStorage.setItem(tourStorageKey(userId), 'true')
  } catch {
    // Ignore storage failures
  }
}
