package com.privatechat.app.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** User-visible notes category. Deleting a category never deletes notes. */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false
)
