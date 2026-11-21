import Alpine from "alpinejs";
import { parseRoute } from "./store.js";
import "./styles.css";

async function boot() {
  // MSW mocks stay the default; VITE_USE_BACKEND=true talks to the real API.
  if (!import.meta.env.VITE_USE_BACKEND) {
    const { worker } = await import("../mocks/browser.js");
    await worker.start({ quiet: true });
  }

  Alpine.data("panelRoot", () => ({
    ready: false,
    email: "",
    password: "",
    init() {
      const st = Alpine.store("panel");
      const onHash = () => st.navigate(parseRoute(window.location.hash));
      window.addEventListener("hashchange", onHash);
      onHash();
      this.ready = true;
    },
  }));

  Alpine.start();
}

boot();
