# `libs-repo/`

Local file-Maven-repo holding `darkness-toolkit` artifacts. Termtastic resolves
`se.soderbjorn.darkness:toolkit-*` from this directory when no sibling
`darkness-toolkit` checkout is present (or when `-Pdarkness.toolkit.useArtifacts=true`
is set), so a fresh clone of termtastic builds without the toolkit source tree.

## Refresh

From a `darkness-toolkit` checkout:

```sh
./gradlew publishAllToLibsRepo
```

This publishes every toolkit module to `../../termtastic/adopt-darkness-toolkit/libs-repo`
**and** `../../notegrow/adopt-darkness-toolkit/libs-repo` in one go. Commit the
resulting changes in both consumer repos.

Override targets with `-PtermtasticLibsRepo=…` or `-PnotegrowLibsRepo=…` (paths
absolute or relative to the toolkit checkout).
