/**
 * How the browser should read a failure of the "who am I" call made when the page opens.
 *
 * The token must be discarded only when the server has actually rejected it. A hosting platform
 * that is waking up (502/503/504), a rate limit (429), a database that is resuming (5xx) or a
 * dropped connection says nothing about whether the token is valid, and signing the user out for
 * those turns a few seconds of server start-up into a lost session.
 */

/** The server looked at the token and refused it. */
export function isAuthRejection(error) {
  return error?.response?.status === 401;
}

/** The server could not answer right now; the token's validity is unknown. */
export function isTransientFailure(error) {
  const status = error?.response?.status;
  if (status === undefined || status === null) {
    return true; // no response at all
  }
  return status === 408 || status === 425 || status === 429 || status >= 500;
}
