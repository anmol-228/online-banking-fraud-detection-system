/** Shown instead of an empty table, so a screen never looks broken when there is no data yet. */
export default function EmptyState({ title, description, action }) {
  return (
    <div className="empty-state">
      <p className="empty-state__title">{title}</p>
      {description ? <p className="empty-state__description">{description}</p> : null}
      {action}
    </div>
  );
}
