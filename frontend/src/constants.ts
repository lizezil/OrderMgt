/**
 * Magic numbers and screen-level knobs, in one place. Values the backend also knows (page size,
 * customer id) are duplicated on purpose -- the two sides deploy separately -- but each side owns
 * exactly one copy, here.
 */

/** Page size for the orders list; mirrors the backend's `Paging.DEFAULT_SIZE`. */
export const DEFAULT_PAGE_SIZE = 20

/** Where the orders list and its pager start. */
export const FIRST_PAGE = 1

/** Customer id used when the browser has none: the demo's first customer. */
export const DEFAULT_CUSTOMER_ID = '1001'

/** How long a toast stays on screen. */
export const TOAST_DURATION_MS = 5000

/** First id handed to a message that is not persisted yet; negative so it never collides. */
export const LOCAL_MESSAGE_ID_START = -1

/** Stream-failure reconciler: pause between history reads, and how many reads it makes. */
export const RECONCILE_DELAY_MS = 1500
export const RECONCILE_ATTEMPTS = 3

/** A card reads "N minutes to expiry" until this many remain, then just "expiring soon". */
export const EXPIRY_SOON_MINUTES = 1

export const MS_PER_MINUTE = 60_000
