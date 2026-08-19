/**
 * Which data source the application runs against.
 *
 * - `api`      : the normal full-stack mode. Every call goes to the Spring Boot backend.
 * - `showcase` : a self-contained frontend demonstration using simulated data, used for the
 *                public GitHub Pages build where no backend is deployed.
 *
 * The mode is fixed at build time by VITE_APP_MODE and defaults to `api`, so local development
 * and the full-stack setup are unaffected by the existence of showcase mode.
 */
export const APP_MODE = import.meta.env.VITE_APP_MODE === 'showcase' ? 'showcase' : 'api';

export const IS_SHOWCASE = APP_MODE === 'showcase';

/** Link shown in the showcase banner so a visitor can find the full source. */
export const REPOSITORY_URL =
  import.meta.env.VITE_REPOSITORY_URL ||
  'https://github.com/anmol-228/online-banking-fraud-detection-system';
