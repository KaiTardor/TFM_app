package com.example.besharp.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "evidence_signals",
    primaryKeys = ["signalId"],
    foreignKeys = [
        ForeignKey(
            entity = AuthenticityResultEntity::class,
            parentColumns = ["jobId"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["type"]),
        Index(value = ["outcome"])
    ]
)
data class EvidenceSignalEntity(
    val signalId: String,
    val jobId: Long,
    val type: String,
    val outcome: String,
    val verificationLevel: String,
    val source: String,
    val claim: String,
    val issuer: String? = null,
    val model: String? = null,
    val generatedAt: Long? = null,
    val explanation: String? = null,
    val validationState: String? = null
)
