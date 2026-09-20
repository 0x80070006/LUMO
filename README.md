# LUMO

**LUMO** est une application Android permettant d'utiliser une interface LUMO / Jellyfin directement depuis un téléphone, avec une expérience adaptée au mobile.

L'application embarque l'interface web dans une **WebView Android**, conserve l'adresse du serveur et la session de connexion, et ajoute plusieurs fonctions natives autour de la lecture multimédia.

---

## 📦 Télécharger LUMO

<p align="center">

[![LUMO Android](https://img.shields.io/badge/Android-Télécharger_LUMO_1.2-3DDC84?style=for-the-badge\&logo=android\&logoColor=white)](https://github.com/0x80070006/LUMO/releases/download/lumo_v1.2/LUMO-1.2.apk)

[![Release](https://img.shields.io/badge/GitHub-Release_lumo__v1.2-181717?style=for-the-badge\&logo=github\&logoColor=white)](https://github.com/0x80070006/LUMO/releases/tag/lumo_v1.2)

</p>

### Dernière version

**LUMO 1.2**

👉 https://github.com/0x80070006/LUMO/releases/tag/lumo_v1.2

L'APK Android est disponible dans la section **Assets** de la release.

---

## 📱 À propos

LUMO transforme l'interface web LUMO / Jellyfin en une application Android dédiée.

Au premier lancement, l'application demande simplement l'adresse de votre interface web.

Exemple :

```text
https://mon-serveur-lumo.example.com/
```

Une fois enregistrée :

* l'adresse n'a plus besoin d'être saisie ;
* la WebView ouvre directement LUMO ;
* la session utilisateur est conservée ;
* la page de connexion LUMO/Jellyfin reste fonctionnelle ;
* la navigation se comporte comme dans la version web.

---

## ✨ Fonctionnalités

### 🌐 Interface LUMO intégrée

L'application affiche directement l'interface web LUMO dans une WebView Android.

Elle prend notamment en charge :

* authentification LUMO / Jellyfin ;
* cookies et session persistante ;
* JavaScript ;
* lecture vidéo ;
* navigation dans les films et séries ;
* navigation Retour Android ;
* stockage de l'adresse du serveur ;
* récupération automatique après une erreur de connexion.

---

## 🎬 Lecture vidéo

LUMO 1.2 améliore la compatibilité avec le lecteur vidéo utilisé par l'interface LUMO et Jellyfin.

L'application détecte le lecteur multimédia même lorsqu'il est chargé dans une **iframe Jellyfin**.

Lorsqu'une lecture reste bloquée dans l'interface intégrée, LUMO peut basculer vers le lecteur Jellyfin afin de permettre le démarrage du film ou de l'épisode.

---

## 🔔 Contrôles multimédia Android

Pendant la lecture, LUMO utilise les fonctions multimédia natives d'Android.

Une notification de lecture permet d'afficher :

* 🎞️ le titre du film ou de l'épisode ;
* 🖼️ la jaquette du média ;
* ⏮️ précédent ;
* ⏯️ lecture / pause ;
* ⏭️ suivant ;
* la progression de la lecture ;
* les informations de la lecture en cours.

Les commandes restent accessibles depuis Android sans avoir besoin de revenir immédiatement dans l'application.

---

## 🔒 Mise en veille

Lorsque le téléphone est mis en veille pendant la lecture :

```text
Lecture vidéo
      ↓
Écran verrouillé
      ↓
Vidéo mise en pause
      ↓
Contrôles multimédia toujours disponibles
```

Cela évite qu'un film ou un épisode continue à jouer inutilement lorsque le téléphone est verrouillé.

---

## 🖼️ Picture-in-Picture

LUMO prend en charge le mode **Picture-in-Picture** d'Android.

Lorsque vous revenez à l'écran d'accueil pendant une lecture, la vidéo peut continuer dans une petite fenêtre flottante au-dessus des autres applications.

Cela permet par exemple de :

* ouvrir une autre application ;
* répondre à un message ;
* consulter une page web ;
* continuer à regarder la vidéo.

Lorsque la fenêtre Picture-in-Picture est fermée, LUMO arrête ou met en pause la lecture.

---

## 📺 Plein écran

Pendant l'utilisation de LUMO, l'application utilise un mode immersif afin d'utiliser au maximum l'écran du téléphone.

La barre de statut Android peut être masquée afin que l'interface LUMO occupe tout l'écran.

Cela est particulièrement utile pendant la lecture vidéo.

---

## ⚙️ Premier lancement

Lors du premier démarrage, LUMO affiche un écran de configuration.

Entrez l'adresse de votre serveur :

```text
Adresse LUMO :
https://votre-serveur.example.com/
```

Puis appuyez sur :

```text
Ouvrir LUMO
```

L'adresse est ensuite enregistrée localement.

Au lancement suivant :

```text
Lancement de l'application
        ↓
Adresse déjà enregistrée
        ↓
Chargement de LUMO
        ↓
Session existante restaurée
```

Il n'est donc pas nécessaire de saisir l'adresse à chaque démarrage.

---

## 🔄 Changer l'adresse du serveur

Si le serveur est inaccessible, LUMO affiche un écran de récupération.

Deux possibilités sont disponibles :

```text
Réessayer
```

pour tenter de recharger le serveur actuel.

Ou :

```text
Changer l'adresse
```

pour configurer un nouveau serveur LUMO.

---

## 🔐 Utilisation avec Tailscale

LUMO peut être utilisé avec un serveur uniquement accessible à travers **Tailscale**.

C'est particulièrement pratique pour utiliser Jellyfin à distance sans exposer directement le serveur multimédia sur Internet.

Architecture typique :

```text
┌───────────────────┐
│    Téléphone      │
│ Android /         │
│ GrapheneOS        │
└─────────┬─────────┘
          │
          │ Tailscale
          │
┌─────────▼─────────┐
│   Serveur LUMO    │
│    + Jellyfin     │
└───────────────────┘
```

Avant d'ouvrir LUMO, vérifiez que **Tailscale est connecté sur le téléphone**.

---

## 📱 GrapheneOS

L'application est conçue pour fonctionner sur Android moderne et peut être utilisée sur **GrapheneOS**.

Aucun navigateur externe n'est nécessaire pour utiliser l'interface LUMO une fois l'application configurée.

Pour une utilisation avec un serveur Tailscale privé :

1. installer Tailscale ;
2. connecter le téléphone au Tailnet ;
3. installer LUMO ;
4. autoriser les notifications ;
5. entrer l'adresse du serveur LUMO ;
6. se connecter à son compte ;
7. lancer un film ou une série.

---

## 🔔 Autorisation des notifications

Sur les versions modernes d'Android, LUMO peut demander l'autorisation d'envoyer des notifications.

Il est recommandé de l'autoriser afin de bénéficier des contrôles multimédia :

```text
Titre
Jaquette
Précédent
Lecture / Pause
Suivant
Progression
```

Sans cette autorisation, la lecture vidéo reste possible mais certains contrôles système peuvent ne pas être affichés.

---

## 🔊 Session multimédia

LUMO communique avec Android à travers une session multimédia.

Cela permet au système de connaître :

```text
Média actuel
Titre
Jaquette
État lecture / pause
Position
Durée
Actions disponibles
```

Les contrôles Android peuvent ainsi rester synchronisés avec le lecteur LUMO/Jellyfin.

---

## 🍿 Jellyfin

Le lecteur LUMO 1.2 prend en charge le
