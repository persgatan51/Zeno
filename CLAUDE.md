# Zeno – anteckningar för Claude

- Ägare: Fredrik (persgatan51). Svara och skriv på svenska.
- Zeno är en reseguide: fota en byggnad → få namn och historia → välj nästa stopp i närheten → vägvisning dit.
- Vad som ska byggas står i `docs/kravspec-etapp-1.md`. Bygg en etapp i taget och håll dig till kravspecen.
- Samma uppbyggnad som Skivbacken (persgatan51/skivhyllan), använd den som förlaga:
  - Webbappen är en enda fil, `index.html`, som körs från GitHub Pages: https://persgatan51.github.io/zeno/
  - `android/` är ett tunt WebView-skal (Kotlin). GitHub Actions bygger APK:n när något under `android/` ändras och lägger den i releasen `app` som `zeno.apk`.
  - Behåll `applicationId = se.persgatan.zeno` och den fasta debug-nyckeln, annars installeras inte uppdateringar ovanpå.
- Ingen backend. All data och alla nycklar ligger i localStorage i appen. Inga nycklar i repot.
- Bara gratistjänster: Gemini API (gratisnivån), Wikipedia/Wikidata, OpenStreetMap (Overpass), senare OpenRouteService.
- Gemini får bara berätta utifrån hämtad källtext. Hellre "det vet källorna inte" än påhittade fakta.
- Bryggan `App.*` har ett versionsnummer (`App.version()`). Höj det när du lägger till en metod, och låt webbappen kontrollera versionen så att gamla APK:er inte kraschar.
- Uppdatera `CHANGELOG` i `index.html` (nyast överst) och `WEB_VERSION` vid varje ändring som användaren märker.
