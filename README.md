# RS Search Patch

RS Search Patch is a small client-only Forge mod for Minecraft 1.20.1 and Refined Storage 1.12.x.

It makes Refined Storage grid searching smoother in two ways:

- Tooltip text is indexed progressively without doing NBT serialization, hashing, disk access, or
  tooltip generation while a search predicate is running. Fingerprints receive about 2 ms and
  cache-miss tooltip generation receives about 4 ms after each rendered Grid frame. Unresolved
  tooltip-search results appear progressively while the UI remains responsive.
- Normalized tooltip search lines are cached by a deterministic SHA-256 fingerprint of the item or
  fluid registry ID and canonical, count-free NBT. The gzip JSON cache survives Grid replacement
  and game restarts at `config/rs_search_patch/tooltip-search-cache.json.gz`.
- Double quotes group search phrases. Prefixes retain their normal meaning, so `#"fire resistance"`,
  `@"refined storage"`, and `$"forge tools"` search tooltips, mod names, and tags respectively.
  Unquoted searches without `#` are passed directly to Refined Storage's original parser. OR (`|`)
  is quote-aware, and `|` inside a quoted phrase is literal.

## Requirements

- Minecraft 1.20.1
- Forge 47.x
- Refined Storage 1.12.4
- Java 17 when building

Only the client installs RS Search Patch. It registers no network channel and is not required on a
dedicated server.

## Build

Place the unmodified Refined Storage jar at `libs/refinedstorage-1.12.4.jar`, then run:

```powershell
.\gradlew.bat build
```

The output is `build/libs/rs_search_patch-1.0.0.jar`. Refined Storage is used as a remapped Gradle
dependency for compilation and development; it is not copied, shaded, or bundled into the output.

## Search examples

```text
"some item name"
#"fire resistance"
sword #"fire resistance"
#"fire resistance" | #"cold resistance"
"foo|bar"
"some \"quoted\" name"
```

An unmatched opening quote groups the rest of the query rather than causing an error.

## License

MIT
