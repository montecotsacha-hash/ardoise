import { describe, expect, it } from "vitest";

import { RELEASES } from "./changelog";
import { VERSION, VERSION_CODENAME } from "./version";

/**
 * La page « Nouveautés » lit CHANGELOG.md : si la lecture se casse (format de
 * ligne, fins de ligne CRLF), le site afficherait silencieusement une liste
 * vide. Ces contrôles gardent la source unique honnête.
 */
describe("journal des versions sur le site", () => {
  it("affiche la version publiée en tête", () => {
    expect(RELEASES[0]?.version).toBe(VERSION);
    expect(RELEASES[0]?.name).toBe(VERSION_CODENAME);
  });

  it("donne une date et un résumé à chaque version", () => {
    expect(RELEASES.length).toBeGreaterThan(4);
    for (const release of RELEASES) {
      expect(release.summary.length).toBeGreaterThan(20);
      expect(release.date).toMatch(/\d{4}$/);
    }
  });
});
