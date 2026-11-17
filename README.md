# Zeno

Din reseguide i telefonen. Fota en byggnad så berättar Zeno vad det är och dess historia, på svenska. Sedan föreslår Zeno vad du kan se i närheten och visar vägen dit.

**Status:** kravspec för etapp 1 är klar, se [docs/kravspec-etapp-1.md](docs/kravspec-etapp-1.md).

## Etapper

1. **Foto → berättelse:** identifiera byggnaden med position, kompass och Gemini, och berätta med källa.
2. **Nära och vägen:** tre förslag i närheten, karta och gångrutt.
3. **Kedjan och ljudet:** Zeno känner av när du är framme, berättar på vägen och läser upp med skärmen släckt.
4. **Minne:** resedagbok, profiler för resesällskapet och delning.

## Teknik

Webbapp i en fil (`index.html`) på GitHub Pages, i ett tunt Android-skal. Gratistjänster: Gemini API (gratisnivån), Wikipedia/Wikidata och OpenStreetMap. Allt sparas i telefonen.
