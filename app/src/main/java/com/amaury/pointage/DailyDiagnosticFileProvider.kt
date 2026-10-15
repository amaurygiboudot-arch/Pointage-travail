package com.amaury.pointage

import androidx.core.content.FileProvider

/**
 * A distinct manifest identity keeps diagnostic sharing in Play builds.
 * Manifest merging matches providers by class name, so the Play removal of the
 * internal APK FileProvider must not also remove this user-facing provider.
 */
class DailyDiagnosticFileProvider : FileProvider()
