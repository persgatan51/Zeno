# Zeno – kravspec etapp 1: Foto → berättelse

Version 1.0 · 17 nov 2026 · Ägare: Fredrik (persgatan51)

Etapp 1 ska göra en sak riktigt bra: du fotar en byggnad och får på några sekunder veta vad det är och dess historia, på svenska, med källa. Etapp 2 (förslag i närheten och vägvisning) beskrivs kort i slutet och byggs först när etapp 1 klarar sin grind.

Bakgrund, marknadsanalys och hela färdplanen finns i dokumentet "Zeno – reseapp: fördjupad analys och förslag" (Claude-projektet Zeno).

## 1. Beslut som styr bygget

| Fråga | Beslut |
| --- | --- |
| Plattform | Android-app (Samsung). Samma uppbyggnad som Skivbacken: en webbapp i en fil (`index.html`) på GitHub Pages, i ett tunt Android-skal (Kotlin WebView). |
| Backend | Ingen. Appen anropar tjänsterna direkt. Nycklar och data sparas bara i telefonen (localStorage). |
| AI-modell | Gemini Flash via Gemini API, gratisnivån. Samma modellkedja och felhantering som i Skivbacken. En modell för både igenkänning och berättelse. |
| Språk | Svenska i hela appen och i berättelserna, även när källorna är på engelska. |
| Uppkoppling | Krävs. Ingen offline-funktion. Appen säger tydligt till när nät saknas. |
| Användare | Bara Fredrik. Ingen inloggning, ingen synk. |
| Kostnad | 0 kr. Bara gratistjänster och gratisnivåer. |

## 2. Användarflöde i etapp 1

1. **Start.** Stor knapp **Fota** och en mindre **Vad står jag vid?** (utan foto). Senaste platserna listas under.
2. **Fota.** Kameran öppnas. Samtidigt hämtar appen GPS-position och kompassriktning.
3. **Identifiera.** Appen hämtar platser i närheten och låter Gemini välja vilken som syns på bilden.
4. **Resultat.**
   - *Säker:* platsen visas direkt.
   - *Osäker:* 2–3 kort med namn, bild och avstånd. Du trycker på rätt.
   - *Inte hittad:* "Jag hittar inte platsen", plus en beskrivning av stil och epok utifrån bilden.
5. **Berättelse.** Tre lager: en mening, en minut, fördjupning. Knapp **Lyssna**. Källa visas.
6. **Fråga vidare.** Ett textfält för följdfrågor om just den platsen.
7. **Rätt eller fel?** Två knappar, så att träffsäkerheten kan mätas (se grind, avsnitt 8).

## 3. Funktionella krav

### 3.1 Position och riktning

- **K1.** Hämta position med `navigator.geolocation` (hög noggrannhet, max 15 s). Visa noggrannheten i meter.
- **K2.** Hämta kompassriktning med `deviceorientationabsolute` (alpha → riktning 0–360°, norr = 0). Ta medianen av mätningarna under de sista 2 sekunderna före fotot. Saknas kompass: fortsätt utan riktning.
- **K3.** Spara position, noggrannhet och riktning tillsammans med fotot.

### 3.2 Kandidater (platser i närheten)

- **K4.** Wikipedia Geosearch, både `sv.wikipedia.org` och `en.wikipedia.org`: radie 300 m (öka till 600 m om färre än 5 träffar), max 30, med `coordinates|pageimages|description|pageprops` (för Wikidata-id).
- **K5.** OpenStreetMap via Overpass API: objekt med `name` och någon av `historic`, `tourism`, `amenity=place_of_worship`, `building` med `wikidata`, inom 200 m.
- **K6.** Slå ihop träffarna. Samma Wikidata-id = samma plats.
- **K7.** Räkna avstånd och bäring från användaren till varje kandidat. Om riktning finns: markera kandidater inom ±35° från riktningen som "i blickfånget".
- **K8.** Skicka högst 15 kandidater till Gemini, sorterade efter i blickfånget först, sedan avstånd.

### 3.3 Identifiering med Gemini

- **K9.** Fotot skalas ner till 1024 px längsta sida, JPEG, innan det skickas.
- **K10.** Ett anrop med bild, kandidatlista (id, namn, beskrivning, avstånd, bäring, i blickfånget) och riktning. Gemini ska svara med JSON (`responseMimeType: application/json`):

```json
{
  "match_id": "Q123456 eller null",
  "confidence": "high | medium | low",
  "alternatives": ["id", "id"],
  "seen": "kort beskrivning av vad som syns på bilden",
  "style": "arkitekturstil om den går att bedöma",
  "era": "ungefärlig tid"
}
```

- **K11.** Prompten ska säga att Gemini bara får välja bland kandidaterna eller svara `null`, och att det är bättre att vara osäker än att gissa.
- **K12.** `high` visar platsen direkt. `medium`/`low` visar korten (K-flöde steg 4). `null` visar "inte hittad" med `seen`, `style` och `era`.
- **K13.** **Vad står jag vid?** hoppar över fotot och visar de 3 närmaste kandidaterna i blickfånget att välja bland.

### 3.4 Berättelse

- **K14.** Hämta källtext: Wikipedia-artikelns inledning och upp till ca 6 000 tecken av brödtexten (sv i första hand, annars en), samt fakta från Wikidata (byggår, arkitekt, stil, kulturarvsstatus, bild).
- **K15.** Ett Gemini-anrop som skriver berättelsen på svenska i tre lager som JSON: `oneLine` (en mening), `minute` (ca 150 ord), `deep` (ca 400 ord, med rubriker som Arkitektur, Personer, Kuriosa när det finns underlag).
- **K16.** Gemini får **bara** använda källtexten. Saknas underlag ska det stå "det vet källorna inte". Inga påhittade årtal eller namn.
- **K17.** Vinkeln styrs av användarens intressen (avsnitt 3.6), men fakta ändras inte.
- **K18.** Visa källa längst ner med länk ("Källa: Wikipedia, CC BY-SA").
- **K19.** Cacha berättelsen per plats och intresseprofil i localStorage, så att samma plats inte kostar nya anrop.

### 3.5 Uppläsning och följdfrågor

- **K20.** **Lyssna** läser upp valt lager med Androids TextToSpeech på svenska (via bryggan, se 4.2). **Stopp** avbryter.
- **K21.** Följdfrågor skickas till Gemini med källtexten och tidigare frågor som sammanhang. Samma regel som K16.

### 3.6 Profil och inställningar

- **K22.** Första gången: välj 3–6 intressen bland kort: Arkitektur, Historia och makt, Konst, Kyrkor och religion, Kuriosa, Mat och dryck, Natur och parker, Barnvänligt.
- **K23.** Inställningar (⚙︎): Gemini API-nyckel, intressen, standardlängd på berättelsen (en mening / en minut / fördjupning), antal Gemini-anrop idag, träffsäkerhet (avsnitt 8), rensa cache.
- **K24.** Nyckeln sparas bara i appen. Länk till aistudio.google.com/apikey.

### 3.7 Historik

- **K25.** Varje identifierad plats sparas: namn, Wikidata-id, position, tid, liten miniatyr av fotot, rätt/fel. Visas som lista på startskärmen. Detta blir grunden för resedagboken i etapp 4.

## 4. Android-skalet

### 4.1 Grund

- Kopiera upplägget från Skivbacken: `android/` med Kotlin `MainActivity`, WebView, fast debug-nyckel i repot, GitHub Actions bygger APK vid ändring under `android/` och lägger den i releasen `app` som `zeno.apk`.
- `applicationId = "se.persgatan.zeno"`, appnamn **Zeno**.
- `START_URL = "https://persgatan51.github.io/Zeno/"`, med `?v=` vid start så att sidan alltid är färsk.
- Kameran via `<input type="file" capture>` och FileProvider, som i Skivbacken.

### 4.2 Nytt jämfört med Skivbacken

- **Position:** `ACCESS_FINE_LOCATION` och `ACCESS_COARSE_LOCATION` i manifestet. Be om behörighet vid första start. Svara på `onGeolocationPermissionsShowPrompt` i WebChromeClient.
- **Uppläsning:** bryggan `App.speak(text, id)` och `App.stopSpeaking()` med `TextToSpeech`, språk `sv-SE`. Svara när uppläsningen är klar.
- **Versionsnummer:** `App.version()` som i Skivbacken. Webbappen kontrollerar versionen så att en gammal APK inte kraschar.
- **Skärmen tänd** medan en berättelse läses upp.

## 5. Gemini – modeller och felhantering

- Modellkedja som i Skivbacken: `gemini-flash-latest`, sedan nästa Flash-modell om en är överbelastad. Valbar modell i inställningarna.
- Räkna anrop per dag och visa i inställningarna.
- Svenska felmeddelanden: fel nyckel, dagens gratisanrop slut, överbelastat, inget nät.
- Temperatur låg (0,2) för identifiering och berättelse.

## 6. Icke-funktionella krav

| Krav | Mål |
| --- | --- |
| Tid från foto till namn | Under 8 sekunder på 4G |
| Tid till "en minut"-berättelsen | Under 15 sekunder |
| Kostnad | 0 kr |
| Gemini-anrop per foto | Högst 2 (identifiering + berättelse), cache vid återbesök |
| Språk | Svenska överallt |
| Data | Bara i telefonen. Inga nycklar i repot. |
| Läsbarhet ute | Stor text, hög kontrast, fungerar med en hand |
| Attribution | Wikipedia (CC BY-SA), OpenStreetMap (ODbL) visas i appen |

## 7. Utanför etapp 1

Förslag i närheten, karta, vägvisning, ljud på vägen, resedagbok, flera profiler, dela.

## 8. Grind: när är etapp 1 klar?

- [ ] Appen installeras från releasen och startar på Fredriks Samsung.
- [ ] Foto → namn fungerar med position och kompass.
- [ ] Osäkra fall visar 2–3 kort, och "inte hittad" ger en beskrivning i stället för en gissning.
- [ ] Berättelsen finns i tre lager på svenska, med källa, och kan läsas upp.
- [ ] Följdfrågor fungerar.
- [ ] **Träffsäkerhet: minst 8 av 10 rätt på 30 egna foton** (räknas via rätt/fel-knapparna).
- [ ] Inga påhittade fakta i 10 stickprov jämfört med källan.

## 9. Etapp 2 i korthet (MVP)

- **Tre förslag** efter berättelsen: ett tryggt, ett oväntat och ett praktiskt (fika, toalett, vila). Kandidater från Wikipedia och OpenStreetMap inom gångavstånd.
- **Rankning:** intressematchning × avstånd × öppet nu × variation × tid kvar.
- **Reglage:** tid kvar och ork.
- **Karta och gångrutt:** OpenStreetMap-karta (Leaflet). Rutt med OpenRouteService (gratis nyckel, 2 000 rutter/dag), eller "Öppna i Google Maps" som reserv.
- **Grind:** en hel runda hemma i Helsingborg.
