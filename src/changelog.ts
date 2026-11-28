import raw from "../CHANGELOG.md?raw";

/**
 * Une version publiée, lue depuis CHANGELOG.md.
 *
 * Le journal reste la source unique : une entrée ajoutée pour une release
 * apparaît sur le site sans second fichier à tenir à jour, et le site ne peut
 * pas annoncer une version que le journal ignore.
 */
export type Release = {
  version: string;
  name: string;
  date: string;
  summary: string;
};

/** `- \`1.6.15\` — « Étambot » — 28 novembre 2026 : …` */
const ENTRY = /^- `([^`]+)` — « ([^»]+) » — ([^:]+) : (.+?)\r?$/gm;

export const RELEASES: Release[] = [...raw.matchAll(ENTRY)].map((match) => ({
  version: match[1],
  name: match[2],
  date: match[3].trim(),
  summary: match[4].trim(),
}));

/** Combien de versions récentes la page d'accueil affiche. */
export const HIGHLIGHTED_RELEASES = 3;
