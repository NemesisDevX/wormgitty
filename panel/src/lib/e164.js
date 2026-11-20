const AR_CC = "20";

/** Formats raw digit soup into E.164 with Egyptian default country code. */
export function formatE164(input) {
  const digits = String(input ?? "").replace(/\D/g, "");
  if (digits.length < 10 || digits.length > 15) return null;
  let rest;
  if (digits.startsWith("20") && digits.length >= 12) {
    rest = digits.slice(2);
  } else if (digits.startsWith("0")) {
    rest = digits.replace(/^0+/, "");
  } else {
    return `+${digits}`;
  }
  if (rest.length < 9 || rest.length > 14) return null;
  return `+${AR_CC}${rest}`;
}
