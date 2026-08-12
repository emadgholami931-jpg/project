# Architecture

## Data

- `FlashcardEntity`: vocabulary content, AI metadata, legacy v1 scheduling fields, and FSRS-6 state.
- `ReviewLogEntity`: immutable review history used for auditing and future FSRS parameter optimization.
- `AppDatabase`: Room database v2 with explicit `MIGRATION_1_2`; destructive fallback is not enabled.
- `WordNormalizer`: the single normalization path used by manual add, CSV import, edit conflict checks, and backup restore.

## AI

- `AiEnrichmentClient`: provider routing, fallback, cooldown and failure classification.
- Gemini provider: `gemini-3.6-flash` through the Interactions API.
- Groq provider: `openai/gpt-oss-20b` through Groq Chat Completions with strict JSON Schema.
- `GeminiApiKeyStore` / `GroqApiKeyStore`: encrypted on-device key storage backed by Android Keystore.
- `EnrichmentWorker`: network-constrained WorkManager batch processing (max 20 words), exponential backoff and precise retry behavior.

## Review

- `FsrsScheduler`: FSRS-6 default 21-parameter model, 90% desired retention, 1m/10m learning and 10m relearning.
- Ratings: Again, Hard, Good, Easy.
- Every review writes both the updated card state and a review log in one Room transaction.

## Import / export

- CSV import accepts a word-only file or optional IPA/meanings/example columns.
- Duplicate checks use normalized words and occur before insertion; the database unique index remains the final guard.
- JSON backup includes all card state and review history, but deliberately excludes API keys.
- Restore validates backup format, de-duplicates cards, remaps identifiers when necessary, and replaces cards + review logs transactionally.

## Release

- `version.properties` is the single source for version name/code.
- GitHub Actions always creates a debug APK and creates an R8-minified signed release APK when permanent signing secrets are configured.
- Signing keys are never committed to the repository.
