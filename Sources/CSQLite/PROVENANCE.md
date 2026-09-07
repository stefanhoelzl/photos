# SQLite amalgamation

    sqlite3.c, include/sqlite3.h

Vendored, not linked from the system. The Static Linux SDK ships no static musl
`libsqlite3`, and DESIGN §7 wants one self-contained binary; vendoring also means the
phone and the laptop run byte-identical SQLite, so a version skew cannot make two
devices disagree about a shard.

| | |
|---|---|
| version | 3.53.4 |
| source | <https://sqlite.org/2026/sqlite-amalgamation-3530400.zip> |
| sha256 | `1e71ddf93849c6a6ecf58b827c0692073d2dd7ee40196158068f7b29f422e87d` |
| retrieved | 2026-09-07 |

`shell.c` and `sqlite3ext.h` from the same archive are not vendored: nothing here runs the
CLI shell or loads extensions.

## Compile flags

Set in `Package.swift`, not here, so they are visible where the target is defined.

| flag | why |
|---|---|
| `SQLITE_DQS=0` | a double-quoted string is an error, not a silent identifier-then-string fallback |
| `SQLITE_THREADSAFE=1` | DESIGN §3 runs a writer and readers on separate connections |
| `SQLITE_OMIT_LOAD_EXTENSION` | nothing loads extensions; drops the dlopen path |
| `SQLITE_DEFAULT_MEMSTATUS=0` | skips per-allocation accounting nothing reads |
| `SQLITE_DEFAULT_WAL_SYNCHRONOUS=1` | WAL is required (§4); `NORMAL` is its right pairing |
| `SQLITE_LIKE_DOESNT_MATCH_BLOBS` | faster LIKE; every LIKE here is over TEXT |
| `SQLITE_OMIT_DEPRECATED` | removes interfaces nothing calls |
| `SQLITE_ENABLE_MATH_FUNCTIONS` | needs `-lm`, declared in the modulemap |

## Upgrading

Replace both files from a new amalgamation zip, update the table above, and run the tests.
The suite covers the schema and the rebuild, so a behavioural change shows up there.
