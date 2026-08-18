import SwiftUI

@main
struct HPTravailApp: App {
    @StateObject private var store = WorkStore()
    @StateObject private var locationManager = LocationManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(locationManager)
                .onAppear {
                    locationManager.requestWhenInUseIfNeeded()
                }
        }
    }
}
