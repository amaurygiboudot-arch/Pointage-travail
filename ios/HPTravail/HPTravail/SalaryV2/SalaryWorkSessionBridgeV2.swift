import Foundation
#if SWIFT_PACKAGE
import RuntimeV2Contract
#endif

/// Source factuelle provenant du journal RuntimeV2.
/// La fiabilité du stockage est transportée séparément : les faits restent visibles pour diagnostic,
/// mais un stockage non fiable interdit toute publication d'un résultat comme confirmé.
struct SalaryWorkSessionSourceV2: Equatable {
    let sessions: [SalarySessionFactV2]
    let reliable: Bool
}

/// Adaptateur unidirectionnel RuntimeV2 -> Salaire V2.
///
/// Aucun rattachement d'entreprise n'est créé ici : `employerId` est recopié tel quel.
/// Une session sans entreprise reste sans entreprise ; une session d'une autre entreprise conserve
/// son identifiant. Le filtrage appartient ensuite à `SalaryPaidWorkAggregatorV2`.
enum SalaryWorkSessionBridgeV2 {
    static func source(
        from sessions: [WorkSession],
        storageReliable: Bool
    ) -> SalaryWorkSessionSourceV2 {
        SalaryWorkSessionSourceV2(
            sessions: sessions.map { session in
                SalarySessionFactV2(
                    id: session.id.uuidString,
                    entry: session.entry,
                    exit: session.exit,
                    employerId: session.employerId,
                    pauses: session.pauses.map { pause in
                        PaidPauseFactV2(
                            start: pause.start,
                            end: pause.end,
                            paid: pause.paid
                        )
                    }
                )
            },
            reliable: storageReliable
        )
    }
}
