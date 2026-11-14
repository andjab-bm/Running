Je souhaite démarrer un projet personnel de suivi de bien-être physique (self-hosted, open-source/privé, sans paywall). Je vois plusieux volets.

- Courses à pied
- Alimentation
- Suivi poids

Mais là je souhaiterais que l'on s'intèresse à un module de suivi d'activités de course à pied (pour débutant), combinant le meilleur de Strava (analyse, segments, design) et de Garmin (guidage pendant l'effort, séances structurées, récupération).

### 1. Contexte Matériel & Écosystème de Capture
- **Équipement actuel :** Smartphone Google Pixel 8 (Android) + montre connectée Samsung Galaxy Watch 4 (Wear OS).
- **Objectif de confidentialité :** Éviter autant que possible l'envoi de mes données vers des serveurs tiers (Strava, Samsung, etc.).
- **Synchronisation :** Synchronisation locale automatisée dès que le téléphone se connecte à mon réseau Wi-Fi domestique (ou via Health Connect en local sur Android comme passerelle si nécessaire, sans dépendance cloud). Import/Export manuel de fichiers `.gpx` et `.fit` (pour récupérer mon historique existant et exporter des parcours).
- **Données à capturer :** Trace GPS, distance, allure (instantanée, moyenne, ajustée à la pente), temps écoulé, temps en mouvement vs temps total (auto-pause), dénivelé (D+/D-), cadence, fréquence cardiaque (depuis la montre).

### 2. Périmètre Fonctionnel

#### A. Application Web (Tableau de bord & Analyse - style Strava)
- **Vue détaillée d'une sortie :**
  - **Carte interactive (Map)** soignée avec tracé GPS coloré selon l'allure ou la FC, profil d'altitude synchronisé avec le survol de la carte.
  - **Découpage (Splits & Laps) :** Tableau des temps au kilomètre ET des tours manuels (Laps), avec allure moyenne, dénivelé et FC moyenne.
  - **Analyse de l'effort :** Répartition du temps dans les zones de fréquence cardiaque et score d'effort relatif.
  - **Journal de bord :** Titre, description, ressenti de l'effort (RPE) et tags (*Endurance fondamentale, Fractionné, Compétition*).
- **Segments & Performances :**
  - **Segments personnels :** Détection automatique sur mes portions de parcours récurrentes pour comparer mes chronos.
  - **Records Personnels (PR) & Prédictions :** Suivi des meilleurs temps (400m, 1km, 5km, 10km, Semi, Marathon) + estimation des chronos potentiels (façon Garmin *Race Predictor*).
- **Suivi d'entraînement, Récupération & Équipement :**
  - **Statistiques & Tendances :** Calendrier heatmap annuel, volume hebdo/mensuel, courbes de progression.
  - **Charge & Récupération :** Suivi de la charge d'entraînement et estimation du temps de récupération conseillé (façon Garmin).
  - **Planificateur de séances & parcours :** Création de séances de fractionné structurées et import de traces `.gpx` à envoyer vers le mobile/la montre.

#### B. Application Mobile (Android / Pixel 8 - style Garmin)
- **Enregistrement & Ergonomie :** Démarrer, mettre en pause (manuelle ou auto-pause), marquer un tour manuel (**Bouton Lap**), et arrêter la course. Mode verrouillage tactile (anti-pluie/transpiration).
- **Écrans personnalisables en direct :** Navigation entre plusieurs vues (Métriques principales avec code couleur zone cardio, Tour en cours, Carte de suivi d'itinéraire GPX).
- **Coach d'entraînement en direct :**
  - **Fractionné guidé :** Exécution des séances structurées avec alertes vibration/vocales/sonores aux changements de phase.
  - **Virtual Pacer / Stratégie d'allure (façon PacePro) :** Indicateur d'avance/retard par rapport à une allure cible (fixe ou ajustée au dénivelé du parcours).
  - Annonces vocales optionnelles à chaque kilomètre.
- **Historique :** Consultation des courses synchronisées et des statistiques de base.

#### C. Application Montre (Bonus - Wear OS / Galaxy Watch 4)
- Contrôle de la session (Start, Pause, Lap manuel, Stop), affichage des écrans de métriques essentielles au poignet, alertes de vibration pour le fractionné/allure, et remontée continue de la fréquence cardiaque.

### 3. Design, UX & Livrable Attendu Avant Code
- **Exigence esthétique :** L'interface (UI/UX) est primordiale (contrairement à des alternatives comme FitoTrack que je trouve trop austères). Je veux un design moderne, épuré, lisible et soigné.
- **Thèmes :** Support natif d'un **Dark Mode** et d'un Light Mode.
- **Validation visuelle :** Avant toute implémentation technique, génère un **artefact visuel (maquette interactive / prototype UI)** d'une page de détail d'activité sur le web (incluant la carte avec tracé, le profil d'altitude, les splits, les zones cardio et le switch Dark Mode) afin que nous validions la direction artistique ensemble.

### 4. Contraintes d'Infrastructure & Architecture
- **Hébergement :** L'application web et son backend doivent être facilement déployables via **Docker** sur un serveur domestique de type **Raspberry Pi** (architecture ARM64, ressources optimisées).
- **Stack technique :** J'avais pensé à un backend en **Rust**, mais je ne sais pas si c'est approprié pour le projet et je reste très ouvert. Ma montre est un peu vielle j'ai besoin que ça tourne avec le moins de ressources possible. 

Recommande-moi l'**architecture globale la plus pertinente et réaliste** pour faire communiquer la montre, le téléphone et le serveur Raspberry Pi (ex: application mobile native vs PWA, utilisation de Health Connect, format de fichiers `.fit`/`.gpx`, choix de la stack backend/frontend).