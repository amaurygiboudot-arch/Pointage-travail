package com.amaury.pointage.v2.model

/**
 * Métadonnées de fiabilité attachées à une liste d'absences issue d'un stockage persistant.
 *
 * Une List<AbsenceV2> ordinaire reste utilisable par les moteurs purs et les tests. Lorsqu'une
 * liste provient du stockage HoraTrack, cette interface permet de distinguer « aucune absence »
 * d'un stockage illisible ou partiellement corrompu.
 */
interface AbsenceSourceStateV2 {
    val absenceSourceReliable: Boolean
    val absenceSourceWarnings: List<String>
}
