# Installing the CLI

The CLI in use on this machine lives at `~/.local/bin/photos-cli`, and nowhere else.

To install a new build:

```sh
./gradlew :app:cli:linkReleaseExecutableLinuxX64
install -m 755 app/cli/build/bin/linuxX64/releaseExecutable/photos-cli.kexe ~/.local/bin/photos-cli
```

Overwrite it in place. Do not keep a copy of the old binary (no `photos-cli.old`, no dated
backups): any build can be rebuilt from its commit.

# Build from this workspace only

Never copy anything from another workspace or worktree -- not `.tools/`, not `build/`, not a
compiled binary, not a file of any kind. Build everything this workspace needs yourself, from its
own sources, with Gradle -- which builds the native libraries too, in `:native`.
Another workspace's artifacts were built from another branch's sources, and a copy carries that
workspace's absolute paths with it.
