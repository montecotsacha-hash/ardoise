#!/usr/bin/env node
/**
 * Corps d'une GitHub Release, tiré de CHANGELOG.md.
 *
 * Le journal est la source unique des notes de version : la release, le site
 * et l'application racontent donc la même chose. Si le tag n'a pas d'entrée,
 * le script échoue avant de publier une release vide.
 *
 * Usage : node scripts/release-notes.mjs v1.6.15 > release-notes.md
 */
import { readFileSync } from "node:fs";

const version = (process.argv[2] ?? "").replace(/^v/, "");
if (!version) {
  console.error("Usage : node scripts/release-notes.mjs <tag>");
  process.exit(1);
}

const journal = readFileSync(new URL("../CHANGELOG.md", import.meta.url), "utf8");
const line = journal
  .split("\n")
  .find((entry) => entry.startsWith(`- \`${version}\``));

const parsed = line?.match(/^- `([^`]+)` — « ([^»]+) » — ([^:]+) : (.+?)\r?$/);
if (!parsed) {
  console.error(
    `Aucune entrée \`${version}\` dans CHANGELOG.md : la release n'a pas de notes.`,
  );
  process.exit(1);
}

const [, number, name, date, summary] = parsed;

console.log(`# Ardoise ${number} « ${name} »`);
console.log("");
console.log(summary.trim());
console.log("");
console.log(`_${date.trim()}_`);
console.log("");
console.log(
  "APK signé (clé d'upload) à installer sur Android 8.0 et plus, et AAB " +
    "correspondant pour les magasins. Vérifie l'empreinte SHA-256 dans " +
    "`SHA256SUMS.txt` avant d'installer.",
);
console.log("");
console.log(
  "Mise à jour automatique sur le téléphone : ajoute cette page de releases " +
    "dans Obtainium.",
);
