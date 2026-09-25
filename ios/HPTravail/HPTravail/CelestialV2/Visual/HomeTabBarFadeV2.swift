import SwiftUI
import UIKit

/// Fade the existing system bar without removing its safe-area/layout slot.
/// No second tab bar, guessed height, window search or layout compensation.
struct HomeTabBarFadeV2: UIViewControllerRepresentable {
    let isVisible: Bool

    func makeUIViewController(context: Context) -> Controller {
        let controller = Controller()
        controller.setVisible(isVisible)
        return controller
    }

    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.setVisible(isVisible)
    }

    static func dismantleUIViewController(_ controller: Controller, coordinator: ()) {
        controller.stopAndRestore()
    }

    final class Controller: UIViewController {
        private var requestedVisible = true
        private var isPresented = false
        private weak var ownedBar: UITabBar?
        private var originalAlpha: CGFloat = 1
        private var originalInteraction = true
        private var originalAccessibilityHidden = false
        private var appliedVisibility: Bool?
        private var fadeAnimator: UIViewPropertyAnimator?

        override func loadView() {
            let transparentView = UIView()
            transparentView.backgroundColor = .clear
            transparentView.isUserInteractionEnabled = false
            view = transparentView
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            isPresented = true
            apply(animated: false)
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            // Resolve only our containing controller, including after reattachment.
            apply(animated: false)
        }

        override func viewWillDisappear(_ animated: Bool) {
            stopAndRestore()
            super.viewWillDisappear(animated)
        }

        func setVisible(_ visible: Bool) {
            requestedVisible = visible
            apply(animated: true)
        }

        private func apply(animated: Bool) {
            guard isPresented, let bar = tabBarController?.tabBar else { return }
            if ownedBar !== bar {
                restoreOwnedBar()
                ownedBar = bar
                originalAlpha = bar.alpha
                originalInteraction = bar.isUserInteractionEnabled
                originalAccessibilityHidden = bar.accessibilityElementsHidden
            }
            guard appliedVisibility != requestedVisible else { return }
            appliedVisibility = requestedVisible
            let currentAlpha = bar.layer.presentation()?.opacity
            fadeAnimator?.stopAnimation(true)
            fadeAnimator = nil
            if let currentAlpha { bar.alpha = CGFloat(currentAlpha) }
            // Transparent tabs must not be actionable or accessible by VoiceOver.
            bar.isUserInteractionEnabled = requestedVisible && originalInteraction
            bar.accessibilityElementsHidden = !requestedVisible || originalAccessibilityHidden
            let targetAlpha: CGFloat = requestedVisible ? originalAlpha : 0
            guard animated, !UIAccessibility.isReduceMotionEnabled else {
                bar.alpha = targetAlpha
                return
            }
            let animator = UIViewPropertyAnimator(
                duration: requestedVisible ? 0.16 : 0.22,
                curve: .easeInOut
            ) {
                bar.alpha = targetAlpha
            }
            fadeAnimator = animator
            animator.startAnimation()
        }

        func stopAndRestore() {
            isPresented = false
            restoreOwnedBar()
        }

        private func restoreOwnedBar() {
            // Stop only our animator: do not cancel unrelated system animations.
            fadeAnimator?.stopAnimation(true)
            fadeAnimator = nil
            if let bar = ownedBar {
                bar.alpha = originalAlpha
                bar.isUserInteractionEnabled = originalInteraction
                bar.accessibilityElementsHidden = originalAccessibilityHidden
            }
            ownedBar = nil
            appliedVisibility = nil
        }
    }
}
