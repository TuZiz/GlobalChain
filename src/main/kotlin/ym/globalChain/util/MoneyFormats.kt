package ym.globalchain.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object MoneyFormats {

    fun parseMinorUnits(input: String, scale: Int): Long? {
        return runCatching {
            BigDecimal(input.trim())
                .setScale(scale, RoundingMode.UNNECESSARY)
                .movePointRight(scale)
                .longValueExact()
        }.getOrNull()
    }

    fun formatMinorUnits(value: Long, scale: Int): String {
        val symbols = DecimalFormatSymbols(Locale.US)
        val pattern = if (scale <= 0) "#,##0" else "#,##0.${"0".repeat(scale)}"
        val decimalFormat = DecimalFormat(pattern, symbols)
        val decimal = BigDecimal.valueOf(value, scale)
        return decimalFormat.format(decimal)
    }
}
