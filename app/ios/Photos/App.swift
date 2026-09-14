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
    @UIApplicationDelegateAdaptor(OrientationDelegate.self) var delegate

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

// The one question iOS asks the app delegate rather than a view controller: which orientations
// are allowed right now. A SwiftUI app's root is its own hosting controller, so the Compose view
// controller is never asked; the delegate is, and the answer is Kotlin's (§6: landscape only while
// a photo is open).
final class OrientationDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        #if DEBUG
        UIInterfaceOrientationMask(rawValue: UInt(PhotosDebugEntry.shared.supportedOrientations()))
        #else
        UIInterfaceOrientationMask(rawValue: UInt(PhotosEntry.shared.supportedOrientations()))
        #endif
    }
}
