# DMaps

**DMaps is an offline, honesty-first transit companion — a [CoMaps](https://github.com/comaps/comaps) fork (itself a community fork of Organic Maps) that adds what city riders actually need: scheduled journey planning, with no fake precision, no tracking, and no accounts.**

Berlin/VBB is the first region. The architecture is region-agnostic — new cities are a timetable pack away.

---

## What DMaps adds over CoMaps / Organic Maps

**1. Offline scheduled transit journey planning.** CoMaps and Organic Maps draw transit lines on the map but cannot plan a journey. DMaps v0.1 plans station-to-station trips on a GTFS-derived timetable pack you import yourself — no server, no internet, no API keys.

**2. Platform-precise endpoints.** Plans start and end at an explicit platform ("S+U Alexanderplatz Bhf · Platform 1 (U5) → S+U Berlin Hauptbahnhof · Platform 2 (U5)"), not a vague station guess. Station search folds umlauts — type `Suedkreuz`, find *S Südkreuz*.

**3. Honesty-first design.** Every screen tells the truth about what the app knows:
- Your imported pack is labelled **untrusted**: "SHA-256 checks content integrity, not publisher identity."
- No live departures are claimed. No guaranteed connections. Approximate times say so.
- The material limits are printed on the screen: *"Search: next 3 hours, at most 3 boardings, same-platform transfers only. No walking to, between or from platforms."*
- When a departure passes, the plan retires honestly: *"The planned departure time has passed. Your platform choices are kept — plan again for current departures."*
- When nothing is found: *"No itinerary found in this preview. This does not mean no public transport connection exists."*
- A broken pack import never destroys your working pack; validity and a recovery path are always visible.

**4. Privacy by construction.** Planning is fully offline. No accounts, no analytics, no network calls. Your timetable data never leaves the device.

**5. Germany-first details.** Europe/Berlin noon-anchored, DST-correct time semantics; German-localised strings; umlaut-folding search.

**6. Evidence-driven engineering.** 672 host tests with 265 UI-state pins guard the transit screens; device campaigns follow a scenario graph; every latency number in the notes comes from a **pre-registered measurement** on sealed bytes; a claims ledger ties user-visible claims to receipts. We think how an app is verified is part of the product.

## Honest limitations of v0.1

- Berlin/VBB pack only (bring your own city later — the format is region-agnostic).
- Same-platform transfers only; no walking between platforms; no walking to/from stations.
- No live departures, no delays, no guaranteed connections — scheduled preview only.
- The mapped walking-link preview takes ~5.5 s on-device (measured; on the v0.2 performance list).
- The attached APK is a **debug-signed build** for early testers; the release-signed artifact follows our signing ceremony, and our distribution channel will be our own F-Droid repository with GitHub mirroring the identical artifact.

## Provenance

Built from sealed source (`dmaps-autonomous-m4`, seal `578439ca…`, app sha `d63a984b…`). License: Apache-2.0, preserving CoMaps/Organic Maps attribution (verified byte-identical packaging audit). Berlin timetable data © Verkehrsverbund Berlin-Brandenburg (VBB), CC BY 4.0 — imported by you, labelled untrusted by us.

---

*The sections below are the upstream CoMaps README, kept for attribution and upstream documentation.*

<!-- Navigation Bar -->
<p align="center">
  <a href="https://codeberg.org/comaps#comaps">Project Intro</a> |
  <a href="https://codeberg.org/comaps/Governance#comaps-project-governance-docs">Governance</a> |
  <a href="https://codeberg.org/comaps/Governance/src/branch/main/FAQ.md">FAQ</a> |
  <a href="#contributing">Contribute</a> |
  <a href="https://www.comaps.app/donate">Donate</a> |
  <a href="https://codeberg.org/comaps#keep-connected">Keep Connected</a>
</p>

<div align="center" >
<img src="docs/badges/logo.svg" width="150">
</div>


<div align="center">
<h1><a href="https://comaps.app/">CoMaps</a></h1>
<h2>Hike, Bike, Drive Offline - Easy Map Navigation with Privacy</h2>
</div>
<div align="center">
  <p align="center">
    <a href="https://codeberg.org/comaps/comaps/src/branch/main/LICENSE">
      <img src="https://img.shields.io/github/license/comaps/comaps?style=for-the-badge&logo=opensourceinitiative&logoColor=white&color=588157" alt="License"/>
    </a>
    <a href="https://opencollective.com/comaps">
      <img src="https://img.shields.io/opencollective/all/comaps?label=Open%20Collective%20Donors&logo=opencollective&logoColor=white&style=for-the-badge&color=588157" alt="Open Collective Donors"/>
    </a>
    <a href="https://liberapay.com/CoMaps">
      <img src="https://img.shields.io/liberapay/patrons/CoMaps?label=Liberapay%20Patrons&logo=liberapay&logoColor=white&style=for-the-badge&color=588157" alt="Liberapay Patrons"/>
    </a>
  </p>
</div>

---

A community-led free & open source maps app based on [OpenStreetMap](https://www.openstreetmap.org), built for transparency, privacy, and not-for-profit values. A fork of Organic Maps, originally based on Maps.ME.

<p align="center">
  <a href="https://apps.apple.com/app/comaps/id6747180809">
    <img src="docs/badges/apple-appstore.png" alt="App Store" width="160"/>
  </a>
  <a href="https://play.google.com/store/apps/details?id=app.comaps.google">
    <img src="docs/badges/google-play.png" alt="Google Play" width="160"/>
  </a>
  <a href="https://f-droid.org/en/packages/app.comaps.fdroid/">
    <img src="docs/badges/fdroid.png" alt="F-Droid" width="160"/>
  </a>
  <a href="https://flathub.org/apps/app.comaps.comaps">
    <img src="docs/badges/flathub.png" alt="Flathub" width="160"/>
  </a>
  <a href="https://apt.izzysoft.de/fdroid/index/apk/app.comaps">
    <img src="docs/badges/izzyondroid.png" alt="Obtainium" width="160"/>
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://codeberg.org/comaps/comaps">
    <img src="docs/badges/obtainium.png" alt="Obtainium" width="160"/>
  </a>
  <a href="https://codeberg.org/comaps/comaps/releases">
    <img src="docs/badges/codeberg.png" alt="Codeberg" width="160"/>
  </a>
</p>


<!-- Screenshots -->
<p align="center">
  <img src="android/app/src/fdroid/play/listings/en-US/graphics/phone-screenshots/1.png" width="180" />
  <img src="android/app/src/fdroid/play/listings/en-US/graphics/phone-screenshots/2.png" width="180" />
  <img src="android/app/src/fdroid/play/listings/en-US/graphics/phone-screenshots/3.png" width="180" />
  <img src="android/app/src/fdroid/play/listings/en-US/graphics/phone-screenshots/4.png" width="180" />
  <img src="android/app/src/fdroid/play/listings/en-US/graphics/phone-screenshots/5.png" width="180" />
  <img src="android/app/src/fdroid/play/listings/en-US/graphics/phone-screenshots/6.png" width="180" />
</p>

---

## ⚡️ Highlights

- **Offline-first**: Navigate without a connection
- **Privacy-respecting**: No tracking, ads or data collection
- **Lightweight**: Battery- and space-efficient
- **Simple**: Polished, user-focused interface
- **Cross-platform**: Available for Android, iOS, MacOS, and Linux.
- **Community-built**: Free, open source, and collaborative
- **Transparent**: Open finances and governance

---

## 🚀 Main Features

- Downloadable detailed maps with places which are not available with Google Maps

- Outdoor mode with highlighted hiking trails, campsites, water sources, peaks, contour lines, etc

- Walking paths and cycleways

- Points of interest like restaurants, gas stations, hotels, shops, sightseeings and many more

- Search by name or an address or by point of interest category

- Navigation with voice announcements for walking, cycling, or driving

- Bookmark your favorite places with a single tap

- Offline Wikipedia articles

- Subway transit layer and directions

- Track recording

- Export and import bookmarks and tracks in KML, KMZ, GPX formats

- A dark mode to use during the night

- Improve map data for everyone using a basic built-in editor

- Android Auto and CarPlay support

 ***Freedom Is Here – Navigate the world with privacy and community at the forefront.***

---

## 🤝 Contributing

You can help by donating, contributing code, translating, or by telling others about it. To build the best maps app there is a need for software development, design, product, community development, and other areas. Reach out to us and let us know how you want to help.

- Build instructions: [docs/INSTALL.md](docs/INSTALL.md)  
- Contribution guide: [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)

There is a dedicated [Zulip](https://codeberg.org/comaps/Governance/src/branch/main/contribute.md#3-team-messaging) chat for active contributors.

---

## 💬 Feedback

- Rate us on [App Store](https://apps.apple.com/app/comaps/id6747180809) and [Google Play](https://play.google.com/store/apps/details?id=app.comaps.google)
- Star our repos on Codeberg 
- Report bugs or request features on the [issue tracker](https://codeberg.org/comaps/comaps/issues)

---

## 💸 Funding

CoMaps is free. To fund development, we rely on your voluntary support ♥️
Donate via [OpenCollective](https://opencollective.com/comaps/donate) or [Liberapay](https://liberapay.com/CoMaps).  
The project's financial information is completely open and transparent at [our Open Collective](https://opencollective.com/comaps).

---

## 🔒 Privacy

The Android app has been reviewed by [Exodus Privacy](https://reports.exodus-privacy.eu.org/en/reports/app.comaps.google/latest/).

To [verify](https://developer.android.com/studio/command-line/apksigner#usage-verify) the APK, use the following signing certificate fingerprints:
```
SHA-256: 4894e8e6963627ef660031d8593fe77297f835acb4e23810003e926135023b4c
SHA-1: 8b7b5739f917e9f7c681671ced0c9c8562123ade
MD5: 9cce0ffea281dc2f0e0a154d6d2e281e
```

To verify CoMaps via [AppVerifier](https://github.com/soupslurpr/AppVerifier), use the following signing certificate fingerprint:
```
app.comaps
48:94:E8:E6:96:36:27:EF:66:00:31:D8:59:3F:E7:72:97:F8:35:AC:B4:E2:38:10:00:3E:92:61:35:02:3B:4C
```
---

## ⚖️ License

Licensed under the Apache License 2.0.  
See [LICENSE](LICENSE), [NOTICE](NOTICE), and [data/copyright.html](data/copyright.html). 

Our project is completely free and open source software. It had been reviewed by "[is it really FOSS?](https://isitreallyfoss.com/projects/comaps/)" and is listed as 100% FOSS, [see what that means](https://isitreallyfoss.com/about/categorisation/)

