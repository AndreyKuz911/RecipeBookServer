package com.recipebook.server.features.news

import kotlinx.serialization.Serializable

@Serializable
data class NewsItemDto(
    val title: String,
    val summary: String,
    val url: String,
    val imageUrl: String? = null,
    val publishedAt: String,
    val source: String,
)
