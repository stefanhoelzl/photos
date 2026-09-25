/* Face detection and embedding (§12) over OpenCV's dnn: YuNet through FaceDetectorYN, SFace
 * through FaceRecognizerSF.
 *
 * The one C++ unit in the shim, because OpenCV's API is C++. Nothing C++ crosses the header:
 * every entry point catches, and an exception becomes a pi_error like any other failure. */
#include "photos_imaging.h"

#include <stdlib.h>
#include <string.h>

#include <algorithm>
#include <exception>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect/face.hpp>

extern "C" {
#include "pi_internal.h"
}

struct pi_faces {
    cv::Ptr<cv::FaceDetectorYN> detector;
    cv::Ptr<cv::FaceRecognizerSF> embedder;
};

extern "C" void pi_face_result_init(pi_face_result *res) {
    if (res) memset(res, 0, sizeof(*res));
}

extern "C" void pi_face_result_free(pi_face_result *res) {
    if (!res) return;
    free(res->faces);
    free(res->embeddings);
    memset(res, 0, sizeof(*res));
}

extern "C" int pi_faces_open(const char *detector_path, const char *embedder_path,
                             pi_faces **out, pi_error *err) {
    *out = NULL;
    try {
        /* Process-wide, and idempotent: the pipeline's workers are the parallelism. */
        cv::setNumThreads(1);
        pi_faces *f = new pi_faces();
        /* The input size is set per image in pi_faces_find; this one is a placeholder. Score
         * and NMS thresholds are the model zoo's; the caller filters by score again. */
        f->detector = cv::FaceDetectorYN::create(detector_path, "", cv::Size(320, 320), 0.5f, 0.3f, 5000);
        f->embedder = cv::FaceRecognizerSF::create(embedder_path, "");
        if (f->detector.empty() || f->embedder.empty()) {
            delete f;
            return pi_fail(err, PI_ERR_MODEL, "face models failed to load");
        }
        *out = f;
        pi_ok(err);
        return PI_OK;
    } catch (const std::exception &e) {
        return pi_fail(err, PI_ERR_MODEL, "face models: %s", e.what());
    }
}

extern "C" void pi_faces_close(pi_faces *faces) {
    delete faces;
}

extern "C" int pi_faces_find(pi_faces *f, const pi_image *img, int detect_long_edge, float min_score,
                             pi_face_result *out, pi_error *err) {
    pi_face_result_init(out);
    if (!f || !img || !img->pixels || (img->channels != 3 && img->channels != 4))
        return pi_fail(err, PI_ERR_INVALID, "bad image for face detection");
    try {
        /* The shim's pixels are RGB(A); dnn's models were trained on OpenCV's BGR. */
        cv::Mat rgb(img->height, img->width, img->channels == 3 ? CV_8UC3 : CV_8UC4, img->pixels);
        cv::Mat bgr;
        cv::cvtColor(rgb, bgr, img->channels == 3 ? cv::COLOR_RGB2BGR : cv::COLOR_RGBA2BGR);

        /* Detect on a smaller copy -- small faces in a group photo survive ~1280px, and the
         * detector's cost is proportional to its input -- then map the rows back. */
        double scale = 1.0;
        int long_edge = std::max(bgr.cols, bgr.rows);
        cv::Mat small = bgr;
        if (detect_long_edge > 0 && long_edge > detect_long_edge) {
            scale = (double)detect_long_edge / long_edge;
            cv::resize(bgr, small, cv::Size(), scale, scale, cv::INTER_AREA);
        }
        f->detector->setInputSize(small.size());
        cv::Mat rows;
        f->detector->detect(small, rows);

        std::vector<pi_face> kept;
        std::vector<float> embeddings;
        int dims = 0;
        for (int i = 0; i < rows.rows; i++) {
            cv::Mat row = rows.row(i).clone();
            float score = row.at<float>(0, 14);
            if (score < min_score) continue;
            for (int c = 0; c < 14; c++) row.at<float>(0, c) = (float)(row.at<float>(0, c) / scale);

            cv::Mat aligned, feature;
            f->embedder->alignCrop(bgr, row, aligned);
            f->embedder->feature(aligned, feature);

            // Sharpness, on exactly the pixels the embedding saw.
            cv::Mat grey, laplacian;
            cv::cvtColor(aligned, grey, cv::COLOR_BGR2GRAY);
            cv::Laplacian(grey, laplacian, CV_64F);
            cv::Scalar mean, deviation;
            cv::meanStdDev(laplacian, mean, deviation);
            cv::Mat unit;
            cv::normalize(feature.reshape(1, 1), unit, 1.0, 0.0, cv::NORM_L2, CV_32F);
            dims = unit.cols;

            pi_face face;
            face.x = row.at<float>(0, 0);
            face.y = row.at<float>(0, 1);
            face.w = row.at<float>(0, 2);
            face.h = row.at<float>(0, 3);
            for (int k = 0; k < 10; k++) face.landmarks[k] = row.at<float>(0, 4 + k);
            face.score = score;
            face.sharpness = (float)(deviation[0] * deviation[0]);
            kept.push_back(face);
            embeddings.insert(embeddings.end(), unit.ptr<float>(0), unit.ptr<float>(0) + dims);
        }

        if (!kept.empty()) {
            out->faces = (pi_face *)malloc(kept.size() * sizeof(pi_face));
            out->embeddings = (float *)malloc(embeddings.size() * sizeof(float));
            if (!out->faces || !out->embeddings) {
                pi_face_result_free(out);
                return pi_fail(err, PI_ERR_MEMORY, "out of memory for %zu faces", kept.size());
            }
            memcpy(out->faces, kept.data(), kept.size() * sizeof(pi_face));
            memcpy(out->embeddings, embeddings.data(), embeddings.size() * sizeof(float));
        }
        out->count = (int)kept.size();
        out->dims = dims;
        pi_ok(err);
        return PI_OK;
    } catch (const std::exception &e) {
        pi_face_result_free(out);
        return pi_fail(err, PI_ERR_DECODE, "face detection: %s", e.what());
    }
}
