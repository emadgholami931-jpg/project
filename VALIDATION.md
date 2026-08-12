# Validation report

Validation performed before packaging `flashcard 2.0.0`:

- FSRS-6 / CSV parser / word normalizer: compiled with the local Kotlin compiler and passed smoke checks.
- FSRS state smoke sequence: Good → learning step → Good → review interval passed.
- CSV parser smoke checks: quoted commas, escaped quotes, quoted newlines and duplicate row preservation passed.
- Room v1 → v2 migration SQL: executed against a synthetic v1 SQLite database; legacy scheduling values and indexes were preserved/seeded as expected.
- Android XML resources and manifest: parsed successfully.
- Source scan: no API-key-shaped Gemini/Groq secrets found.
- Source scan: no legacy Gemini 2.5/3.5 model references and no legacy `ReviewGrade` references found.
- Signing material scan: no `.jks` or `.keystore` file is included.
- GitHub Actions YAML was structurally parsed and includes unit test, debug APK and optional signed release APK steps.

A complete Android/Gradle build is intentionally delegated to the included GitHub Actions workflow because this packaging environment does not contain the Android SDK/Gradle dependency cache and has no direct Maven/Gradle network access. The workflow uses the same Gradle/JDK/SDK family that successfully built the previous app baseline.
