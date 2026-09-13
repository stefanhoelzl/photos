import SwiftUI
import PhotosKit

// The entire Swift side of this application.
//
// Compose draws through Skia rather than composing UIKit views (DESIGN §6), so there is no
// SwiftUI view hierarchy to build: PhotosKit hands back one UIViewController and this presents
// it. Everything a person sees is Kotlin, compiled from `:ui` and identical to what the Linux
// harness renders.
@main
struct PhotosApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

// The bridge, and it is deliberately this small. Each configuration links its own PhotosKit and
// calls its one entry point: Debug's also starts the control server when launched with a port,
// and Release's framework has no control server in it to start (decision 5).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG
        PhotosDebugEntry.shared.viewController()
        #else
        PhotosEntry.shared.viewController()
        #endif
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
