/**
 * A single banner used for success, error and information messages.
 *
 * @param tone one of success, error, warning or info
 */
export default function Message({ tone = 'info', children, onDismiss }) {
  if (!children) {
    return null;
  }
  return (
    <div className={`message message--${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      <span>{children}</span>
      {onDismiss ? (
        <button type="button" className="message__close" onClick={onDismiss} aria-label="Dismiss">
          &times;
        </button>
      ) : null}
    </div>
  );
}
