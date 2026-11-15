package com.amaury.pointage

import com.amaury.pointage.v2.SalaryAccountScope

/** One device-authentication result, valid only in its originating account session. */
object PendingSalaryCompanyAccess {
    private data class Access(val companyId: String?, val lease: SalaryAccountScope.Lease)
    private var access: Access? = null

    fun authorize(companyId: String, lease: SalaryAccountScope.Lease) {
        access = if (companyId.isNotBlank() && SalaryAccountScope.isCurrent(lease)) Access(companyId, lease) else null
    }

    fun hasCurrentAccess(): Boolean = access?.let { SalaryAccountScope.isCurrent(it.lease) } == true

    fun authorizeRecovery(lease: SalaryAccountScope.Lease) {
        access = if (SalaryAccountScope.isCurrent(lease)) Access(null, lease) else null
    }

    fun consumeRecovery(): Boolean {
        val pending = access ?: return false
        if (pending.companyId != null) return false
        access = null
        return SalaryAccountScope.isCurrent(pending.lease)
    }

    fun consume(): String? {
        val pending = access
        access = null
        return pending?.takeIf { SalaryAccountScope.isCurrent(it.lease) }?.companyId
    }

    fun clear() { access = null }
}
