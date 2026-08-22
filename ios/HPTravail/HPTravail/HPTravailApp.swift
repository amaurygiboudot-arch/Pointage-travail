import SwiftUI
import GoogleSignIn

@main
struct HPTravailApp: App {
    @StateObject private var store = WorkStore()
    @StateObject private var locationManager = LocationManager()
    @StateObject private var authManager = AuthManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(locationManager)
                .environmentObject(authManager)
                .onAppear {
                    locationManager.requestWhenInUseIfNeeded()
                }
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
    }
}
