import Foundation
#if canImport(Glibc)
import Glibc
#endif
import PhotosIngest

/// Output that reads the same in a terminal and in the journal.
///
/// One line per fact, no ANSI and no carriage returns on stdout, so `journalctl` stays
/// greppable. The only thing that redraws is a progress counter, and it goes to stderr and
/// only when stdout is a terminal — so an unattended run's log is exactly the facts (§7).
struct Console: Sendable {

    let isTerminal: Bool

    init() {
        self.isTerminal = isatty(STDOUT_FILENO) == 1
    }

    func line(_ text: String) {
        FileHandle.standardOutput.write(Data((text + "\n").utf8))
    }

    /// Something a person should see but that did not stop the run.
    func note(_ text: String) {
        line("! \(text)")
    }

    func error(_ text: String) {
        FileHandle.standardError.write(Data("photos-cli: \(text)\n".utf8))
    }

    /// A single redrawing line, only on a terminal. Never part of the record.
    func progress(_ text: String) {
        guard isTerminal else { return }
        FileHandle.standardError.write(Data("\u{1B}[2K\r\(text)".utf8))
    }

    func clearProgress() {
        guard isTerminal else { return }
        FileHandle.standardError.write(Data("\u{1B}[2K\r".utf8))
    }
}
