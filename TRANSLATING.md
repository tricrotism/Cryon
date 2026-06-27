# Translating Cryon (core)

Player-facing text lives in `paper/src/main/resources/lang/<locale>.properties` (MiniMessage
templates). The loader (`LangScanner`) auto-registers every bundled `<locale>.properties` at runtime,
and admins can override any key in `plugins/Cryon/lang/`. Crowdin manages the translation workflow via
`crowdin.yml` + `.github/workflows/crowdin.yml`.

## One-time setup

1. Create a Crowdin project — file format **Java Properties**, source language **English**.
2. Add two repo secrets (Settings → Secrets and variables → Actions):
    - `CROWDIN_PROJECT_ID`
    - `CROWDIN_PERSONAL_TOKEN` (personal access token with project scope)
3. **Protect the MiniMessage tags** so `<player>`, `<highlight>`, `<color:#aabbcc>`, … aren't
   translated or broken. In the project's string/placeholder settings add a custom regex:
   ```
   <[^>]+>
   ```
   (Every `<…>` in Cryon strings is a tag, so protecting all of them is safe.)
4. Seed the existing translation — this repo already ships a hand-made `de_DE.properties`. Import it
   once so Crowdin starts pre-filled (the workflow keeps `upload_translations: false` afterwards so
   Crowdin stays the source of truth):
   ```
   crowdin upload translations --language de
   ```

## How it flows

- Edit/add keys in `en_US.properties` → push to `master` → the **Crowdin** workflow uploads sources.
- Daily (and via *Run workflow*) it downloads completed translations and opens an
  `[i18n] Sync translations from Crowdin` PR. Review, merge, and they ship in the next build.

Missing keys render `⟨key⟩` and fall back through the locale chain, so partial translations are safe to
merge.
