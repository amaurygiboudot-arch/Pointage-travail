package com.amaury.pointage

/**
 * Compatibilité Kotlin pour les tableaux primitifs.
 * Certaines versions de la stdlib utilisées par le build Android n'exposent
 * pas mapNotNull directement sur IntArray.
 */
inline fun <R : Any> IntArray.mapNotNull(transform: (Int) -> R?): List<R> {
    val result = ArrayList<R>(size)
    for (value in this) {
        transform(value)?.let(result::add)
    }
    return result
}
