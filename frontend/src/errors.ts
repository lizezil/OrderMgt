/**
 * A caught value is only trustworthy as an Error; anything else (a rejected
 * non-Error, a thrown string) gets the caller's fallback label instead.
 */
export function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}
