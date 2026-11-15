package com.pause.app.data.local

import androidx.room.TypeConverter
import com.pause.app.domain.model.FrictionMode
import com.pause.app.domain.model.InterventionOutcome

/**
 * Enums are stored by name. Decoding is defensive so an older/newer value can
 * never crash a query after an upgrade.
 */
class Converters {

    @TypeConverter
    fun fromFrictionMode(value: FrictionMode): String = value.name

    @TypeConverter
    fun toFrictionMode(value: String): FrictionMode =
        runCatching { FrictionMode.valueOf(value) }.getOrDefault(FrictionMode.BREATHING)

    @TypeConverter
    fun fromInterventionOutcome(value: InterventionOutcome): String = value.name

    @TypeConverter
    fun toInterventionOutcome(value: String): InterventionOutcome =
        runCatching { InterventionOutcome.valueOf(value) }.getOrDefault(InterventionOutcome.PENDING)
}
