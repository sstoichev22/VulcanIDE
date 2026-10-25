# Vulcan project rules

- Increment the application version at most once per chat/release, not once per change or revision. Keep the current version unchanged for the rest of a chat after its increment; follow an explicit user version instruction when provided.
- Read the current version from `src/VulcanIDE/Vulcan.java`; do not reset it to an older requested version.
- Jars live only in each repo's `builds/` folder and are produced by `../qa/build.ps1`; never commit class files or other build output.
