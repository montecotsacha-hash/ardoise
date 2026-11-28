import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import { VERSION, VERSION_CODENAME } from "./version";

/**
 * Le site publié est `deploy/assets` : c'est ce dossier que l'outil de
 * déploiement envoie. Il a déjà servi un build en 1.6.9 pendant que la 1.6.12
 * était publiée, parce que rien ne comparait ce dossier à la version du dépôt.
 *
 * Ces contrôles tournent avec `bun run test` (et donc avant chaque commit) :
 * un `deploy/assets` périmé ne passe plus.
 */
const ASSETS = join(process.cwd(), "deploy/assets");
const INDEX = join(ASSETS, "index.html");
const html = existsSync(INDEX) ? readFileSync(INDEX, "utf8") : "";
const bundle = html.match(/index-[A-Za-z0-9_-]+\.js/)?.[0] ?? "";
const bundlePath = bundle ? join(ASSETS, "assets", bundle) : "";

describe("site prêt à déployer", () => {
  it("existe et référence un bundle présent", () => {
    expect(existsSync(INDEX), "deploy/assets/index.html absent").toBe(true);
    expect(bundle, "aucun bundle JS référencé par la page").not.toBe("");
    expect(existsSync(bundlePath), `${bundle} absent de deploy/assets/assets`).toBe(
      true,
    );
  });

  it("sert la version du dépôt, pas celle d'un ancien build", () => {
    const js = existsSync(bundlePath) ? readFileSync(bundlePath, "utf8") : "";
    expect(js, `${bundle} n'annonce pas ${VERSION}`).toContain(`"${VERSION}"`);
    expect(js, `${bundle} n'annonce pas ${VERSION_CODENAME}`).toContain(
      `"${VERSION_CODENAME}"`,
    );
  });

  it("n'embarque pas l'APK, servi depuis R2 par le Worker", () => {
    expect(existsSync(join(ASSETS, "Ardoise.apk"))).toBe(false);
  });
});
