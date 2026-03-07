package com.garbagesys.data.models

import kotlinx.serialization.Serializable

@Serializable
data class AirdropOpportunity(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val network: String = "polygon",
    val status: AirdropStatus = AirdropStatus.PENDING,
    val discoveredAt: Long = System.currentTimeMillis(),
    val submittedAt: Long? = null,
    val requiresAction: String? = null
)

enum class AirdropStatus {
    PENDING, SUBMITTED, REQUIRES_ACTION, FAILED, EXPIRED
}
