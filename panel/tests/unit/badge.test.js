import { describe, expect, it } from "vitest";
import { stateBadge } from "../../src/lib/badge.js";

describe("stateBadge", () => {
  it("maps online to a green badge", () => {
    const b = stateBadge("online");
    expect(b.label).toBeTruthy();
    expect(b.className).toContain("badge-online");
  });

  it("maps offline distinctly", () => {
    const b = stateBadge("offline");
    expect(b.className).toContain("badge-offline");
    expect(stateBadge("online").className).not.toBe(b.className);
  });

  it("maps syncing and error states", () => {
    expect(stateBadge("syncing").className).toContain("badge-syncing");
    expect(stateBadge("error").className).toContain("badge-error");
  });

  it("falls back to unknown for foreign states", () => {
    expect(stateBadge("exploded").className).toContain("badge-unknown");
  });
});
