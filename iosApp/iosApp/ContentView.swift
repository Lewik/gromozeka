import SwiftUI
import UIKit
import GromozekaPresentation

struct ContentView: View {
    var body: some View {
        GromozekaComposeView()
    }
}

private struct GromozekaComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosMainKt.GromozekaMainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}
