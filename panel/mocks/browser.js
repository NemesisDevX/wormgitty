import { setupWorker } from "msw/browser";
import { handlers, captured, faultControl } from "./handlers.js";

export const worker = setupWorker(...handlers);

if (typeof window !== "undefined") {
  window.__wg_captured = captured;
  window.__wg_fault = faultControl;
}
