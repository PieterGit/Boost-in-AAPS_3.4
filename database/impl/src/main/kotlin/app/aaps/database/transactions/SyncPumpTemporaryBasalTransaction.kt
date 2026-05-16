package app.aaps.database.transactions

import android.util.Log
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.interfaces.end
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TBR_SYNC_DEBUG_TAG = "TBR_SYNC_DEBUG"
private const val TBR_SYNC_NEAR_OVERLAP_WINDOW_MS = 1_000L

/**
 * Creates or updates the Temporary basal from pump synchronization
 */
class SyncPumpTemporaryBasalTransaction(
    private val temporaryBasal: TemporaryBasal,
    private val type: TemporaryBasal.Type? // extra parameter because field is not nullable in TemporaryBasal.class
) : Transaction<SyncPumpTemporaryBasalTransaction.TransactionResult>() {

    override fun run(): TransactionResult {
        temporaryBasal.interfaceIDs.pumpId ?: temporaryBasal.interfaceIDs.pumpType
        ?: temporaryBasal.interfaceIDs.pumpSerial
        ?: throw IllegalStateException("Some pump ID is null")

        val result = TransactionResult()
        val existing = database.temporaryBasalDao.findByPumpIds(temporaryBasal.interfaceIDs.pumpId!!, temporaryBasal.interfaceIDs.pumpType!!, temporaryBasal.interfaceIDs.pumpSerial!!)
        if (existing != null) {
            val resolvedType = type ?: existing.type
            if (
                existing.timestamp != temporaryBasal.timestamp ||
                existing.rate != temporaryBasal.rate ||
                existing.duration != temporaryBasal.duration && existing.interfaceIDs.endId == null ||
                existing.type != resolvedType
            ) {
                logExistingPumpIdMatchUpdate(existing, temporaryBasal, resolvedType)

                val old = existing.copy()
                existing.timestamp = temporaryBasal.timestamp
                existing.rate = temporaryBasal.rate
                existing.duration = temporaryBasal.duration
                existing.type = resolvedType
                database.temporaryBasalDao.updateExistingEntry(existing)
                result.updated.add(Pair(old, existing))
            }
        } else {
            val running = database.temporaryBasalDao.getTemporaryBasalActiveAtLegacy(temporaryBasal.timestamp)
            if (running != null) {
                logRunningTbrClosureCandidate(running, temporaryBasal, type ?: temporaryBasal.type)

                val old = running.copy()
                running.end = temporaryBasal.timestamp
                running.interfaceIDs.endId = temporaryBasal.interfaceIDs.pumpId
                database.temporaryBasalDao.updateExistingEntry(running)
                result.updated.add(Pair(old, running))
            }
            database.temporaryBasalDao.insertNewEntry(temporaryBasal)
            result.inserted.add(temporaryBasal)
        }
        return result
    }

    class TransactionResult {

        val inserted = mutableListOf<TemporaryBasal>()
        val updated = mutableListOf<Pair<TemporaryBasal, TemporaryBasal>>()
    }
}

private fun logRunningTbrClosureCandidate(
    running: TemporaryBasal,
    incoming: TemporaryBasal,
    incomingType: TemporaryBasal.Type
) {
    val calculatedDuration = incoming.timestamp - running.timestamp
    val shouldLog = calculatedDuration <= 0L || calculatedDuration <= TBR_SYNC_NEAR_OVERLAP_WINDOW_MS

    if (!shouldLog) return

    val severity = if (calculatedDuration <= 0L) "INVALID" else "NEAR_OVERLAP"
    val classification = when {
        running.timestamp == incoming.timestamp -> "SAME_START_ZERO_DURATION"
        incoming.timestamp < running.timestamp -> "DAO_CONTRACT_VIOLATION_NEGATIVE_DURATION"
        else -> "VERY_SHORT_TRUNCATION"
    }
    val runningEndBefore = running.timestamp + running.duration
    val incomingEnd = incoming.timestamp + incoming.duration

    Log.e(
        TBR_SYNC_DEBUG_TAG,
        buildString {
            appendLine("$severity TemporaryBasal overlap before assigning running.end")
            appendLine("Logging-only diagnostic: the original running.end assignment still executes after this log line.")
            appendLine()
            appendLine("decision:")
            appendLine("  path=existingByPumpIds:null -> getTemporaryBasalActiveAtLegacy(incoming.timestamp)")
            appendLine("  activeQueryTimestamp=${incoming.timestamp}")
            appendLine("  activeQueryTimestampUtc=${incoming.timestamp.toUtcDebugString()}")
            appendLine("  attemptedEnd=${incoming.timestamp}")
            appendLine("  attemptedEndUtc=${incoming.timestamp.toUtcDebugString()}")
            appendLine("  runningStart=${running.timestamp}")
            appendLine("  runningStartUtc=${running.timestamp.toUtcDebugString()}")
            appendLine("  runningEndBefore=$runningEndBefore")
            appendLine("  runningEndBeforeUtc=${runningEndBefore.toUtcDebugString()}")
            appendLine("  incomingEnd=$incomingEnd")
            appendLine("  incomingEndUtc=${incomingEnd.toUtcDebugString()}")
            appendLine("  calculatedDuration=$calculatedDuration")
            appendLine("  classification=$classification")
            appendLine("  sameTimestamp=${running.timestamp == incoming.timestamp}")
            appendLine("  daoContractViolationIncomingBeforeRunning=${incoming.timestamp < running.timestamp}")
            appendLine("  runningContainsIncomingStart=${running.timestamp <= incoming.timestamp && runningEndBefore > incoming.timestamp}")
            appendLine("  incomingContainsRunningStart=${incoming.timestamp <= running.timestamp && incomingEnd > running.timestamp}")
            appendLine("  samePumpType=${running.interfaceIDs.pumpType == incoming.interfaceIDs.pumpType}")
            appendLine("  samePumpSerial=${running.interfaceIDs.pumpSerial == incoming.interfaceIDs.pumpSerial}")
            appendLine("  samePumpId=${running.interfaceIDs.pumpId == incoming.interfaceIDs.pumpId}")
            appendLine("  sameStartId=${running.interfaceIDs.startId == incoming.interfaceIDs.startId}")
            appendLine("  sameEndId=${running.interfaceIDs.endId == incoming.interfaceIDs.endId}")
            appendLine("  sameTemporaryId=${running.interfaceIDs.temporaryId == incoming.interfaceIDs.temporaryId}")
            appendLine("  sameRate=${running.rate == incoming.rate}")
            appendLine("  sameType=${running.type == incomingType}")
            appendLine("  runningEndIdEqualsIncomingPumpId=${running.interfaceIDs.endId == incoming.interfaceIDs.pumpId}")
            appendLine()
            appendTemporaryBasal("incoming", incoming, incomingType)
            appendLine()
            appendTemporaryBasal("running", running, running.type)
        }
    )
}

private fun logExistingPumpIdMatchUpdate(
    existing: TemporaryBasal,
    incoming: TemporaryBasal,
    resolvedType: TemporaryBasal.Type
) {
    val timestampDelta = incoming.timestamp - existing.timestamp
    val durationDelta = incoming.duration - existing.duration
    val endDelta = (incoming.timestamp + incoming.duration) - (existing.timestamp + existing.duration)
    val shouldLog = timestampDelta != 0L || durationDelta != 0L || endDelta != 0L

    if (!shouldLog) return

    Log.w(
        TBR_SYNC_DEBUG_TAG,
        buildString {
            appendLine("TemporaryBasal existing pumpId match is being updated")
            appendLine("This may help identify Insight history timestamp drift across repeated reads.")
            appendLine()
            appendLine("deltas:")
            appendLine("  timestampDelta=$timestampDelta")
            appendLine("  durationDelta=$durationDelta")
            appendLine("  endDelta=$endDelta")
            appendLine("  existingEndBefore=${existing.timestamp + existing.duration}")
            appendLine("  incomingEnd=${incoming.timestamp + incoming.duration}")
            appendLine("  resolvedType=$resolvedType")
            appendLine()
            appendTemporaryBasal("incoming", incoming, resolvedType)
            appendLine()
            appendTemporaryBasal("existing", existing, existing.type)
        }
    )
}

private fun StringBuilder.appendTemporaryBasal(
    label: String,
    basal: TemporaryBasal,
    resolvedType: TemporaryBasal.Type
) {
    val basalEnd = basal.timestamp + basal.duration

    appendLine("$label:")
    appendLine("  db:")
    appendLine("    id=${basal.id}")
    appendLine("    version=${basal.version}")
    appendLine("    dateCreated=${basal.dateCreated}")
    appendLine("    dateCreatedUtc=${basal.dateCreated.toUtcDebugString()}")
    appendLine("    isValid=${basal.isValid}")
    appendLine("    referenceId=${basal.referenceId}")
    appendLine("  time:")
    appendLine("    timestamp=${basal.timestamp}")
    appendLine("    timestampUtc=${basal.timestamp.toUtcDebugString()}")
    appendLine("    utcOffset=${basal.utcOffset}")
    appendLine("    duration=${basal.duration}")
    appendLine("    end=$basalEnd")
    appendLine("    endUtc=${basalEnd.toUtcDebugString()}")
    appendLine("  basal:")
    appendLine("    type=${basal.type}")
    appendLine("    resolvedType=$resolvedType")
    appendLine("    isAbsolute=${basal.isAbsolute}")
    appendLine("    rate=${basal.rate}")
    appendLine("  ids:")
    appendLine("    pumpType=${basal.interfaceIDs.pumpType}")
    appendLine("    pumpId=${basal.interfaceIDs.pumpId}")
    appendLine("    startId=${basal.interfaceIDs.startId}")
    appendLine("    endId=${basal.interfaceIDs.endId}")
    appendLine("    temporaryId=${basal.interfaceIDs.temporaryId}")
    appendLine("    nightscoutId=${basal.interfaceIDs.nightscoutId}")
    appendLine("    nightscoutSystemId=${basal.interfaceIDs.nightscoutSystemId}")
}

private fun Long.toUtcDebugString(): String = try {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    formatter.format(Date(this))
} catch (_: Exception) {
    "unavailable"
}
