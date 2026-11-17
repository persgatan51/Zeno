# Zeno

Din reseguide i telefonen. Fota en byggnad så berättar Zeno vad det är och dess historia, på svenska. Sedan föreslår Zeno vad du kan se i närheten och visar vägen dit.

**Status:** etapp 1 är byggd och testas. Kravspec: [docs/kravspec-etapp-1.md](docs/kravspec-etapp-1.md).

## Kom igång

1. Ladda ner `zeno.apk` under Releases → "Android-app" på telefonen och installera den.
2. Tillåt kamera och plats när appen frågar.
3. Klistra in din **Gemini API-nyckel** (gratis, [aistudio.google.com/apikey](https://aistudio.google.com/apikey)) och välj dina intressen.
4. Tryck **Fota en byggnad**, rikta mot byggnaden och ta fotot.

Efter varje foto trycker du **Rätt** eller **Fel**. Träffsäkerheten syns på startskärmen; målet för etapp 1 är minst 8 av 10 rätt på 30 foton.

Uppläsningen använder telefonens svenska röst. Saknas den: Inställningar → Allmän hantering → Text till tal.

## Etapper

1. **Foto → berättelse:** identifiera byggnaden med position, kompass och Gemini, och berätta med källa.
2. **Nära och vägen:** tre förslag i närheten, karta och gångrutt.
3. **Kedjan och ljudet:** Zeno känner av när du är framme, berättar på vägen och läser upp med skärmen släckt.
4. **Minne:** resedagbok, profiler för resesällskapet och delning.

## Teknik

Webbapp i en fil (`index.html`) på GitHub Pages, i ett tunt Android-skal. Gratistjänster: Gemini API (gratisnivån), Wikipedia/Wikidata och OpenStreetMap. Allt sparas i telefonen.
