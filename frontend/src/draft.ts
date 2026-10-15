/**
 * The unsent draft, kept across the loss of a session (US-6.4).
 *
 * <p>A session that expires sends the customer back to the login page, and that is one thing; wiping
 * the sentence they were halfway through typing is another, and the second must not be a side effect
 * of the first. So the composer parks its text here and picks it up again after signing back in.
 *
 * <p>Keyed by customer id: on a shared browser the next person to sign in gets their own empty
 * composer, not the previous person's half-written order. `sessionStorage`, not `localStorage` --
 * it belongs to this tab and this sitting, and closing the browser should forget it.
 */
const KEY_PREFIX = 'ordermgt.draft.'

function keyFor(customerId: string): string {
  return `${KEY_PREFIX}${customerId}`
}

/** Stores this customer's draft; an empty draft clears the entry rather than leaving a blanked one. */
export function stashDraft(customerId: string, text: string): void {
  try {
    if (text) sessionStorage.setItem(keyFor(customerId), text)
    else sessionStorage.removeItem(keyFor(customerId))
  } catch {
    // Storage can be unavailable (private mode, a blocked origin). Losing the draft then is a
    // smaller failure than breaking the composer over it.
  }
}

/** The sentence this customer never sent, or an empty string when there is none. */
export function takeDraft(customerId: string | undefined): string {
  if (!customerId) return ''
  try {
    return sessionStorage.getItem(keyFor(customerId)) ?? ''
  } catch {
    return ''
  }
}
