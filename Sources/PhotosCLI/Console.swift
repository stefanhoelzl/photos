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

    /// Ask for one value on the terminal.
    ///
    /// The prompt goes to stderr so that piping stdout somewhere does not swallow it, and so
    /// `photos-cli login < answers` still works for anyone who wants it scripted.
    func ask(_ prompt: String, secret: Bool = false) -> String? {
        FileHandle.standardError.write(Data(prompt.utf8))
        let text = secret ? withoutEcho { readLine(strippingNewline: true) }
                          : readLine(strippingNewline: true)
        if secret { FileHandle.standardError.write(Data("\n".utf8)) }
        return text
    }

#if canImport(Glibc)
    /// Read with the terminal's echo turned off, and turn it back on however we leave.
    ///
    /// Not conditional on `isTerminal`: that tracks stdout, and the password is read from
    /// stdin, which may be a terminal when stdout is a pipe. `tcgetattr` failing is the
    /// answer for the case where stdin is not a terminal at all.
    private func withoutEcho<T>(_ body: () -> T) -> T {
        var original = termios()
        guard tcgetattr(STDIN_FILENO, &original) == 0 else { return body() }
        var quiet = original
        quiet.c_lflag &= ~tcflag_t(ECHO)
        tcsetattr(STDIN_FILENO, TCSAFLUSH, &quiet)
        defer { tcsetattr(STDIN_FILENO, TCSAFLUSH, &original) }
        return body()
    }
#else
    private func withoutEcho<T>(_ body: () -> T) -> T { body() }
#endif
}
