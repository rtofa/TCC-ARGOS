import { useEffect } from 'react'

/**
 * Shows the <dialog> behind `ref` as a modal while the component is mounted. Pair it with
 * onCancel={closeOnCancel(onClose)} and never listen to the native "close" event: that event
 * also fires when the effect cleans up, and StrictMode runs that cleanup once on mount in
 * development, which used to close the dialog right after it opened.
 */
export function useModal(ref) {
  useEffect(() => {
    const element = ref.current
    element.showModal()
    return () => element.close()
  }, [ref])
}

/** Esc closes through the parent, so the parent stays the only owner of "is it open". */
export const closeOnCancel = (onClose) => (event) => {
  event.preventDefault()
  onClose()
}
