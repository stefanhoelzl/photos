# Installing the CLI

The CLI in use on this machine lives at `~/.local/bin/photos-cli`, and nowhere else.

To install a new build:

```sh
./gradlew :app:cli:linkReleaseExecutableLinuxX64
install -m 755 app/cli/build/bin/linuxX64/releaseExecutable/photos-cli.kexe ~/.local/bin/photos-cli
```

Overwrite it in place. Do not keep a copy of the old binary (no `photos-cli.old`, no dated
backups): any build can be rebuilt from its commit.
