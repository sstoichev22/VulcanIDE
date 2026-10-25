# IntelliJ icon inventory

Read-only extraction from the installed IntelliJ IDEA Community Edition 2022.2.3 at `C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2022.2.3`.

`icon-inventory.csv` identifies every discovered PNG, SVG and GIF: 4,440 entries inside installation JARs and one loose installation image (4,441 total, 24,662,365 bytes inside JARs). `intellij-icon-library.zip` retains every image's original bytes. JAR assets are stored under `<relative jar path>/<original entry path>`; the loose image is stored under `loose/<installation-relative path>`. Entries with identical image names in different JARs remain separate. The archive also preserves LICENSE, NOTICE and COPYRIGHT entries found in each image-bearing JAR under `licenses/`.

The installation's top-level Apache 2.0 license and notice are included separately. Preserve embedded copyright headers and applicable third-party notices when reusing icons; the archive includes third-party/plugin assets, so the top-level Apache license is not asserted to supersede their own terms.

Runtime resources are deliberately limited to these original JetBrains SVGs (with Apache 2.0 copyright headers intact) and 64×64 rasterizations:

| Runtime mapping | Installed source in `lib/app-client.jar` |
| --- | --- |
| `dir-icon.png` → `intellij-folder.png` | `nodes/folder.svg` |
| `dir-open-icon.png` → `intellij-folder-open.png` | `welcome/open.svg` |
| `java-icon.png` → `intellij-java.png` | `fileTypes/java.svg` |
| `txt-icon.png` → `intellij-text.png` | `fileTypes/text.svg` |

Only the relevant runtime assets and accompanying license/notice are packaged in VulcanIDE. The complete inventory stays outside `src` and is available for future icon selection.
