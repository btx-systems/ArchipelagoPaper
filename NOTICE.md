# Licensing

Archipelago Paper is a modified version of [Paper](https://github.com/PaperMC/Paper), first modified in November 2026.
It is not affiliated with or endorsed by PaperMC.

Paper inherits the GNU General Public License version 3 from Spigot, Bukkit and CraftBukkit.
Archipelago Paper, including its patches and its own source files, is likewise licensed under the
[GNU General Public License version 3](LICENSE).

Some Paper contributors have also released their work under the [MIT License](licenses/MIT.md).
They are listed in [Paper's LICENSE.md](https://github.com/PaperMC/Paper/blob/main/LICENSE.md), and their work
remains available under those terms. This does not extend to changes made in Archipelago Paper.

Files and dependencies that carry their own license notice, such as the Gradle wrapper (Apache License 2.0),
remain under that license.

## Source code

The source is at <https://github.com/btx-systems/ArchipelagoPaper>. For a server jar, use the commit shown by
`/version`. For the API and dev bundle, use the commit of the
[Build and Publish run](https://github.com/btx-systems/ArchipelagoPaper/actions/workflows/build.yml)
with the same number as the build in the version.

`./gradlew applyAllPatches` at that commit recreates the full source tree, using the Paper commit set as
`paperRef` in its `gradle.properties`.
