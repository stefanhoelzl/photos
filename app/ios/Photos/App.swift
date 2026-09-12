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

// The bridge, and it is deliberately this small. `PhotosEntry.viewController()` is the
// framework's whole exported surface -- one object, one function -- so nothing here can drift
// from the Kotlin side without the compiler saying so.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        PhotosEntry.shared.viewController()
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
