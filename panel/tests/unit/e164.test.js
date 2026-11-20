import { describe, expect, it } from "vitest";
import { formatE164 } from "../../src/lib/e164.js";

describe("formatE164", () => {
  it("formats Egyptian mobile with default CC", () => {
    expect(formatE164("01012345678")).toBe("+201012345678");
  });

  it("strips separators and plus signs before formatting", () => {
    expect(formatE164("+20 (10) 123-45678")).toBe("+201012345678");
  });

  it("passes through international numbers verbatim", () => {
    expect(formatE164("+14155552671")).toBe("+14155552671");
  });

  it("rejects fewer than 10 digits", () => {
    expect(formatE164("010123456")).toBeNull();
  });

  it("rejects more than 15 digits", () => {
    expect(formatE164("0101234567890123")).toBeNull();
  });

  it("returns null for junk input", () => {
    expect(formatE164("abcdef")).toBeNull();
  });
});
