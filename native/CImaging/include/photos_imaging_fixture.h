/* Fixture support.
 *
 * Decision 16 keeps every test input synthesised rather than committed -- the real library is
 * personal data, and a committed corpus goes stale in a way generated inputs cannot. Stills
 * are cheap to synthesise in Kotlin, but a video needs an encoder, and the only one in the
 * build is the one the pipeline ships with. So the fixture writer lives here, beside it.
 *
 * Nothing in the pipeline calls this; it exists for the adapter's tests and :tests:fixtures.
 */
#ifndef PHOTOS_IMAGING_FIXTURE_H
#define PHOTOS_IMAGING_FIXTURE_H

#include "photos_imaging.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Writes a short HEVC/MP4 clip of moving colour bars.
 *
 * rotation, when 90/180/270, is written as a display matrix, so the fixture can exercise the
 * rotation baking that 118 of the library's files need. */
/* content_identifier, when non-NULL, is written as
 * com.apple.quicktime.content.identifier -- the signal decision 14 pairs Live Photos on.
 *
 * with_audio adds an AAC tone as long as the clip, as a phone's own videos carry. Without one no
 * fixture had a soundtrack, which is how a transcoder that dropped every soundtrack passed. */
int pi_fixture_write_video(const char *path, int width, int height,
                           int frames, int rotation,
                           const char *content_identifier, int with_audio, pi_error *err);

/* Writes a HEIC carrying an EXIF block, given an APP1 payload ("Exif\0\0" + TIFF).
 *
 * Exists because the encoder alone cannot produce one: `pi_encode_heic` writes pixels and a
 * colour profile, so every synthetic HEIC had no metadata at all -- which is precisely why a
 * broken HEIF EXIF reader passed the whole suite while silently dropping the date, the GPS
 * and the Live Photo identifier of all 1,531 HEICs in the real library. */
int pi_fixture_write_heic_with_exif(const char *path, int width, int height,
                                    const uint8_t *app1, size_t app1_len, pi_error *err);

#ifdef __cplusplus
}
#endif

#endif /* PHOTOS_IMAGING_FIXTURE_H */
