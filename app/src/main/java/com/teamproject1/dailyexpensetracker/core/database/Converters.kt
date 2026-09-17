package com.teamproject1.dailyexpensetracker.core.database

import androidx.room.TypeConverter
import com.teamproject1.dailyexpensetracker.core.database.entity.AccountType
import com.teamproject1.dailyexpensetracker.core.database.entity.AutoRenewMode
import com.teamproject1.dailyexpensetracker.core.database.entity.BookType
import com.teamproject1.dailyexpensetracker.core.database.entity.CompoundingFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.EpfTileType
import com.teamproject1.dailyexpensetracker.core.database.entity.ExchangeCode
import com.teamproject1.dailyexpensetracker.core.database.entity.InterestType
import com.teamproject1.dailyexpensetracker.core.database.entity.PpfEntryType
import com.teamproject1.dailyexpensetracker.core.database.entity.RecurrenceFrequency
import com.teamproject1.dailyexpensetracker.core.database.entity.RateInstrument
import com.teamproject1.dailyexpensetracker.core.database.entity.TransactionType
import com.teamproject1.dailyexpensetracker.core.database.entity.WealthInstrumentKind

class Converters {
    @TypeConverter
    fun toAccountType(value: String) = enumValueOf<AccountType>(value)
    @TypeConverter
    fun fromAccountType(value: AccountType) = value.name

    @TypeConverter
    fun toBookType(value: String) = enumValueOf<BookType>(value)
    @TypeConverter
    fun fromBookType(value: BookType) = value.name

    @TypeConverter
    fun toTransactionType(value: String) = enumValueOf<TransactionType>(value)
    @TypeConverter
    fun fromTransactionType(value: TransactionType) = value.name

    @TypeConverter
    fun toRecurrenceFrequency(value: String) = enumValueOf<RecurrenceFrequency>(value)
    @TypeConverter
    fun fromRecurrenceFrequency(value: RecurrenceFrequency) = value.name

    @TypeConverter
    fun toInterestType(value: String) = enumValueOf<InterestType>(value)
    @TypeConverter
    fun fromInterestType(value: InterestType) = value.name

    @TypeConverter
    fun toCompoundingFrequency(value: String) = enumValueOf<CompoundingFrequency>(value)
    @TypeConverter
    fun fromCompoundingFrequency(value: CompoundingFrequency) = value.name

    @TypeConverter
    fun toExchangeCode(value: String) = enumValueOf<ExchangeCode>(value)
    @TypeConverter
    fun fromExchangeCode(value: ExchangeCode) = value.name

    @TypeConverter
    fun toWealthInstrumentKind(value: String) = enumValueOf<WealthInstrumentKind>(value)
    @TypeConverter
    fun fromWealthInstrumentKind(value: WealthInstrumentKind) = value.name

    @TypeConverter
    fun toRateInstrument(value: String) = enumValueOf<RateInstrument>(value)
    @TypeConverter
    fun fromRateInstrument(value: RateInstrument) = value.name

    @TypeConverter
    fun toAutoRenewMode(value: String) = enumValueOf<AutoRenewMode>(value)
    @TypeConverter
    fun fromAutoRenewMode(value: AutoRenewMode) = value.name

    @TypeConverter
    fun toPpfEntryType(value: String) = enumValueOf<PpfEntryType>(value)
    @TypeConverter
    fun fromPpfEntryType(value: PpfEntryType) = value.name

    @TypeConverter
    fun toEpfTileType(value: String) = enumValueOf<EpfTileType>(value)
    @TypeConverter
    fun fromEpfTileType(value: EpfTileType) = value.name
}
