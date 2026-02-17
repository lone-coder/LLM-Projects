package com.yourcompany.anxietymonitor.domain.interfaces

import com.yourcompany.anxietymonitor.domain.models.CognitiveDistortion

interface CognitiveAnalyzer {
    suspend fun initialize(): Boolean
    suspend fun analyzeThought(thought: String): CognitiveDistortion?
    fun isAvailable(): Boolean
}