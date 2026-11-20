/** Maps device state -> {label, className} for the list badge. */
export function stateBadge(state) {
  const map = {
    online: { label: "متصل", className: "badge badge-online" },
    offline: { label: "غير متصل", className: "badge badge-offline" },
    syncing: { label: "جاري المزامنة", className: "badge badge-syncing" },
    error: { label: "خطأ", className: "badge badge-error" },
  };
  return map[state] ?? { label: "غير معروف", className: "badge badge-unknown" };
}
