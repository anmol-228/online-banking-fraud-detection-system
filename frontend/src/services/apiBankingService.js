import api from '../api/endpoints.js';

/**
 * The full-stack implementation: every call goes to the Spring Boot backend over HTTP.
 *
 * This is a thin adapter rather than new logic, so the endpoint definitions stay in one place in
 * `api/endpoints.js` and this file only exists to give the service abstraction a second, equal
 * implementation alongside the showcase one.
 */
const apiBankingService = {
  ...api,

  /** No-op in full-stack mode: there is no simulated data to reset. */
  resetShowcaseData() {},
};

export default apiBankingService;
