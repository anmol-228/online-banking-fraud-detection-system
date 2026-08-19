/** Loading indicator shown while a screen waits for the backend. */
export default function Loader({ label = 'Loading' }) {
  return (
    <div className="loader" role="status" aria-live="polite">
      <span className="loader__spinner" aria-hidden="true" />
      <span>{label}...</span>
    </div>
  );
}
