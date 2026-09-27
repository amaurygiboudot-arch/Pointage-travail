package com.amaury.pointage

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import org.json.JSONArray
import org.json.JSONObject

enum class ObjectiveSyncStatus(val label: String) {
    DEVICE("Enregistré sur l'appareil"),
    PENDING("Synchronisation en attente"),
    ONLINE("Sauvegardé en ligne")
}

object ObjectiveDeliveryGameStore {
    private const val PREFS = "objective_delivery_game"
    private const val KEY_ACTIVE_TYPE = "active_company_type"
    private const val KEY_SYNC_STATUS_PREFIX = "sync_status_"
    private const val CAMPAIGN_PREFIX = "campaign_"
    private const val GAME_FIREBASE_APP = "objective-delivery-game"

    private data class FirebaseSession(
        val auth: FirebaseAuth,
        val firestore: FirebaseFirestore
    )

    fun loadActive(context: Context): ObjectiveDeliveryState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val typeId = prefs.getString(KEY_ACTIVE_TYPE, null) ?: return null
        return load(context, ObjectiveCompanyType.fromId(typeId))
    }

    fun load(context: Context, type: ObjectiveCompanyType): ObjectiveDeliveryState? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CAMPAIGN_PREFIX + type.id, null)
            ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    fun setActive(context: Context, type: ObjectiveCompanyType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_TYPE, type.id)
            .apply()
    }

    fun save(context: Context, state: ObjectiveDeliveryState) {
        saveLocal(context, state, ObjectiveSyncStatus.PENDING)
        ObjectiveDeliveryGameSyncWorker.enqueue(
            context.applicationContext,
            state.companyType
        )
    }

    fun saveDeviceOnly(context: Context, state: ObjectiveDeliveryState) {
        saveLocal(context, state, ObjectiveSyncStatus.DEVICE)
    }

    fun syncStatus(
        context: Context,
        type: ObjectiveCompanyType
    ): ObjectiveSyncStatus {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(
                KEY_SYNC_STATUS_PREFIX + type.id,
                ObjectiveSyncStatus.DEVICE.name
            )
        return runCatching { ObjectiveSyncStatus.valueOf(raw ?: "") }
            .getOrDefault(ObjectiveSyncStatus.DEVICE)
    }

    fun restoreLatestFromCloud(context: Context, onComplete: (ObjectiveDeliveryState?) -> Unit) {
        ensureFirebaseUser(
            context = context.applicationContext,
            onReady = { uid, firestore ->
                firestore.collection("users")
                    .document(uid)
                    .collection("objective_delivery")
                    .orderBy("updatedAt", Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .addOnSuccessListener { result ->
                        val payload = result.documents.firstOrNull()?.getString("payload")
                        val state = payload?.let {
                            runCatching { decode(JSONObject(it)) }.getOrNull()
                        }
                        if (state != null) {
                            saveLocal(context, state, ObjectiveSyncStatus.ONLINE)
                        }
                        onComplete(state)
                    }
                    .addOnFailureListener { onComplete(null) }
            },
            onUnavailable = { onComplete(null) }
        )
    }

    private fun saveLocal(
        context: Context,
        state: ObjectiveDeliveryState,
        status: ObjectiveSyncStatus
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_TYPE, state.companyType.id)
            .putString(CAMPAIGN_PREFIX + state.companyType.id, encode(state).toString())
            .putString(KEY_SYNC_STATUS_PREFIX + state.companyType.id, status.name)
            .apply()
    }

    internal fun syncPending(
        context: Context,
        type: ObjectiveCompanyType,
        onComplete: (Boolean) -> Unit
    ) {
        val state = load(context, type)
        if (state == null) {
            onComplete(true)
            return
        }

        ensureFirebaseUser(
            context = context,
            onReady = { uid, firestore ->
                val ref = firestore.collection("users")
                    .document(uid)
                    .collection("objective_delivery")
                    .document(state.campaignId)

                firestore.runTransaction { tx ->
                    val remote = tx.get(ref)
                    val remoteRevision = remote.getLong("revision") ?: 0L
                    check(remoteRevision <= state.revision.toLong()) {
                        "Une sauvegarde cloud plus récente existe."
                    }
                    tx.set(
                        ref,
                        mapOf(
                            "schemaVersion" to state.schemaVersion,
                            "campaignId" to state.campaignId,
                            "companyType" to state.companyType.id,
                            "revision" to state.revision,
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "payload" to encode(state).toString()
                        )
                    )
                }.addOnSuccessListener {
                    setSyncStatus(context, type, ObjectiveSyncStatus.ONLINE)
                    onComplete(true)
                }.addOnFailureListener {
                    setSyncStatus(context, type, ObjectiveSyncStatus.PENDING)
                    onComplete(false)
                }
            },
            onUnavailable = {
                setSyncStatus(context, type, ObjectiveSyncStatus.PENDING)
                onComplete(false)
            }
        )
    }

    private fun setSyncStatus(
        context: Context,
        type: ObjectiveCompanyType,
        status: ObjectiveSyncStatus
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SYNC_STATUS_PREFIX + type.id, status.name)
            .apply()
    }

    /**
     * Un compte Google AGKGMG déjà connecté est réutilisé.
     * Sinon le jeu emploie une FirebaseApp secondaire afin que son compte anonyme
     * ne devienne jamais le currentUser global de l'application.
     */
    private fun cloudSession(context: Context): FirebaseSession? {
        val defaultAuth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
            ?: return null
        val defaultUser = defaultAuth.currentUser
        if (defaultUser != null && !defaultUser.isAnonymous) {
            return FirebaseSession(defaultAuth, FirebaseFirestore.getInstance())
        }

        val gameApp = runCatching {
            FirebaseApp.getApps(context)
                .firstOrNull { it.name == GAME_FIREBASE_APP }
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseApp.getInstance().options,
                    GAME_FIREBASE_APP
                )
        }.getOrNull() ?: return null

        configureGameAppCheck(gameApp)
        return FirebaseSession(
            auth = FirebaseAuth.getInstance(gameApp),
            firestore = FirebaseFirestore.getInstance(gameApp)
        )
    }

    private fun configureGameAppCheck(app: FirebaseApp) {
        runCatching {
            val appCheck = FirebaseAppCheck.getInstance(app)
            appCheck.installAppCheckProviderFactory(gameAppCheckProviderFactory())
            appCheck.setTokenAutoRefreshEnabled(true)
        }
    }

    private fun gameAppCheckProviderFactory(): AppCheckProviderFactory {
        if (!BuildConfig.DEBUG) {
            return PlayIntegrityAppCheckProviderFactory.getInstance()
        }

        val providerClass = Class.forName(
            "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
        )
        val instance = providerClass.getMethod("getInstance").invoke(null)
        return instance as? AppCheckProviderFactory
            ?: error("Fournisseur Firebase App Check debug invalide")
    }

    private fun ensureFirebaseUser(
        context: Context,
        onReady: (String, FirebaseFirestore) -> Unit,
        onUnavailable: () -> Unit
    ) {
        val session = cloudSession(context) ?: return onUnavailable()
        session.auth.currentUser?.uid?.let { uid ->
            onReady(uid, session.firestore)
            return
        }

        session.auth.signInAnonymously()
            .addOnSuccessListener { result ->
                result.user?.uid?.let { uid ->
                    onReady(uid, session.firestore)
                } ?: onUnavailable()
            }
            .addOnFailureListener { onUnavailable() }
    }

    internal fun encode(state: ObjectiveDeliveryState): JSONObject = JSONObject().apply {
        put("schemaVersion", state.schemaVersion)
        put("campaignId", state.campaignId)
        put("companyType", state.companyType.id)
        put("seed", state.seed)
        put("currentChapter", state.currentChapter)
        put("unlockedChapter", state.unlockedChapter)
        put("step", state.step)
        put("clientTrust", state.clientTrust)
        put("needCompleteness", state.needCompleteness)
        put("quotedPrice", state.quotedPrice)
        put("quotedDelayDays", state.quotedDelayDays)
        put("marginAmount", state.marginAmount)
        put("outcome", state.outcome.name)
        put("chapterMetricA", state.chapterMetricA)
        put("chapterMetricB", state.chapterMetricB)
        put("chapterChoice", state.chapterChoice)
        put("business", encodeBusiness(state.business))
        put("revision", state.revision)
        put("history", JSONArray(state.history))
    }

    internal fun decode(json: JSONObject): ObjectiveDeliveryState {
        val historyJson = json.optJSONArray("history") ?: JSONArray()
        val history = buildList {
            for (index in 0 until historyJson.length()) {
                historyJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        val companyType = ObjectiveCompanyType.fromId(json.getString("companyType"))
        val quotedPrice = json.optInt("quotedPrice", 0)
        val quotedDelayDays = json.optInt("quotedDelayDays", 0)
        val marginAmount = json.optInt("marginAmount", 0)
        val business = json.optJSONObject("business")
            ?.let(::decodeBusiness)
            ?: ObjectiveBusinessScenarioFactory.initial(companyType).copy(
                acceptedOrderPrice = quotedPrice,
                acceptedOrderDelayDays = quotedDelayDays,
                acceptedOrderMargin = marginAmount
            )

        return ObjectiveDeliveryState(
            schemaVersion = ObjectiveDeliveryGameEngine.SCHEMA_VERSION,
            campaignId = json.getString("campaignId"),
            companyType = companyType,
            seed = json.optLong("seed", 0L),
            currentChapter = json.optInt("currentChapter", 1),
            unlockedChapter = json.optInt("unlockedChapter", 1),
            step = json.optInt("step", 0),
            clientTrust = json.optInt("clientTrust", 50).coerceIn(0, 100),
            needCompleteness = json.optInt("needCompleteness", 0).coerceIn(0, 100),
            quotedPrice = quotedPrice,
            quotedDelayDays = quotedDelayDays,
            marginAmount = marginAmount,
            outcome = runCatching {
                ObjectiveOutcome.valueOf(
                    json.optString("outcome", ObjectiveOutcome.IN_PROGRESS.name)
                )
            }.getOrDefault(ObjectiveOutcome.IN_PROGRESS),
            chapterMetricA = json.optInt("chapterMetricA", 0),
            chapterMetricB = json.optInt("chapterMetricB", 0),
            chapterChoice = json.optString("chapterChoice", ""),
            business = business,
            revision = json.optInt("revision", 1).coerceAtLeast(1),
            history = history
        )
    }

    private fun encodeBusiness(business: ObjectiveBusinessState): JSONObject =
        JSONObject().apply {
            put("cash", business.cash)
            put("acceptedOrderPrice", business.acceptedOrderPrice)
            put("acceptedOrderDelayDays", business.acceptedOrderDelayDays)
            put("acceptedOrderMargin", business.acceptedOrderMargin)
            put("handoffQuality", business.handoffQuality)
            put("teamMorale", business.teamMorale)
            put("simulatedLegalFloorIndex", business.simulatedLegalFloorIndex)
            put("payGridIndex", business.payGridIndex)
            put("annualRaiseEnvelopeBasisPoints", business.annualRaiseEnvelopeBasisPoints)
            put("annualRaiseUsedBasisPoints", business.annualRaiseUsedBasisPoints)
            put("supplierReliability", business.supplierReliability)
            put("supplierLeadTimeDays", business.supplierLeadTimeDays)

            put(
                "employees",
                JSONArray().apply {
                    business.employees.forEach { employee ->
                        put(
                            JSONObject().apply {
                                put("id", employee.id)
                                put("firstName", employee.firstName)
                                put("role", employee.role)
                                put("contractType", employee.contractType.name)
                                put("classification", employee.classification.name)
                                put("weeklyHours", employee.weeklyHours)
                                put("leaveBalanceDays", employee.leaveBalanceDays)
                                put("skillLabel", employee.skillLabel)
                                put("skillLevel", employee.skillLevel)
                                put("seniorityMonths", employee.seniorityMonths)
                                put("payIndex", employee.payIndex)
                                put("morale", employee.morale)
                            }
                        )
                    }
                }
            )

            put(
                "stock",
                JSONArray().apply {
                    business.stock.forEach { item ->
                        put(
                            JSONObject().apply {
                                put("id", item.id)
                                put("label", item.label)
                                put("available", item.available)
                                put("reserved", item.reserved)
                                put("inbound", item.inbound)
                                put("requiredForOrder", item.requiredForOrder)
                                put("unitCost", item.unitCost)
                            }
                        )
                    }
                }
            )
        }

    private fun decodeBusiness(json: JSONObject): ObjectiveBusinessState {
        val employeesJson = json.optJSONArray("employees") ?: JSONArray()
        val employees = buildList {
            for (index in 0 until employeesJson.length()) {
                val item = employeesJson.optJSONObject(index) ?: continue
                add(
                    ObjectiveEmployee(
                        id = item.optString("id", "employee_$index"),
                        firstName = item.optString("firstName", "Employé"),
                        role = item.optString("role", "Équipe"),
                        contractType = runCatching {
                            ObjectiveContractType.valueOf(
                                item.optString("contractType", ObjectiveContractType.CDI.name)
                            )
                        }.getOrDefault(ObjectiveContractType.CDI),
                        classification = runCatching {
                            ObjectiveClassification.valueOf(
                                item.optString(
                                    "classification",
                                    ObjectiveClassification.NON_CADRE.name
                                )
                            )
                        }.getOrDefault(ObjectiveClassification.NON_CADRE),
                        weeklyHours = item.optInt("weeklyHours", 35).coerceAtLeast(1),
                        leaveBalanceDays = item.optInt("leaveBalanceDays", 0).coerceAtLeast(0),
                        skillLabel = item.optString("skillLabel", "Polyvalence"),
                        skillLevel = item.optInt("skillLevel", 50).coerceIn(0, 100),
                        seniorityMonths = item.optInt("seniorityMonths", 0).coerceAtLeast(0),
                        payIndex = item.optInt("payIndex", 100).coerceAtLeast(1),
                        morale = item.optInt("morale", 70).coerceIn(0, 100)
                    )
                )
            }
        }

        val stockJson = json.optJSONArray("stock") ?: JSONArray()
        val stock = buildList {
            for (index in 0 until stockJson.length()) {
                val item = stockJson.optJSONObject(index) ?: continue
                add(
                    ObjectiveStockItem(
                        id = item.optString("id", "stock_$index"),
                        label = item.optString("label", "Matière"),
                        available = item.optInt("available", 0).coerceAtLeast(0),
                        reserved = item.optInt("reserved", 0).coerceAtLeast(0),
                        inbound = item.optInt("inbound", 0).coerceAtLeast(0),
                        requiredForOrder = item.optInt("requiredForOrder", 0).coerceAtLeast(0),
                        unitCost = item.optInt("unitCost", 0).coerceAtLeast(0)
                    )
                )
            }
        }

        val floor = json.optInt("simulatedLegalFloorIndex", 100).coerceAtLeast(1)
        return ObjectiveBusinessState(
            cash = json.optInt("cash", 0).coerceAtLeast(0),
            acceptedOrderPrice = json.optInt("acceptedOrderPrice", 0).coerceAtLeast(0),
            acceptedOrderDelayDays = json.optInt("acceptedOrderDelayDays", 0).coerceAtLeast(0),
            acceptedOrderMargin = json.optInt("acceptedOrderMargin", 0),
            handoffQuality = json.optInt("handoffQuality", 0).coerceIn(0, 100),
            teamMorale = json.optInt("teamMorale", 70).coerceIn(0, 100),
            simulatedLegalFloorIndex = floor,
            payGridIndex = json.optInt("payGridIndex", floor).coerceAtLeast(floor),
            annualRaiseEnvelopeBasisPoints = json.optInt(
                "annualRaiseEnvelopeBasisPoints",
                500
            ).coerceAtLeast(0),
            annualRaiseUsedBasisPoints = json.optInt(
                "annualRaiseUsedBasisPoints",
                0
            ).coerceAtLeast(0),
            employees = employees,
            stock = stock,
            supplierReliability = json.optInt("supplierReliability", 75)
                .coerceIn(0, 100),
            supplierLeadTimeDays = json.optInt("supplierLeadTimeDays", 8)
                .coerceAtLeast(0)
        )
    }
}
