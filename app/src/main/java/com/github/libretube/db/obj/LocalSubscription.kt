package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "localSubscription")
data class LocalSubscription(
    @PrimaryKey val channelId: String = ""
)
