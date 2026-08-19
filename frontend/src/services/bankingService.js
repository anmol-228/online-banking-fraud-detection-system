import { IS_SHOWCASE } from '../config/appMode.js';
import apiBankingService from './apiBankingService.js';
import showcaseBankingService from './showcase/showcaseBankingService.js';

/**
 * The single entry point every screen uses to reach banking data.
 *
 * Two implementations satisfy the same contract:
 *
 * - `apiBankingService`      talks to the Spring Boot backend (full-stack mode, the default)
 * - `showcaseBankingService` simulates the backend entirely in the browser (public demo build)
 *
 * The choice is made once, here, from the build-time mode. No screen contains a conditional for
 * it, which is what keeps the two modes from drifting apart.
 */
const bankingService = IS_SHOWCASE ? showcaseBankingService : apiBankingService;

export default bankingService;
