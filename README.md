# flashcard 2.0.0

A local-first Android English → Persian vocabulary flashcard app with Gemini + Groq AI enrichment and FSRS-6 review scheduling.

## Final feature set

- English-only application UI (Persian appears only as flashcard meanings).
- Flashcards contain: English word, General American IPA, 1–4 common Persian meanings, and one English example sentence.
- Gemini 3.6 Flash support through the Gemini Interactions API.
- GroqCloud support through `openai/gpt-oss-20b` with strict JSON Schema output.
- AI modes: **Auto**, **Gemini only**, **Groq only**. Auto tries Gemini first and falls back to Groq.
- API keys are encrypted with Android Keystore + AES/GCM and are never stored in source code, APK resources, CSV exports, or backups.
- CSV import with duplicate detection, file progress, and live AI-enrichment progress for the latest import.
- Manual flashcard editing.
- Search, filters (All, Due, Ready, Pending, Failed, Reviewed), and sorting (Newest, A–Z, Due first, Most reviewed).
- Precise AI retry classification: network/timeouts/429/5xx retry; permanent authentication/request errors fail fast; Auto can fall back to the other provider.
- FSRS-6 review scheduling with **Again / Hard / Good / Easy**.
- FSRS review logs are stored locally for future analysis/optimization.
- Full JSON backup/restore of cards + FSRS state + review history.
- CSV export of card content.
- Reset all review progress, retry all failed AI cards, and delete all cards.
- Real Room database migration from schema version 1 to version 2.
- Central app versioning through `version.properties` (`2.0.0`, versionCode `20000`).
- GitHub Actions builds an installable debug APK on every push and a signed release APK when signing secrets are configured.

## Create a new GitHub repository

1. Create a new empty repository on GitHub.
2. Upload **the contents of this folder** to the repository root. Do not upload the outer ZIP as a single file.
3. Commit to `main`.
4. Open **Actions → Build Flashcard APK**.
5. The workflow runs unit tests and builds `flashcard-2.0.0-debug.apk` automatically.
6. Download the `flashcard-APK` artifact.

The debug APK is suitable for testing. **For the first permanent installation, configure release signing before installing the app.** Every future release must use the same signing key so Android can update the installed app without uninstalling it.

## Signed release APK (recommended)

Android updates require the same signing key forever. Never put a `.jks` file or its passwords in a public repository.

Generate a permanent signing key on a trusted computer:

```bash
./scripts/generate-signing-key.sh
```

Then create these **GitHub repository Actions secrets**:

- `ANDROID_KEYSTORE_BASE64` — base64 text printed by the script
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — normally `flashcard`
- `ANDROID_KEY_PASSWORD` — key password

Push any commit or manually run the workflow again. The artifact will then contain:

- `flashcard-2.0.0-release.apk` — signed production APK
- `mapping-release.txt` — R8 mapping file; keep it for crash-symbolication/debugging
- `flashcard-2.0.0-debug.apk` — test APK

**Back up the original signing `.jks` somewhere safe.** Losing it means future versions cannot update the installed release app.

## AI setup inside the app

Open **Settings** and enter your Gemini and/or Groq API key. Do not paste API keys into GitHub files.

Recommended mode: **Auto**. Gemini is used first, and Groq is used as fallback if Gemini fails or is rate-limited.

## CSV format

Minimal CSV:

```csv
word
achieve
adapt
remarkable
```

Optional pre-filled content is also supported:

```csv
word,ipa,meaningsFa,exampleEn
skill,/skɪl/,مهارت,She learned a useful skill.
```

Rows are normalized before insertion. Case, extra whitespace, and edge punctuation do not create duplicate cards. Duplicates already present in the library or repeated inside the same CSV are skipped.

## Backup and restore

Settings provides:

- **Create full backup**: JSON containing cards, FSRS state, AI metadata, and review history.
- **Restore backup**: replaces the current card library and review history with the backup content.
- **Export cards as CSV**: card content for spreadsheet use.

API keys are intentionally excluded from all exports and backups.

## FSRS-6

The scheduler uses the public FSRS-6 default 21-parameter model with:

- desired retention: 90%
- learning steps: 1 minute, 10 minutes
- relearning step: 10 minutes
- four ratings: Again, Hard, Good, Easy

The database stores stability, difficulty, state, step, due time, and review logs per card.

## Database migration

Database v2 adds FSRS state, AI diagnostics, additional indexes, and a `review_logs` table. `MIGRATION_1_2` upgrades a v1 database without destructive fallback. Legacy interval/repetition information is used to seed initial FSRS fields instead of deleting old data.

Room schema export is enabled under `app/schemas/` so future migrations can be reviewed and versioned.

## Project requirements

- Android minSdk 23
- targetSdk 36 / compileSdk 36
- JDK 17
- Gradle 9.3.1 in GitHub Actions
- Android Gradle Plugin 9.1.1
- Kotlin Compose plugin 2.4.10
- Room 2.8.4
- WorkManager 2.11.2

## Security notes

- No API key is hardcoded.
- API keys remain on the device and are encrypted using Android Keystore.
- Android cleartext HTTP is disabled.
- Automatic Android backup is disabled because the app has an explicit backup format.
- Release signing material is loaded only from GitHub Actions secrets.

## Version bumping

For every production release, edit only `version.properties`:

```properties
VERSION_NAME=2.0.1
VERSION_CODE=20001
```

Gradle, the in-app version label, and GitHub Actions artifact filenames all read from this source of truth. `VERSION_CODE` must always increase for Android to accept an update.
