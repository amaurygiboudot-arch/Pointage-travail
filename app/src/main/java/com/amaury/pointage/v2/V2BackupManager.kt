package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.PayrollCoverageAttestationV2

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.amaury.pointage.BackupPreferenceKeyPolicy
import com.amaury.pointage.BackupSecurityPolicy
import com.amaury.pointage.DriveBackupManager
import com.amaury.pointage.GpsPresenceStateKeysV2
import com.amaury.pointage.GeofenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Sauvegarde des données fonctionnelles AGKGMG, sans jetons d'authentification. */
object V2BackupManager {
    private const val FORMAT_VERSION = 6
    private const val OWNERSHIP_SCHEMA = "account-scoped-v1"
    private const val ROOT_FOLDER = "AGKGMG"
    private const val FILE_NAME = "AGKGMG_backup.json"
    private const val RECOVERY_FILE_A = "AGKGMG_backup.recovery_a.json"
    private const val RECOVERY_FILE_B = "AGKGMG_backup.recovery_b.json"
    private val LEGACY_ROOT_FOLDERS = listOf("Pointage Travail")
    private val LEGACY_FILE_NAMES = listOf("HoraTrack_backup.json", "HoraTrack_V2_backup.json")
    private const val RUNTIME_PREFS = "horatrack_v2_test_runtime"
    private const val SALARY_COMPANIES_PREFS = "salary_companies_v2"
    private const val SALARY_COMPANY_PREFIX = "salary_company_"
    private val executor = Executors.newSingleThreadExecutor()

    private val basePreferenceFiles = listOf(
        RUNTIME_PREFS,
        "horatrack_v2_integration",
        "horatrack_v2_migration",
        "horatrack_v2_legal_sources",
        "horatrack_v2_rights",
        "horatrack_v2_payslips",
        SalaryBackupPayloadsV2.CONTRACTS,
        SalaryBackupPayloadsV2.SICKNESS,
        SalaryBackupPayloadsV2.SETTLEMENT,
        SalaryBackupPayloadsV2.DATED_RULES,
        V2PayrollCoverageStore.PREFS,
        V2SegmentedProrationStore.PREFS,
        "horatrack_v2_company_pause",
        "horatrack_v2_gps_state",
        SALARY_COMPANIES_PREFS,
        "salary_settings",
        "gps_settings",
        "shift_profiles",
        "appearance_settings",
        "navigation_state",
        "widget_style",
        "place_names",
        "smart_setup",
        "welcome_preview"
    )

    data class RestoreResult(val restoredFiles:Int,val mergedSessions:Int,
        val quarantinedSalaryFiles:Int = 0, val recoveryId:String? = null) {
        fun summary(): String = if (quarantinedSalaryFiles > 0)
            "$mergedSessions session(s) ajoutée(s). Anciennes données salariales conservées à part : confirme leur propriétaire dans Salaire avant récupération."
        else "Restauration terminée : $mergedSessions session(s) ajoutée(s)."
    }
    internal data class RuntimeMergePlan(val values: Map<String, Any?>, val added: Int)
    internal data class HistoryMergePlan(val history:JSONArray,val added:Int)
    internal data class BackupCopy(
        val fileName: String,
        val createdAtMs: Long,
        val priority: Int
    )
    private data class BackupCandidate(val uri: Uri, val copy: BackupCopy)

    fun backupIfConfiguredAsync(context:Context) {
        val lease = SalaryAccountScope.lease(context)
        if (lease.scope == null) return
        val app = SalaryAccountScope.bindContext(context.applicationContext, lease)
        if (DriveBackupManager.savedTreeUri(app) == null) return
        executor.execute {
            if (SalaryAccountScope.isCurrent(lease)) backupToConfiguredDrive(app)
        }
    }
    fun restoreFreshInstallIfConfiguredAsync(context:Context) {
        val lease = SalaryAccountScope.lease(context)
        if (lease.scope == null) return
        val app = SalaryAccountScope.bindContext(context.applicationContext, lease)
        if (DriveBackupManager.savedTreeUri(app) == null || !isFreshInstall(app)) return
        executor.execute { runCatching {
            if (!SalaryAccountScope.isCurrent(lease)) return@runCatching
            val uri = configuredBackupUri(app) ?: return@runCatching
            if (SalaryAccountScope.isCurrent(lease)) restoreFromUri(app, uri).getOrThrow()
        } }
    }
    fun backupToConfiguredDrive(context:Context):Result<Uri> = runCatching {
        val lease = SalaryAccountScope.lease(context)
        val context = SalaryAccountScope.bindContext(context, lease)
        DriveBackupManager.withStorageAccess {
            val tree = DriveBackupManager.savedTreeUri(context)
                ?: error("Choisis d'abord un dossier Google Drive")
            val content = snapshot(context).toString(2)
            val owner = JSONObject(content).getString("accountScope")
            require(SalaryAccountScope.isCurrent(lease) && owner == lease.scope) { "Compte modifié pendant la sauvegarde" }
            val parsed = runCatching { JSONObject(content) }.getOrNull()
                ?: error("Snapshot de sauvegarde illisible")
            require(isStructurallyRestorableBackup(parsed)) {
                "Snapshot de sauvegarde incomplet"
            }

            val root = treeRootDocumentUri(tree)
            check(SalaryAccountScope.isCurrent(lease)) { "Compte modifié pendant la sauvegarde" }
            val folder = ensureDirectory(context, root, accountBackupFolder(owner))
            val existing = currentRecoveryCandidates(context, folder)
            listOf(FILE_NAME, RECOVERY_FILE_A, RECOVERY_FILE_B).forEach { fileName ->
                val existingUri = findChild(context, folder, fileName, "application/json") ?: return@forEach
                val previousRaw = readBackupText(context, existingUri)
                val previous = JSONObject(previousRaw)
                require(backupOwnerMatches(previous, owner)) { "Ancienne sauvegarde conservée : propriétaire à confirmer" }
                // These historical account envelopes contained global salary payloads. Rotating
                // them must preserve every original before any current-account copy is rewritten.
                preservePriorBackup(context, previousRaw, owner)
            }
            require(SalaryAccountScope.isCurrent(lease)) { "Compte modifié pendant la sauvegarde" }
            val recoveryName = recoveryTargetFileName(existing.map { it.copy })
            val recovery = ensureFile(context, folder, recoveryName, "application/json")

            // Une copie validée est durable avant toute réécriture du fichier principal.
            writeAndVerifyBackup(context, recovery, content, owner)

            check(SalaryAccountScope.isCurrent(lease)) { "Compte modifié pendant la sauvegarde" }
            val canonical = ensureFile(context, folder, FILE_NAME, "application/json")
            // Si cette seconde écriture échoue, la copie recovery validée reste disponible.
            writeAndVerifyBackup(context, canonical, content, owner)

            context.getSharedPreferences("horatrack_v2_backup", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_backup_ms", System.currentTimeMillis())
                .apply()
            canonical
        }
    }
    fun restoreFromUri(context:Context,uri:Uri):Result<RestoreResult> = runCatching {
        val bound = SalaryAccountScope.bindContext(context)
        val raw=DriveBackupManager.withStorageAccess {
            readBackupText(context, uri)
        }
        restoreFromJson(bound,raw).getOrThrow()
    }

    /** Même restauration conservatrice pour Drive et Firestore. */
    fun restoreFromJson(context:Context,raw:String, authenticatedSourceScope: String? = null):Result<RestoreResult> = runCatching {
        val lease = SalaryAccountScope.lease(context)
        val context = SalaryAccountScope.bindContext(context, lease)
        val result = V2RuntimeAccountScope.withScope {
        val root=JSONObject(raw)
        val accountScope = lease.scope
        require(backupOwnerAuthorized(root, accountScope, authenticatedSourceScope)) { "Propriétaire de sauvegarde non confirmé pour ce compte" }
        require(isSupportedFormatVersion(root.optInt("formatVersion",0))){"Format de sauvegarde non reconnu"}
        val scope = requireNotNull(accountScope)
        if (root.optInt("formatVersion") == FORMAT_VERSION) require(
            root.optString("salaryOwnership") == OWNERSHIP_SCHEMA && root.optString("salaryOwner") == scope
        ) { "Propriété des données salariales non confirmée" }
        val files=root.optJSONObject("preferences")?:error("Sauvegarde incomplète")
        val savedNames=files.keys().asSequence().filter(::isManagedPreferenceFileName).filter(BackupSecurityPolicy::canTransferPreferenceFile).toList()
            .sortedWith(compareBy<String> { if (it == RUNTIME_PREFS) 0 else if (it == SALARY_COMPANIES_PREFS) 1 else if (it.startsWith(SALARY_COMPANY_PREFIX)) 2 else 3 }.thenBy { it })
        require(savedNames.isNotEmpty()){"Sauvegarde vide ou sans données reconnues"}
        val payloads=savedNames.associateWith{name->
            val saved=files.optJSONObject(name)?:error("Préférences $name illisibles")
            require(isRestorablePreferencePayload(name, saved)){"Préférences $name invalides"}
            saved
        }
        val legacySalary = if (root.optInt("formatVersion") < FORMAT_VERSION)
            payloads.filterKeys(SalaryPreferenceRegistry::isPersonal).filterValues { it.length() > 0 }
        else emptyMap()
        val recoveryId = if (legacySalary.isNotEmpty()) quarantineLegacySalary(context, raw, lease) else null
        val applicable = payloads.filterKeys { name -> root.optInt("formatVersion") == FORMAT_VERSION || !SalaryPreferenceRegistry.isPersonal(name) }
        var merged = 0
        val restoredResult = V2RuntimeStore.withTransaction {
            V2RestoreTransaction.execute(context, scope, { SalaryAccountScope.isCurrent(lease) }) {
                val plans = prepareRestorePlans(context, applicable, scope) { merged = it }
                // The previous GPS evidence is part of the undo journal too.
                if (plans.isNotEmpty()) {
                    val gps = (plans["gps_settings"] ?: preferences(context, "gps_settings").all).toMutableMap()
                    GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach(gps::remove)
                    plans["gps_settings"] = gps
                }
                plans
            }
            RestoreResult(applicable.size, merged, legacySalary.size, recoveryId)
        }
        restoredResult
        }
        // Publication intentionally invalidated the old read lease. Only a still matching account
        // may refresh side effects, using its new lease after leaving the pinned runtime operation.
        val current = SalaryAccountScope.capture()
        if (result.restoredFiles > 0 && current.scope == lease.scope && SalaryAccountScope.isCurrent(current)) {
            val refreshed = SalaryAccountScope.bindContext(SalaryAccountScope.rawContext(context), current)
            V2ProfileStore.bind(refreshed)
            GeofenceManager.reconfigureStoredZones(refreshed)
            com.amaury.pointage.CompanyPauseAlarmManager.scheduleAll(refreshed)
        }
        result
    }

    /** Importe un ancien cloud directement dans le moteur actuel, sans toucher au stockage legacy local. */
    fun importLegacyPointageJson(context:Context,legacy:JSONArray):Int =
        V2MigrationManager.importLegacyArray(context.applicationContext, legacy).imported

    fun snapshot(context:Context):JSONObject = V2RuntimeAccountScope.withScope {
        val lease = SalaryAccountScope.lease(context)
        val accountScope = lease.scope ?: error("Compte indisponible")
        val bound = SalaryAccountScope.bindContext(context, lease)
        require(!SalaryLegacyRecovery.hasUnresolved(bound)) {
            "Anciennes données salariales à attribuer ou à conserver séparément avant la sauvegarde du compte"
        }
        require(DatedWorkRuleStoreV2.prepareOwnedLegacyForBackup(bound)) {
            "Anciennes règles individuelles non confirmées : sauvegarde incomplète"
        }
        V2RuntimeStore.withTransaction {
            V2RestoreTransaction.withAccess(bound, accountScope, { error(V2RestoreTransaction.WARNING) }) {
                require(SalaryAccountScope.available(bound)) { "Compte salaire indisponible" }
                V2RuntimeReader.allSessions(bound).requireReliable()
                val all=JSONObject()
                transferablePreferenceFiles(bound).forEach{all.put(it,encodePreferences(bound,it))}
                val decoded = all.keys().asSequence().associateWith { V2PreferenceValues.decode(all.getJSONObject(it)) }
                SalaryBackupPayloadsV2.validateDependencies(decoded[SalaryBackupPayloadsV2.DATED_RULES].orEmpty(), accountScope,
                    decoded[SalaryBackupPayloadsV2.CONTRACTS].orEmpty(), decoded[SALARY_COMPANIES_PREFS].orEmpty())
                check(SalaryAccountScope.isCurrent(lease)) { "Compte modifié pendant la sauvegarde" }
                JSONObject().put("accountScope", accountScope).put("salaryOwner", accountScope)
                    .put("salaryOwnership", OWNERSHIP_SCHEMA).put("formatVersion",FORMAT_VERSION)
                    .put("schemaVersion",HoraTrackV2.SCHEMA_VERSION).put("createdAtMs",System.currentTimeMillis()).put("preferences",all)
            }
        }
    }

    internal fun backupOwnerMatches(root: JSONObject, accountScope: String?): Boolean =
        !accountScope.isNullOrBlank() && root.opt("accountScope") is String &&
            root.optString("accountScope") == accountScope

    internal fun backupOwnerAuthorized(root: JSONObject, scope: String?, authenticatedSourceScope: String?): Boolean =
        backupOwnerMatches(root, scope) || (!root.has("accountScope") &&
            scope != null && scope.startsWith("uid:") && scope.length > 4 && authenticatedSourceScope == scope)

    internal fun preferences(context: Context, name: String) = when {
        SalaryPreferenceRegistry.isPersonal(name) -> SalaryAccountScope.preferences(context, name)
        name == RUNTIME_PREFS || name == "horatrack_v2_migration" -> V2RuntimeAccountScope.preferences(context, name)
        else -> RestoreGuardedPreferences.open(context, name)
    }

    internal fun isSupportedFormatVersion(version:Int):Boolean = version in 1..FORMAT_VERSION

    /** Noms que ce format de sauvegarde est autorisé à restaurer. */
    internal fun isManagedPreferenceFileName(name:String):Boolean =
        name in basePreferenceFiles || SalaryPreferenceRegistry.isTransferable(name)

    internal fun transferablePreferenceFiles(context:Context):List<String> {
        val scope = SalaryAccountScope.lease(context).scope ?: return emptyList()
        val personal = SalaryPreferenceRegistry.transferableNames(context, scope)
        return (basePreferenceFiles.filterNot(SalaryPreferenceRegistry::isPersonal) + personal)
            .distinct()
            .filter(::isManagedPreferenceFileName)
            .filter(BackupSecurityPolicy::canTransferPreferenceFile)
            .sorted()
    }

    private fun isFreshInstall(context:Context):Boolean {
        val runtime=preferences(context,RUNTIME_PREFS)
        val history=V2RuntimeHistoryGuardV2.read(context)
        if(!history.reliable)return false
        val runtimeEntry=runtime.all["real_entry"]
        if(runtime.contains("real_entry")&&strictLong(runtimeEntry)==null)return false
        val legacyPrefs=context.getSharedPreferences("pointage",Context.MODE_PRIVATE)
        val legacy=if(!legacyPrefs.contains("data"))JSONArray() else {
            val raw=runCatching{legacyPrefs.getString("data",null)}.getOrNull()?:return false
            runCatching{JSONArray(raw)}.getOrNull()?:return false
        }
        if (!SalaryAccountScope.available(context)) return false
        val salary=preferences(context,"salary_settings")
        val salaryV2=preferences(context,SALARY_COMPANIES_PREFS)
        val hasRuntime=(strictLong(runtimeEntry)?:0L)>0L||history.history.length()>0
        val hasLegacy=legacy.length()>0
        return !hasRuntime&&!hasLegacy&&salary.all.isEmpty()&&salaryV2.all.isEmpty()
    }

    private fun prepareRestorePlans(
        context: Context, payloads: Map<String, JSONObject>, scope: String,
        mergedSessions: (Int) -> Unit
    ): MutableMap<String, Map<String, Any?>> {
        require(SalaryAccountScope.available(context)) { "Compte salaire indisponible" }
        val plans = linkedMapOf<String, Map<String, Any?>>()
        payloads.forEach { (name, payload) ->
            val saved = V2PreferenceValues.decode(payload).filterKeys { BackupPreferenceKeyPolicy.canTransfer(name, it) }
            val local = preferences(context, name).all
            plans[name] = when (name) {
                RUNTIME_PREFS -> prepareRuntimeMerge(context, payload).let { mergedSessions(it.added); it.values }
                V2PayrollCoverageStore.PREFS -> local.toMutableMap().apply {
                    put(V2PayrollCoverageStore.KEY_ITEMS, requireNotNull(V2PayrollCoverageStore.encode(prepareCoverageMerge(context, payload))))
                }
                SalaryBackupPayloadsV2.CONTRACTS -> SalaryBackupPayloadsV2.mergeContracts(local, saved)
                SalaryBackupPayloadsV2.SICKNESS -> SalaryBackupPayloadsV2.mergeSickness(local, saved)
                SalaryBackupPayloadsV2.SETTLEMENT -> SalaryBackupPayloadsV2.mergeSettlement(local, saved)
                SalaryBackupPayloadsV2.DATED_RULES -> SalaryBackupPayloadsV2.mergeRules(local, saved, scope)
                else -> (local + saved).mapValues { it.value }
            }
        }
        fun values(name: String): Map<String, *> = plans[name] ?: preferences(context, name).all
        SalaryBackupPayloadsV2.validateDependencies(values(SalaryBackupPayloadsV2.DATED_RULES), scope,
            values(SalaryBackupPayloadsV2.CONTRACTS), values(SALARY_COMPANIES_PREFS))
        return plans
    }

    data class LegacySalaryRecovery(val id: String, val fingerprint: String, val createdAtMs: Long, val fileCount: Int)

    private fun recoveryDirectory(context: Context) = File(SalaryAccountScope.rawContext(context).noBackupFilesDir,
        "agkgmg_salary_recovery")

    private fun fingerprint(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized
    private fun quarantineLegacySalary(context: Context, raw: String, lease: SalaryAccountScope.Lease): String {
        require(SalaryAccountScope.isCurrent(lease)) { "Compte modifié pendant la récupération" }
        val id = fingerprint(raw)
        val directory = recoveryDirectory(context)
        check(directory.isDirectory || directory.mkdirs()) { "Impossible de conserver l'ancienne sauvegarde" }
        val file = android.util.AtomicFile(File(directory, "$id.json"))
        if (file.baseFile.exists()) {
            check(String(file.readFully(), Charsets.UTF_8) == raw) { "Ancienne sauvegarde locale différente" }
        } else {
            val stream = file.startWrite()
            try {
                stream.write(raw.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (failure: Exception) { file.failWrite(stream); throw failure }
            check(String(file.readFully(), Charsets.UTF_8) == raw) { "Ancienne sauvegarde non conservée" }
        }
        require(SalaryAccountScope.isCurrent(lease)) { "Compte modifié pendant la récupération" }
        return id
    }

    /** Shared by Drive rotation and Firestore replacement: old globally sourced data is retained. */
    internal fun preservePriorBackup(context: Context, raw: String, authenticatedScope: String): String? {
        val lease = SalaryAccountScope.lease(context)
        require(lease.scope == authenticatedScope && SalaryAccountScope.isCurrent(lease))
        val previous = JSONObject(raw)
        require(backupOwnerAuthorized(previous, authenticatedScope, authenticatedScope)) {
            "Sauvegarde existante d'un autre propriétaire conservée sans modification"
        }
        require(isSupportedFormatVersion(previous.optInt("formatVersion"))) {
            "Sauvegarde existante d'une autre version conservée sans modification"
        }
        return if (previous.getInt("formatVersion") < FORMAT_VERSION) quarantineLegacySalary(context, raw, lease)
        else {
            require(previous.optString("salaryOwnership") == OWNERSHIP_SCHEMA &&
                previous.optString("salaryOwner") == authenticatedScope) { "Propriété de sauvegarde non confirmée" }
            null
        }
    }

    /** Only counts/dates are exposed before ownership is explicitly asserted; no salary preview. */
    fun legacySalaryRecoveries(context: Context): List<LegacySalaryRecovery> {
        if (!SalaryAccountScope.available(context)) return emptyList()
        val scope = SalaryAccountScope.lease(context).scope
        val claims = SalaryAccountScope.rawContext(context).getSharedPreferences("salary_legacy_recovery_owner", 0)
        return recoveryDirectory(context).listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}\\.json")) }
            .mapNotNull { file -> runCatching {
                val raw = file.readText(Charsets.UTF_8)
                val id = file.name.removeSuffix(".json")
                require(fingerprint(raw) == id)
                val claim = claims.all["backup:$id"] as? String
                if (claim != null) require(JSONObject(claim).getString("scope") == scope)
                val root = JSONObject(raw)
                require(root.getInt("formatVersion") in 1 until FORMAT_VERSION)
                val files = root.getJSONObject("preferences")
                val count = files.keys().asSequence().count { SalaryPreferenceRegistry.isPersonal(it) && files.getJSONObject(it).length() > 0 }
                LegacySalaryRecovery(id, id, root.optLong("createdAtMs", 0), count)
            }.getOrNull() }.filter { it.fileCount > 0 }.sortedByDescending { it.createdAtMs }
    }

    /** Explicit attribution is never implied by the accountScope of an old globally sourced backup. */
    fun confirmLegacySalaryRestore(context: Context, id: String, expectedFingerprint: String,
        ownershipConfirmed: Boolean): Result<RestoreResult> = runCatching {
        val lease = SalaryAccountScope.lease(context)
        val scope = requireNotNull(lease.scope)
        val bound = SalaryAccountScope.bindContext(context, lease)
        require(ownershipConfirmed && scope.startsWith("uid:") && SalaryAccountScope.isCurrent(lease)) {
            "Confirme le compte propriétaire de ces anciennes données salariales" }
        require(id.matches(Regex("[0-9a-f]{64}")) && expectedFingerprint == id)
        val raw = File(recoveryDirectory(bound), "$id.json").readText(Charsets.UTF_8)
        require(fingerprint(raw) == expectedFingerprint) { "Ancienne sauvegarde modifiée : recommence la vérification" }
        val root = JSONObject(raw)
        require(root.getInt("formatVersion") in 1 until FORMAT_VERSION)
        val files = root.getJSONObject("preferences")
        val payloads = files.keys().asSequence().filter(SalaryPreferenceRegistry::isPersonal)
            .filter(SalaryPreferenceRegistry::isTransferable).associateWith { name ->
                val value = files.getJSONObject(name)
                require(isRestorablePreferencePayload(name, value)) { "Anciennes données salariales invalides" }
                value
            }
        val receiptName = "salary_account_recovery_receipt"
        V2RuntimeStore.withTransaction {
            V2RestoreTransaction.execute(bound, scope, { SalaryAccountScope.isCurrent(lease) }) {
                require(fingerprint(File(recoveryDirectory(bound), "$id.json").readText(Charsets.UTF_8)) == expectedFingerprint)
                val receipts = preferences(bound, receiptName).all
                val receiptKey = "backup:$id"
                val globalReceipts = SalaryAccountScope.rawContext(bound).getSharedPreferences("salary_legacy_recovery_owner", 0).all
                globalReceipts[receiptKey]?.let { claim -> require(claim is String && JSONObject(claim).getString("scope") == scope) {
                    "Ces anciennes données ont déjà été attribuées à un autre compte" } }
                if (receipts.containsKey(receiptKey)) {
                    val receipt = JSONObject(receipts[receiptKey] as String)
                    require(receipt.getString("scope") == scope && receipt.getString("fingerprint") == id)
                    emptyMap()
                } else {
                    require(SalaryPreferenceRegistry.transferableNames(bound, scope).all { name ->
                        preferences(bound, name).all.isEmpty()
                    }) { "Des données salariales existent déjà pour ce compte : récupération automatique refusée pour préserver les deux sources" }
                    prepareRestorePlans(bound, payloads, scope) {}.apply {
                        val receipt = JSONObject().put("scope", scope).put("fingerprint", id)
                            .put("confirmedAtMs", System.currentTimeMillis()).toString()
                        put(receiptName, (receipts + (receiptKey to receipt)).mapValues { it.value })
                        put("salary_legacy_recovery_owner", (globalReceipts + (receiptKey to receipt)).mapValues { it.value })
                    }
                }
            }
        }
        RestoreResult(payloads.size, 0)
    }
    private fun configuredBackupUri(context:Context):Uri? = DriveBackupManager.withStorageAccess {
        val tree = DriveBackupManager.savedTreeUri(context) ?: return@withStorageAccess null
        val root = treeRootDocumentUri(tree)
        val candidates = mutableListOf<BackupCandidate>()

        backupSearchLocations(V2RuntimeAccountScope.currentScope()).groupBy { it.first }.forEach { (folderName, locations) ->
            val folder = findChild(context, root, folderName, DocumentsContract.Document.MIME_TYPE_DIR)
                ?: return@forEach
            locations.forEach { (_, fileName) ->
                findChild(context, folder, fileName, "application/json")
                    ?.let { inspectBackupCandidate(context, it, fileName, backupPriority(fileName)) }
                    ?.let(candidates::add)
            }
        }

        candidates.maxWithOrNull(compareBy<BackupCandidate> { it.copy.createdAtMs }
            .thenBy { it.copy.priority })?.uri
    }

    private fun currentRecoveryCandidates(context: Context, folder: Uri): List<BackupCandidate> =
        listOf(
            FILE_NAME to 30,
            RECOVERY_FILE_A to 20,
            RECOVERY_FILE_B to 20
        ).mapNotNull { (fileName, priority) ->
            findChild(context, folder, fileName, "application/json")
                ?.let { inspectBackupCandidate(context, it, fileName, priority) }
        }

    /** A separate stable directory preserves unowned historical backups byte-for-byte. */
    internal fun accountBackupFolder(scope: String): String =
        requireNotNull(V2RuntimeAccountScope.fileName(ROOT_FOLDER, scope)) { "Compte indisponible" }

    internal fun backupSearchLocations(scope: String? = null): List<Pair<String, String>> =
        (listOfNotNull(scope?.let(::accountBackupFolder), ROOT_FOLDER) + LEGACY_ROOT_FOLDERS).flatMap { folder ->
            (listOf(FILE_NAME) + LEGACY_FILE_NAMES + listOf(RECOVERY_FILE_A, RECOVERY_FILE_B))
                .map { file -> folder to file }
        }

    private fun backupPriority(fileName: String): Int = when (fileName) {
        FILE_NAME -> 30
        RECOVERY_FILE_A, RECOVERY_FILE_B -> 20
        else -> 10
    }

    internal fun recoveryTargetFileName(candidates: List<BackupCopy>): String {
        val best = candidates.maxWithOrNull(compareBy<BackupCopy> { it.createdAtMs }.thenBy { it.priority })
        if (best?.fileName == RECOVERY_FILE_A) return RECOVERY_FILE_B
        if (best?.fileName == RECOVERY_FILE_B) return RECOVERY_FILE_A

        val a = candidates.firstOrNull { it.fileName == RECOVERY_FILE_A }
        val b = candidates.firstOrNull { it.fileName == RECOVERY_FILE_B }
        return when {
            a == null -> RECOVERY_FILE_A
            b == null -> RECOVERY_FILE_B
            a.createdAtMs <= b.createdAtMs -> RECOVERY_FILE_A
            else -> RECOVERY_FILE_B
        }
    }

    private fun inspectBackupCandidate(
        context: Context,
        uri: Uri,
        fileName: String,
        priority: Int
    ): BackupCandidate? {
        val raw = readBackupText(context, uri)
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!backupOwnerMatches(root, V2RuntimeAccountScope.currentScope())) return null
        return inspectBackupContent(fileName, priority) { raw }?.let { BackupCandidate(uri, it) }
    }

    /** Une erreur I/O reste une erreur : elle ne rend jamais une copie disponible à l'écrasement. */
    internal fun inspectBackupContent(fileName: String, priority: Int, read: () -> String): BackupCopy? {
        val raw = read()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!isStructurallyRestorableBackup(root)) return null
        val createdAtMs = strictLong(root.opt("createdAtMs"))?.takeIf { it >= 0L } ?: 0L
        return BackupCopy(fileName, createdAtMs, priority)
    }

    private fun writeAndVerifyBackup(context: Context, uri: Uri, content: String, owner: String) {
        val lease = SalaryAccountScope.lease(context)
        check(SalaryAccountScope.isCurrent(lease) && owner == lease.scope) { "Compte modifié pendant la sauvegarde" }
        context.contentResolver.openOutputStream(uri, "w")
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { writer ->
                check(SalaryAccountScope.isCurrent(lease)) { "Compte modifié pendant la sauvegarde" }
                writer.write(content)
                writer.flush()
            }
            ?: error("Impossible d'écrire la sauvegarde")

        val verified = readBackupText(context, uri)
        check(verified == content) { "La sauvegarde relue diffère du snapshot écrit" }
        val parsed = runCatching { JSONObject(verified) }.getOrNull()
            ?: error("La sauvegarde écrite n'est pas un JSON valide")
        check(isStructurallyRestorableBackup(parsed)) {
            "La sauvegarde écrite n'est pas restaurable"
        }
    }

    private fun readBackupText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)
            ?.use { input -> Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(input.readBytes())).toString() }
            ?: error("Impossible de lire la sauvegarde existante")

    private fun encodePreferences(context:Context,name:String):JSONObject { val out=JSONObject();preferences(context,name).all.forEach{(k,v)->if(BackupPreferenceKeyPolicy.canTransfer(name,k))when(v){is String->out.put(k,JSONObject().put("t","s").put("v",v));is Boolean->out.put(k,JSONObject().put("t","b").put("v",v));is Int->out.put(k,JSONObject().put("t","i").put("v",v));is Long->out.put(k,JSONObject().put("t","l").put("v",v));is Float->out.put(k,JSONObject().put("t","f").put("v",v.toDouble()));is Set<*>->out.put(k,JSONObject().put("t","set").put("v",JSONArray(v.filterIsInstance<String>())))}};return out }
    internal fun isStructurallyRestorableBackup(root: JSONObject): Boolean {
        if (!isSupportedFormatVersion(root.optInt("formatVersion", 0))) return false
        if (root.optInt("formatVersion") == FORMAT_VERSION &&
            (root.optString("salaryOwnership") != OWNERSHIP_SCHEMA ||
                !backupOwnerMatches(root, root.optString("salaryOwner")))) return false
        val files = root.optJSONObject("preferences") ?: return false
        val names = files.keys().asSequence()
            .filter(::isManagedPreferenceFileName)
            .filter(BackupSecurityPolicy::canTransferPreferenceFile)
            .toList()
        if (names.isEmpty()) return false

        for (name in names) {
            val saved = files.optJSONObject(name) ?: return false
            if (!isRestorablePreferencePayload(name, saved)) return false
        }
        return true
    }

    /** Même validation avant remplacement Drive et avant toute mutation lors de la restauration. */
    internal fun isRestorablePreferencePayload(name: String, saved: JSONObject): Boolean {
        if (!isValidTypedPreferencePayload(saved)) return false
        return when (name) {
            RUNTIME_PREFS -> runCatching { mergeRuntimeValues(emptyMap<String, Any>(), decodeRuntimeValues(saved)) }.isSuccess
            V2PayrollCoverageStore.PREFS -> runCatching { decodeBackupCoverage(saved) }.isSuccess
            V2SegmentedProrationStore.PREFS -> saved.keys().asSequence().all { key ->
                val item = saved.optJSONObject(key)
                val raw = item?.opt("v") as? String
                item?.optString("t") == "s" && raw != null && V2SegmentedProrationStore.decode(raw) != null
            }
            SalaryBackupPayloadsV2.CONTRACTS, SalaryBackupPayloadsV2.SICKNESS, SalaryBackupPayloadsV2.SETTLEMENT, SalaryBackupPayloadsV2.DATED_RULES ->
                runCatching { SalaryBackupPayloadsV2.valid(name, V2PreferenceValues.decode(saved)) }.getOrDefault(false)
            else -> true
        }
    }

    internal fun isValidTypedPreferencePayload(saved:JSONObject):Boolean {
        val keys=saved.keys()
        while(keys.hasNext()){
            val key=keys.next();val item=saved.optJSONObject(key)?:return false
            if(!item.has("v")||item.isNull("v"))return false
            val value=item.opt("v")
            val valid=when(item.optString("t")){
                "s"->value is String
                "b"->value is Boolean
                "i"->strictLong(value)?.let{it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()}==true
                "l"->strictLong(value)!=null
                "f"->(value as? Number)?.let { it.toDouble().isFinite() && it.toFloat().isFinite() }==true
                "set"->{val array=value as? JSONArray?:return false;(0 until array.length()).all{array.opt(it) is String}}
                else->false
            }
            if(!valid)return false
        }
        return true
    }

    internal fun decodeBackupHistory(saved:JSONObject):JSONArray {
        if(!saved.has("history"))return JSONArray()
        val item=saved.optJSONObject("history")?:error("Historique de sauvegarde mal typé")
        val raw=(item.opt("v") as? String)?.takeIf{item.optString("t")=="s"}
            ?:error("Historique de sauvegarde mal typé")
        val decoded=V2RuntimeHistoryGuardV2.decode(raw)
        require(decoded.reliable){"Historique de sauvegarde illisible ou incohérent"}
        return JSONArray(decoded.history.toString())
    }

    internal fun decodeBackupCoverage(saved:JSONObject):List<PayrollCoverageAttestationV2>{
        if(!saved.has(V2PayrollCoverageStore.KEY_ITEMS))return emptyList()
        val item=saved.optJSONObject(V2PayrollCoverageStore.KEY_ITEMS)?:error("Couverture paie de sauvegarde mal typée")
        val raw=(item.opt("v") as? String)?.takeIf{item.optString("t")=="s"}
            ?:error("Couverture paie de sauvegarde mal typée")
        val decoded=V2PayrollCoverageStore.decode(raw)
        require(decoded.reliable){"Couverture paie de sauvegarde illisible ou incohérente"}
        return decoded.attestations
    }

    internal fun mergeCoverageAttestations(
        current:List<PayrollCoverageAttestationV2>,
        saved:List<PayrollCoverageAttestationV2>
    ):List<PayrollCoverageAttestationV2>{
        require(V2PayrollCoverageStore.encode(current)!=null){
            "Couverture paie locale illisible ou incohérente"
        }
        require(V2PayrollCoverageStore.encode(saved)!=null){
            "Couverture paie de sauvegarde illisible ou incohérente"
        }
        val merged=current.toMutableList()
        val byId=current.associateBy{it.id}.toMutableMap()
        saved.forEach{remote->
            val local=byId[remote.id]
            if(local==null){
                merged+=remote
                byId[remote.id]=remote
            }else require(local==remote){
                "L'attestation de couverture "+remote.id+" diffère entre le téléphone et la sauvegarde"
            }
        }
        require(V2PayrollCoverageStore.encode(merged)!=null){
            "Fusion de couverture paie incohérente"
        }
        return merged
    }

    internal fun mergeHistories(current:JSONArray,saved:JSONArray):HistoryMergePlan {
        require(V2RuntimeHistoryGuardV2.inspect(current).reliable){"Historique local illisible ou incohérent"}
        require(V2RuntimeHistoryGuardV2.inspect(saved).reliable){"Historique de sauvegarde illisible ou incohérent"}
        val merged=JSONArray(current.toString())
        val localById=mutableMapOf<String,JSONObject>()
        for(index in 0 until merged.length()){
            val item=merged.getJSONObject(index)
            localById[item.getString("id")]=item
        }
        var added=0
        for(index in 0 until saved.length()){
            val item=saved.getJSONObject(index);val id=item.getString("id")
            val local=localById[id]
            if(local==null){
                val copy=JSONObject(item.toString())
                merged.put(copy);localById[id]=copy;added++
            }else require(canonicalJson(local)==canonicalJson(item)){
                "La session $id diffère entre le téléphone et la sauvegarde"
            }
        }
        require(V2RuntimeHistoryGuardV2.inspect(merged).reliable){"Fusion d'historique incohérente"}
        return HistoryMergePlan(merged,added)
    }

    private fun canonicalJson(value:Any?):String=when(value){
        is JSONObject->value.keys().asSequence().toList().sorted()
            .joinToString(prefix="{",postfix="}"){key->JSONObject.quote(key)+":"+canonicalJson(value.get(key))}
        is JSONArray->(0 until value.length()).joinToString(prefix="[",postfix="]"){index->canonicalJson(value.get(index))}
        JSONObject.NULL,null->"null"
        is String->JSONObject.quote(value)
        is Number->java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        else->value.toString()
    }

    private const val ACTIVE_RECOVERY_RECEIPT = "legacy_recovery_receipt"
    private const val RESTORED_PROVENANCE = "restored_legacy_provenance"

    internal fun decodeRuntimeValues(saved: JSONObject): Map<String, Any?> {
        require(isValidTypedPreferencePayload(saved)) { "Journal sauvegardé invalide" }
        return saved.keys().asSequence().associateWith { key ->
            val item = saved.getJSONObject(key)
            when (item.getString("t")) {
                "s" -> item.getString("v")
                "b" -> item.getBoolean("v")
                "l" -> strictLong(item.get("v")) ?: error("Long invalide")
                "i" -> (strictLong(item.get("v")) ?: error("Entier invalide")).toInt()
                else -> error("Type non pris en charge dans le journal")
            }
        }
    }

    /** Restore facts atomically; foreign recovery receipts are evidence, never local ownership. */
    internal fun mergeRuntimeValues(local: Map<String, *>, saved: Map<String, *>): RuntimeMergePlan {
        val metadata = setOf(ACTIVE_RECOVERY_RECEIPT, RESTORED_PROVENANCE)
        fun facts(values: Map<String, *>) = values.filterKeys { it !in metadata }
        val left = facts(local)
        val right = facts(saved)
        val merged = requireNotNull(V2LegacyJournalRecovery.merge(left, right)) {
            "Session courante ou historique en conflit : restauration annulée sans remplacer les faits locaux"
        }.toMutableMap()
        val before = JSONArray(left["history"] as? String ?: "[]").length()
        val after = JSONArray(merged["history"] as? String ?: "[]").length()
        local[ACTIVE_RECOVERY_RECEIPT]?.let {
            require(it is String)
            merged[ACTIVE_RECOVERY_RECEIPT] = it
        }
        val provenance = linkedSetOf<String>()
        for (values in listOf(local, saved)) {
            values[RESTORED_PROVENANCE]?.let { raw ->
                require(raw is String)
                val items = JSONArray(raw)
                for (i in 0 until items.length()) provenance += items.getString(i)
            }
        }
        saved[ACTIVE_RECOVERY_RECEIPT]?.let { require(it is String); provenance += it }
        if (provenance.isNotEmpty()) merged[RESTORED_PROVENANCE] = JSONArray(provenance.sorted()).toString()
        return RuntimeMergePlan(merged, after - before)
    }

    private fun prepareRuntimeMerge(context:Context,saved:JSONObject):RuntimeMergePlan {
        V2RuntimeReader.allSessions(context).requireReliable()
        return mergeRuntimeValues(preferences(context, RUNTIME_PREFS).all, decodeRuntimeValues(saved))
    }

    private fun prepareCoverageMerge(
        context:Context,
        saved:JSONObject
    ):List<PayrollCoverageAttestationV2>{
        val local=V2PayrollCoverageStore.read(context)
        require(local.reliable){"Couverture paie locale illisible : restauration bloquée"}
        return mergeCoverageAttestations(local.attestations,decodeBackupCoverage(saved))
    }

    private fun strictLong(value:Any?):Long?=when(value){
        is Byte,is Short,is Int,is Long->(value as Number).toLong()
        is Float,is Double->{val number=(value as Number).toDouble();number.takeIf{it.isFinite()&&it%1.0==0.0&&it>=Long.MIN_VALUE.toDouble()&&it<Long.MAX_VALUE.toDouble()}?.toLong()}
        is String->value.trim().toLongOrNull()
        else->null
    }
    private fun treeRootDocumentUri(u:Uri):Uri=DocumentsContract.buildDocumentUriUsingTree(u,DocumentsContract.getTreeDocumentId(u))
    private fun ensureDirectory(c:Context,p:Uri,n:String):Uri=findChild(c,p,n,DocumentsContract.Document.MIME_TYPE_DIR)?:DocumentsContract.createDocument(c.contentResolver,p,DocumentsContract.Document.MIME_TYPE_DIR,n)?:error("Impossible de créer $n")
    private fun ensureFile(c:Context,p:Uri,n:String,m:String):Uri=findChild(c,p,n,m)?:DocumentsContract.createDocument(c.contentResolver,p,m,n)?:error("Impossible de créer $n")
    private fun findChild(c:Context,p:Uri,n:String,m:String):Uri?{val id=DocumentsContract.getDocumentId(p);val children=DocumentsContract.buildChildDocumentsUriUsingTree(p,id);val projection=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE);(c.contentResolver.query(children,projection,null,null,null)?:error("Impossible de lire le dossier de sauvegarde")).use{cur->val ci=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val cn=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);val cm=cur.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);while(cur.moveToNext())if(cur.getString(cn)==n&&cur.getString(cm)==m)return DocumentsContract.buildDocumentUriUsingTree(p,cur.getString(ci))};return null}
}
