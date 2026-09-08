import Foundation
import PhotosCore
import PhotosPipeline

/// Milestone C's acceptance harness.
///
/// §10 says C is "verified by running over the real library and checking output against
/// previously measured sizes and counts — any large deviation means the pipeline is wrong".
/// This is that check, made runnable and repeatable: it walks a library root, runs the full
/// pipeline, and prints per-tier counts and sizes beside INGEST.md's recorded figures.
///
/// It writes nothing to the zone and builds no catalog.
///
/// Deliberately **not** part of the shipped `photos-cli`: the figures it compares against
/// were measured from one particular library, and baking those into a package this design
/// presents as reusable is the mixing of concerns `INGEST.md` exists to prevent. It is also
/// the only way to run the pipeline under musl, where no test framework exists.
@main
struct PhotosScan {

    static func main() async {
        let arguments = CommandLine.arguments.dropFirst()
        var root: URL?
        var limit: Int?
        var albumFilter: String?
        var jobs = ProcessInfo.processInfo.activeProcessorCount
        var verbose = false

        var index = arguments.startIndex
        while index < arguments.endIndex {
            let argument = arguments[index]
            func next() -> String? {
                index = arguments.index(after: index)
                return index < arguments.endIndex ? arguments[index] : nil
            }
            switch argument {
            case "--limit": limit = next().flatMap(Int.init)
            case "--album": albumFilter = next()
            case "--jobs": jobs = next().flatMap(Int.init) ?? jobs
            case "--verbose", "-v": verbose = true
            case "--help", "-h": usage(); return
            default:
                if argument.hasPrefix("-") { usage(); exit(2) }
                root = URL(fileURLWithPath: argument)
            }
            index = arguments.index(after: index)
        }

        guard let root = root ?? ProcessInfo.processInfo.environment["PHOTOS_LIBRARY_ROOT"]
            .map({ URL(fileURLWithPath: $0) }) else {
            usage()
            exit(2)
        }

        do {
            try await Scan.run(root: root, limit: limit, albumFilter: albumFilter,
                               jobs: max(1, jobs), verbose: verbose)
        } catch {
            FileHandle.standardError.write(Data("photos-scan: \(error)\n".utf8))
            exit(1)
        }
    }

    static func usage() {
        print("""
        usage: photos-scan [options] <library-root>

          --album <name>      only albums whose path contains this
          --limit <n>         stop after n media files
          --jobs <n>          worker threads (default: core count)
                              budget ~400 MB each: x265 keeps sizeable per-encoder
                              buffers even bounded to one thread, so 16 workers
                              hold ~6.4 GB resident
          --verbose           list every skipped file

        Reads only. Writes nothing to the library and nothing to the zone.
        """)
    }
}
