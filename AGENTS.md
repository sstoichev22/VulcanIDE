# Vulcan project rules

- Increment the application version at most once per chat/release, not once per change or revision. Keep the current version unchanged for the rest of a chat after its increment; follow an explicit user version instruction when provided.
- Read the current version from `src/VulcanIDE/Vulcan.java`; do not reset it to an older requested version.
- Deliver source only unless the user requests compiled artifacts. Do not include a build folder or JAR in deliveries.
- Files under the parent project's `sources/` directory are synced, read-only reference material and must remain untouched.
