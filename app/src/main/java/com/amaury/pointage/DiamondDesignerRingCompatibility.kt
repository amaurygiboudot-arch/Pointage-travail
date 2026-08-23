package com.amaury.pointage

/**
 * Compatibility hook for the designer branch.
 * Ring values are stored/exported by DiamondDesignerCanvas. The production
 * diamond mesh is intentionally not modified here until the dedicated mesh
 * controls are wired and validated.
 */
fun RedDiamondFinalButton.setDesignerRingGains(ring1: Float, ring2: Float, ring3: Float) = Unit
