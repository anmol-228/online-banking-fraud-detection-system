import { IS_SHOWCASE, REPOSITORY_URL } from '../config/appMode.js';
import bankingService from '../services/bankingService.js';

/**
 * A single unobtrusive strip shown only in the public showcase build, making it clear that the
 * data is simulated and that no backend is connected.
 *
 * It renders nothing at all in full-stack mode.
 */
export default function ShowcaseBanner() {
  if (!IS_SHOWCASE) {
    return null;
  }

  function resetDemo() {
    const confirmed = window.confirm(
      'Reset the demo? This clears the simulated transfers, alerts and complaints you have created, and signs you out.'
    );
    if (!confirmed) {
      return;
    }
    bankingService.resetShowcaseData();
    window.location.reload();
  }

  return (
    <div className="showcase-banner">
      <span className="showcase-banner__tag">Demo</span>
      <span className="showcase-banner__text">
        Frontend showcase running on simulated data — no backend is connected and no real money is
        involved. The full Spring Boot and MySQL implementation is in the{' '}
        <a href={REPOSITORY_URL} target="_blank" rel="noreferrer">
          repository
        </a>
        .
      </span>
      <button type="button" className="showcase-banner__reset" onClick={resetDemo}>
        Reset demo
      </button>
    </div>
  );
}
