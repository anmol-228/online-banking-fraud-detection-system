import { Link } from 'react-router-dom';

/**
 * Where a REAL fraud victim should go. Static information only.
 *
 * <p>This application is a simulation. It has no connection to any bank, police force or regulator
 * and never sends them anything: there is no "report" button and no case data is passed to any of
 * the links below. The page exists so that nobody mistakes an exported evidence file for a report
 * to the authorities.</p>
 */
export default function ReportFraudHelpPage() {
  return (
    <div className="page">
      <header className="page__header">
        <div>
          <h1 className="page__title">If this were real: official reporting channels</h1>
          <p className="page__subtitle">Information only. Nothing on this page sends anything anywhere.</p>
        </div>
        <Link to="/" className="button button--ghost">
          Back
        </Link>
      </header>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">This is a simulation</h2>
        </div>
        <p className="card__note">
          The Online Banking &amp; Fraud Detection System is an educational simulation. It moves no
          real money, is not connected to a real financial institution, and cannot report anything to a
          bank, the police or a regulator. An evidence file or a mail draft prepared here is practice
          material for the team running the demonstration, not a complaint.
        </p>
      </section>

      <section className="card">
        <div className="card__header">
          <h2 className="card__title">If a real person lost money to online fraud in India</h2>
        </div>
        <ul className="card__note">
          <li>
            Contact your bank immediately, using the number printed on your card or on the bank&apos;s
            official website, and ask for the transaction to be blocked.
          </li>
          <li>
            Report cyber financial fraud on the National Cyber Crime Reporting Portal,{' '}
            <a href="https://cybercrime.gov.in" target="_blank" rel="noreferrer">
              cybercrime.gov.in
            </a>
            , or by calling the helpline <strong>1930</strong>. Reporting quickly improves the chance
            of stopping the money.
          </li>
          <li>Keep screenshots, transaction references and messages as evidence.</li>
        </ul>
        <p className="card__disclaimer">
          Details and numbers change. Always confirm them on the official websites before relying on
          this page. It is not legal or financial advice.
        </p>
      </section>
    </div>
  );
}
