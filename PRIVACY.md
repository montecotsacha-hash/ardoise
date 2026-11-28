# Politique de confidentialité — Ardoise

Dernière mise à jour : 28 novembre 2026.

Ardoise est une application Android indépendante, non affiliée à Index-Éducation.
Elle permet de consulter les notes, moyennes et l'emploi du temps d'un compte
Pronote. Ce document décrit exactement les données traitées par l'application ;
il est rédigé à partir de son code source.

## Aucune donnée ne passe par un serveur Ardoise

Ardoise n'exploite **aucun serveur applicatif**. Il n'y a ni compte Ardoise, ni
collecte, ni télémétrie, ni publicité, ni SDK analytique ou publicitaire dans
l'application. Le site de distribution du projet sert uniquement la page de
téléchargement, l'APK et un fichier `version.json` public ; il ne reçoit aucune
donnée d'utilisateur.

## Données transmises

- **Recherche de l'établissement** : quand l'utilisateur écrit le nom de sa
  ville sur l'écran de connexion, ce seul nom de ville est envoyé à la Base
  Adresse Nationale (`api-adresse.data.gouv.fr`) pour obtenir ses coordonnées,
  puis ces coordonnées à l'annuaire public d'Index Éducation
  (`index-education.com`), qui renvoie la liste des établissements PRONOTE
  proches avec leur nom et leur adresse. Ces deux requêtes ne contiennent ni
  identifiant, ni donnée scolaire, ni identifiant d'appareil. Saisir l'adresse
  à la main n'envoie rien à ces deux services.
- **Adresse de l'établissement et identifiants Pronote** : transmis uniquement au
  serveur Pronote de l'établissement saisi par l'utilisateur, pour ouvrir la
  session. C'est le seul destinataire.
- **Données scolaires** (notes, moyennes, emploi du temps, informations de
  l'élève renvoyées par Pronote) : récupérées depuis ce même serveur pour
  l'affichage dans l'application.
- **Vérification de mise à jour** : au lancement, l'application interroge le
  site de distribution (`version.json`, un fichier public) pour savoir si une
  version plus récente existe. Cette requête ne contient ni identifiant ni
  donnée scolaire — au même titre qu'une visite ordinaire du site. La vérification
  se lance aussi à la main dans les paramètres.

L'éditeur d'Ardoise ne reçoit aucune de ces données et n'y a aucun accès. Les
accès via un ENT ne sont pas pris en charge par l'application.

## Données stockées sur l'appareil

- **Identifiant, mot de passe et jeton de session Pronote** : conservés
  chiffrés sur l'appareil via le magasin de clés Android (Android Keystore,
  `EncryptedSharedPreferences`, AES-256). Ils ne sont jamais écrits en clair,
  jamais journalisés, et sont effacés par « Se déconnecter » ou « Supprimer mes
  données » dans les paramètres de l'application.
- **Cache des données scolaires** : conservé localement pour l'affichage hors
  ligne.

Rien n'est sauvegardé dans le cloud : les sauvegardes Android sont désactivées
(`allowBackup="false"`), donc ces données ne quittent pas l'appareil.

## Autorisations demandées

| Autorisation           | Usage                                                                                                         |
| ---------------------- | ------------------------------------------------------------------------------------------------------------- |
| `INTERNET`             | joindre le serveur Pronote de l'établissement, l'annuaire des établissements et le site de distribution (mise à jour) |
| `ACCESS_NETWORK_STATE` | savoir si l'appareil est connecté (mode hors ligne, synchronisation)                                          |
| `POST_NOTIFICATIONS`   | notifications **locales** après une synchronisation (optionnel)                                               |
| `USE_BIOMETRIC`        | verrouillage de l'application (optionnel : empreinte, visage ou code de l'appareil) — aucune donnée transmise |

## Diagnostic d'adresse et QR code

Le bouton « Vérifier cette adresse » lit la page publique de l'établissement
(la même page que le connecteur, sans identifiant ni mot de passe) pour dire si
l'adresse existe, si le serveur est en maintenance ou si l'IP de l'appareil est
temporairement bloquée. Le bouton « Scanner le QR code » ouvre l'interface de
scan de Google Code Scanner, fournie par les services Google Play sur
l'appareil : l'image scannée n'est pas envoyée à Google, seule l'adresse PRONOTE
qu'elle contient est reprise dans le champ d'adresse.

## Gestionnaire de mots de passe Android

L'écran de connexion déclare son champ d'identifiant et son champ de mot de
passe comme tels auprès d'Android. Le gestionnaire de mots de passe de
l'appareil peut donc proposer de remplir le formulaire ou d'enregistrer les
identifiants. Cette proposition vient d'Android et reste sur l'appareil :
l'application ne lit ni ne transmet le contenu du gestionnaire.

## Enfants et données scolaires

L'application n'affiche que les données du compte Pronote dont l'utilisateur
saisit lui-même les identifiants. Aucune donnée n'est partagée avec un tiers,
revendue ou exploitée à d'autres fins.

## Copies, suppression, questions

« Supprimer mes données » dans les paramètres de l'application efface
définitivement les identifiants et le cache de l'appareil. Pour toute question ou
demande relative à ces données, ouvrir un ticket sur le dépôt du projet :
<https://github.com/Martineleve-hash/clay-pronote/issues>.

## Modifications

Toute évolution de cette politique sera publiée à cette adresse, avec sa date de
mise à jour.
