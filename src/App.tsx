import type { CSSProperties } from "react";

import { HIGHLIGHTED_RELEASES, RELEASES } from "./changelog";
import { VERSION_LABEL } from "./version";

const APK_URL = "/Ardoise.apk";
const RELEASES_URL = "https://github.com/montecotsacha-hash/ardoise/releases";
const APK_NAME = "Ardoise.apk";

/** Stagger helper: each block fades in slightly after the previous one. */
const delay = (ms: number): CSSProperties =>
  ({ "--d": `${ms}ms` }) as CSSProperties;

const features = [
  {
    title: "Hors ligne d'abord",
    body: "Consulte tes dernières notes même sans réseau. Si l'option est activée, Ardoise tente une synchronisation périodique lorsque tu es connecté.",
    accent: "var(--accent-2)",
  },
  {
    title: "Notifications locales",
    body: "Après une synchronisation en arrière-plan, Ardoise peut t'alerter localement si une nouvelle note ou une variation de moyenne est détectée. Android peut demander l'autorisation d'afficher les notifications.",
    accent: "var(--accent-3)",
  },
  {
    title: "Moyennes fiables",
    body: "Retrouve les notes et moyennes visibles dans Pronote. Si l'établissement ne fournit pas de moyenne générale, Ardoise en propose un calcul local à partir des données disponibles.",
    accent: "var(--accent-1)",
  },
  {
    title: "100 % privé",
    body: "Tes identifiants sont envoyés directement au serveur Pronote de ton établissement, sans serveur intermédiaire Ardoise. Le jeton de session est conservé chiffré sur l'appareil.",
    accent: "var(--accent-4)",
  },
];

const steps = [
  {
    n: "1",
    title: "Télécharge l'APK",
    body: "Télécharge le fichier sur ton téléphone Android depuis le bouton ci-dessous.",
  },
  {
    n: "2",
    title: "Autorise l'installation",
    body: "Android affiche « installer une application inconnue » : accepte pour ton navigateur, une seule fois.",
  },
  {
    n: "3",
    title: "Connecte-toi",
    body: "Saisis l'adresse Pronote de ton établissement, puis tes propres identifiants élève. Les accès via un ENT ne sont pas pris en charge.",
  },
];

export default function App() {
  return (
    <div className="page" translate="no">
      {/* En-tête */}
      <header className="topbar">
        <div className="brand">
          {/* L'icône de l'application, pas une pastille de couleur : c'est le
              même dessin que sur l'écran d'accueil du téléphone. */}
          <img
            src="/icon.svg"
            alt=""
            aria-hidden="true"
            width={38}
            height={38}
            className="app-icon"
          />
          <span>Ardoise</span>
        </div>
        <a href={APK_URL} download={APK_NAME} className="btn btn--small">
          Télécharger
        </a>
      </header>

      {/* Accroche + aperçu de l'application */}
      <section className="hero">
        <div className="reveal" style={delay(60)}>
          <span className="chip">
            Version {VERSION_LABEL} · Android 8.0 et plus · environ 12 Mo
          </span>
          <h1>Tes notes, enfin lisibles.</h1>
          <p className="lead">
            <strong>Ardoise</strong> est l'application mobile qui suit tes notes
            et ta moyenne Pronote — avec un accès hors ligne à tes dernières
            données et une connexion directe au serveur de ton établissement.
          </p>
          <div className="actions">
            <a href={APK_URL} download={APK_NAME} className="btn">
              ⬇️ Télécharger {APK_NAME}
            </a>
            <a href="#installation" className="btn btn--tonal">
              Voir l'installation
            </a>
          </div>
          <p className="fineprint">
            APK signé · environ 12 Mo · aucun serveur intermédiaire Ardoise
          </p>
        </div>

        {/* Mêmes surfaces que l'app : carte de moyenne pleine couleur, lignes
            tonales, aucune ombre. */}
        <div className="preview reveal" style={delay(140)} aria-hidden="true">
          <div className="average-card">
            <span className="average-card__period">1er trimestre</span>
            <div className="average-card__value">
              15,4<span> /20</span>
            </div>
            <span className="average-card__pill">Moyenne officielle</span>
          </div>
          <div className="grade-row">
            <span className="dot" style={{ background: "var(--accent-1)" }} />
            <span>Mathématiques</span>
            <strong>16/20</strong>
          </div>
          <div className="grade-row">
            <span className="dot" style={{ background: "var(--accent-2)" }} />
            <span>Histoire-géographie</span>
            <strong>12,5/20</strong>
          </div>
          <p className="preview__caption">Aperçu de l'application</p>
        </div>
      </section>

      {/* Fonctionnalités */}
      <section aria-labelledby="fonctions">
        <h2 id="fonctions" className="section-title">
          Ce que fait l'application
        </h2>
        <div className="features">
          {features.map((feature, index) => (
            <div
              key={feature.title}
              className="feature reveal"
              style={delay(160 + index * 70)}
            >
              <div className="feature__head">
                <span
                  className="dot"
                  style={{ background: feature.accent }}
                  aria-hidden="true"
                />
                <h3>{feature.title}</h3>
              </div>
              <p>{feature.body}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Nouveautés : le journal des versions, sans second fichier à tenir */}
      <section aria-labelledby="nouveautes">
        <h2 id="nouveautes" className="section-title">
          Nouveautés
        </h2>
        <div className="features">
          {RELEASES.slice(0, HIGHLIGHTED_RELEASES).map((release, index) => (
            <div
              key={release.version}
              className="feature reveal"
              style={delay(160 + index * 70)}
            >
              <div className="feature__head">
                <span
                  className="dot"
                  style={{ background: "var(--accent-2)" }}
                  aria-hidden="true"
                />
                <h3>
                  {release.version} « {release.name} »
                </h3>
              </div>
              <span className="chip release__date">{release.date}</span>
              <p>{release.summary}</p>
            </div>
          ))}
        </div>
        <p className="release__more">
          <a href={RELEASES_URL}>Tout le journal des versions</a>
        </p>
      </section>

      {/* Installation */}
      <section
        id="installation"
        className="install"
        style={{ scrollMarginTop: "24px" }}
        aria-labelledby="installation-titre"
      >
        <div className="card reveal" style={delay(200)}>
          <h2 id="installation-titre" className="section-title">
            Installation en trois étapes
          </h2>
          <div className="install__steps">
            {steps.map((step) => (
              <div key={step.n} className="step">
                <div className="step__head">
                  <span className="step__number">{step.n}</span>
                  <h3>{step.title}</h3>
                </div>
                <p>{step.body}</p>
              </div>
            ))}
          </div>
          <div className="install__cta">
            <a href={APK_URL} download={APK_NAME} className="btn">
              ⬇️ Télécharger maintenant
            </a>
          </div>
          <p className="install__note">
            L'application nécessite Android 8.0 (Oreo) ou une version plus
            récente. Comme pour toute installation hors Play Store, Android te
            demandera d'autoriser l'installation depuis ton navigateur. Ardoise
            se connecte directement au serveur Pronote de ton établissement ;
            les accès via un ENT ne sont pas pris en charge.
          </p>
        </div>
      </section>

      {/* Pied de page */}
      <footer className="footer">
        <div className="card">
          <p>
            <strong>Ardoise — application non officielle</strong>
          </p>
          <p>
            Application indépendante, non affiliée à Index-Éducation. « Pronote
            » est une marque de son éditeur ; Ardoise est un lecteur
            indépendant. Tes identifiants sont transmis directement au serveur
            Pronote de ton établissement pour la connexion ; le jeton de session
            est chiffré sur ton appareil. Aucun serveur intermédiaire Ardoise.
          </p>
        </div>
        <p>Fait avec 💜 pour les lycéens</p>
      </footer>
    </div>
  );
}
