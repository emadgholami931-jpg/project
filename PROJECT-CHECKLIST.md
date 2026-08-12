# Final project checklist — flashcard 2.0.0

## Before the first GitHub build

- Upload the **contents** of this project to the root of a new GitHub repository.
- Keep all API keys out of GitHub. Enter Gemini/Groq keys only inside the installed app.
- The first push to `main` automatically runs unit tests and builds the debug APK.

## For a permanent signed release APK

1. Run `scripts/generate-signing-key.sh` on a trusted computer.
2. Back up the generated `.jks` file in a private location.
3. Add these GitHub Actions secrets:
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
4. Re-run **Build Flashcard APK**.
5. Keep the same signing key for every future release.

`.jks` and `.keystore` files are ignored by Git and must never be committed to a public repository.

## Recommended smoke test after installation

1. Open Settings and save one AI key.
2. Set the provider mode to the provider you want to test.
3. Add `remarkable` manually and verify IPA, Persian meanings and the English example.
4. Import `sample_words.csv` and verify import + AI progress.
5. Search and filter the Words screen.
6. Edit one card manually.
7. Review cards using Again / Hard / Good / Easy.
8. Create a full JSON backup.
9. Export CSV.
10. Restore the backup only after confirming that it replaces the current library.

## Versioning

Edit only `version.properties` for a new release. Always increase `VERSION_CODE`.
