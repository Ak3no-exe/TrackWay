# TrackWay

Application Android (Kotlin + Jetpack Compose + Room + osmdroid) de suivi de trajets GPS.

## Compiler avec GitHub Actions (le plus simple)
1. Crée un dépôt sur github.com, puis envoie tout le contenu de ce dossier (y compris le dossier `.github`).
2. Onglet **Actions** → **Build APK** → **Run workflow**.
3. Une fois terminé, ouvre l'exécution → section **Artifacts** → télécharge `app-debug` (contient `app-debug.apk`).
4. Sur le téléphone : copie l'APK, ouvre-le, autorise « sources inconnues ».

## Compiler avec Android Studio
Ouvre le dossier → attends la synchronisation Gradle → **Build > Build APK(s)**.
(Android Studio génère lui-même le Gradle Wrapper ; sinon lance `gradle wrapper --gradle-version 8.7`.)

## Utilisation
- Autorise la localisation (précise) et les notifications au premier lancement.
- **Cartes hors ligne** : avec Internet, place la carte sur la zone voulue, zoome, puis « Télécharger la zone ». Les tuiles sont stockées sur le téléphone ; le GPS et l'enregistrement fonctionnent sans Internet.
- **+ Nouveau trajet** → choisis le transport. **Terminer le trajet** pour clôturer. Onglet Trajets : voir, exporter en GPX, supprimer.
- Le suivi tourne en arrière-plan et écran verrouillé (service de premier plan + notification). Un trajet interrompu est repris au prochain lancement.

## Limites connues de cette version
Pas encore : onglet Profil/paramètres, export JSON/CSV, import, recherche/filtres/renommage, graphiques, couleurs personnalisables, miles/mph.
Les serveurs de tuiles OpenStreetMap limitent le téléchargement massif : télécharge de petites zones, ou configure ton propre serveur de tuiles pour de grandes régions.
