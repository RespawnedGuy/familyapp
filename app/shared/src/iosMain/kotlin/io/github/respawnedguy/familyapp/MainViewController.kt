package io.github.respawnedguy.familyapp

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

/** Entry point for the future iOS app (Xcode project in iosApp/, built on macOS). */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
    App(versionName = version ?: "")
}
