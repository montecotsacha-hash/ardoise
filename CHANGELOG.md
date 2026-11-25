# Journal des versions

Chaque version d'Ardoise porte un **nom propre, jamais réutilisé**. Ce nom est
affiché sur le site de téléchargement, dans le titre de la release GitHub et
dans le nom de fichier des notes de version : dès qu'il change, la version a
changé.

Règle : le nom suivant est pris en tête de la liste de réserve ci-dessous, et la
liste est mise à jour dans le même commit. Ces noms sont des mots courts liés à
la navigation et au papier — rien de plus.

## Versions

- `1.6.9` — « Mâture » — 25 novembre 2026 : interface simplifiée — les dégradés disparaissent (carte de moyenne, boutons, surfaces et fond), la pastille de la barre de navigation glisse d'un onglet à l'autre avec un rebond d'icône et un libellé qui s'ouvre, et le texte passe en blanc en mode sombre pour rester lisible sur tous les tons.
- `1.6.8` — « Lisière » — 25 novembre 2026 : interface refaite façon Android 17 / Pixel Music — titre plein cadre, carte de moyenne en dégradé, puces pleines, barre de navigation flottante animée, halos de fond qui dérivent, sections sans décor. Le bouton « Télécharger la mise à jour » télécharge désormais l'APK dans l'app puis ouvre l'installateur Android, et le site garde un contenu statique lisible si son JavaScript ne démarre pas.
- `1.6.7` — « Kabig » — 25 novembre 2026 : nouvelle interface Material 3 « givrée » — fond ambient dégradé, en-têtes translucides flottants, tiroir de navigation repensé, cartes tonales et dialogues dont Android floute lui-même l'arrière-plan (API 31+), avec repli lisible quand le flou est désactivé.
- `1.6.6` — « Jetée » — 15 novembre 2026 : sélecteur de trimestre sur le tableau de bord (les périodes réelles de l'établissement : trimestres, bac blanc…). Pronote ouvrait sur un trimestre sans note : l'app bascule désormais d'elle-même sur la période précédente, et la courbe « Année » fusionne les trimestres consultés au lieu de disparaître.
- `1.6.5` — « Ivoire » — 15 novembre 2026 : l'aperçu du widget « dernière note » ne se chargeait plus dans le sélecteur du lanceur, les widgets ne suivaient pas le passage en mode sombre, et ils gardaient la moyenne à l'écran après « Supprimer mes données ».
- `1.6.4` — « Halo » — 15 novembre 2026 : Android 8.0 (API 26) devient le minimum pris en charge. L'app s'appuie sur `java.time`, absent d'Android 7.0/7.1 : elle ne pouvait pas s'y lancer. La demande de code de l'appareil passe désormais par l'API qui existe vraiment sur Android 9.
- `1.6.3` — « Gouvernail » — 15 novembre 2026 : adresse Pronote acceptée sans `https://` (avec un message quand elle est vraiment inutilisable), ligne « Alertes même application fermée » qui se met à jour au retour de l'écran système, changement de semaine qui garde le jour choisi, bouton retour qui ramène au tableau de bord depuis une section, et plus de « serveur injoignable » quand le serveur omet le nom de l'établissement.
- `1.6.2` — « Fanion » — 15 novembre 2026 : l'interrupteur « Mode sombre » du menu affiche le thème réellement appliqué, y compris quand l'app suit le mode système. Une seule bascule suffit désormais pour passer du sombre au clair.
- `1.6.1` — « Dérive » — 15 novembre 2026 : deux défauts vus en testant l'app sur émulateur — les puces de thème écrasaient le libellé « Thème » sur un écran de 360 dp, et le mois s'affichait capitalisé dans l'emploi du temps. Ajoute aussi les captures réelles de l'app au dépôt.
- `1.6.0` — « Cadran » — 14 novembre 2026 : session qui se répare toute seule (plus de déconnexion/reconnexion à faire), alertes calculées en arrière-plan toutes les heures, liste des notes allégée et fiche détaillée qui monte du bas au toucher.
- `1.5.0` — « Boussole » — 14 novembre 2026 : emploi du temps fiabilisé (une section à la fois, cours du bon jour, groupes parallèles), vérification de mise à jour depuis le site, connecteur Pronote découpé et allégé. Premier publipostage complet du site et des releases.
- `1.4.0` — « Écho » — octobre 2026 : notes, moyennes et appréciations hors ligne, thème sombre, widgets.

## Liste de réserve (noms pas encore utilisés)

Nasse, Quille, Ralingue.
