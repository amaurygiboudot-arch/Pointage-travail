import SwiftUI
import GoogleSignIn

@main
@MainActor
struct HPTravailApp: App {
    @StateObject private var store: WorkStoreV2
    @StateObject private var salaryStore: SalaryV2Store
    @StateObject private var locationManager = LocationManager()
    @StateObject private var authManager = AuthManager()

    init() {
        let workStore = WorkStoreV2()
        _store = StateObject(wrappedValue: workStore)
        _salaryStore = StateObject(
            wrappedValue: SalaryV2Store(
                workSourceProvider: {
                    SalaryWorkSessionBridgeV2.source(
                        from: workStore.sessions,
                        storageReliable: workStore.storageReliable
                    )
                }
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(salaryStore)
                .environmentObject(locationManager)
                .environmentObject(authManager)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
    }
}
