import { ref } from 'vue'
import { TOAST_DURATION_MS } from './constants'

const current = ref('')
const isError = ref(false)
let timer: number | undefined

/**
 * A single shared toast. One at a time is the right amount for errors: the second one
 * usually has the same cause as the first.
 */
export function useToast() {
  function push(message: string, error = true): void {
    current.value = message
    isError.value = error
    window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      current.value = ''
    }, TOAST_DURATION_MS)
  }

  return { current, isError, push }
}
