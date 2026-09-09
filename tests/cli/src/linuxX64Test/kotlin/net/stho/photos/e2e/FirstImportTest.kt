package net.stho.photos.e2e

import kotlin.test.Test
import net.stho.photos.ingest.ExitCode

/**
 * An empty zone and a library with one of everything.
 *
 * This is the scenario that carries the native stack's end-to-end coverage. A single JPEG
 * already drives libjpeg → swscale → lcms2 → libheif + x265, because the preview tier is HEIC;
 * the HEIC source adds libde265 and the video adds ffmpeg. Every one of them runs inside the
 * binary that ships, and every result is asserted rather than printed.
 */
class FirstImportTest {

    @Test
    fun everyMediaTypeIsUploadedAndDecodesToWhatSection5Specifies() = scenario("first-import") {
        library {
            photosignore()
            album("Iceland") {
                jpeg("0001.jpg", width = 320, height = 240)
                heic("0002.heic", width = 320, height = 240)
                video("clip.mp4", width = 64, height = 48, frames = 10)
            }
        }
        zone { empty() }

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone {
                album("Iceland") {
                    photo("0001.jpg")
                    photo("0002.heic")
                    video("clip.mp4")
                }
            }
        }
    }

    @Test
    fun aRunOverALibraryWithNothingInItUploadsNothing() = scenario("first-import-empty") {
        library { photosignore() }
        zone { empty() }

        run("sync")

        expect {
            exit(ExitCode.CLEAN)
            zone { empty() }
        }
    }
}
