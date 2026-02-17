package com.yourcompany.anxietymonitor.ai

import android.util.Log
import com.yourcompany.anxietymonitor.domain.interfaces.CognitiveAnalyzer
import com.yourcompany.anxietymonitor.domain.models.CognitiveDistortion
import org.tensorflow.lite.task.text.qa.BertQuestionAnswerer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

@Singleton
class AndroidCognitiveAnalyzer @Inject constructor(
    private val modelManager: ModelManager
) : CognitiveAnalyzer {

    private var gemmaQA: BertQuestionAnswerer? = null

    companion object {
        private const val TAG = "AndroidCognitiveAnalyzer"
    }

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing AndroidCognitiveAnalyzer...")

            // Check if model is downloaded
            if (!modelManager.isModelDownloaded()) {
                Log.w(TAG, "AI model not available, starting download...")
                val downloadResult = modelManager.downloadModel()
                if (downloadResult.isFailure) {
                    Log.e(TAG, "Failed to download model: ${downloadResult.exceptionOrNull()}")
                    return@withContext false
                }
            }

            val modelFile: File = modelManager.getModelFile()
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file does not exist: ${modelFile.absolutePath}")
                return@withContext false
            }

            // Load the .task file directly
            gemmaQA = BertQuestionAnswerer.createFromFile(modelFile)
            Log.d(TAG, "AndroidCognitiveAnalyzer initialized successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AndroidCognitiveAnalyzer", e)
            false
        }
    }

    override suspend fun analyzeThought(thought: String): CognitiveDistortion? = withContext(Dispatchers.IO) {
        val qa = gemmaQA ?: run {
            Log.e(TAG, "Gemma model not initialized")
            return@withContext null
        }

        try {
            val question = "What cognitive distortion is present in this thought? Answer with only a number 1-10."
            val contextText = buildDistortionContext(thought)

            // Use Gemma model for question answering
            val answers = qa.answer(contextText, question)
            val bestAnswer = answers.maxByOrNull { it.pos.logit }

            val result = parseGemmaAnswer(bestAnswer?.text ?: "")
            Log.d(TAG, "Analyzed thought, detected: $result")
            return@withContext result

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing thought", e)
            return@withContext null
        }
    }

    override fun isAvailable(): Boolean = gemmaQA != null

    private fun buildDistortionContext(thought: String): String {
        return """
        Cognitive Behavioral Therapy Analysis:
        
        The thought to analyze: "$thought"
        
        Cognitive Distortion Types:
        1. All-or-Nothing Thinking: Seeing things in black and white categories
        2. Overgeneralization: Drawing broad conclusions from single events
        3. Mental Filter: Focusing only on negative details, ignoring positives
        4. Disqualifying the Positive: Rejecting positive experiences
        5. Jumping to Conclusions: Making negative interpretations without evidence
        6. Magnification: Exaggerating the importance or severity of things
        7. Emotional Reasoning: Assuming feelings reflect reality
        8. Should Statements: Using "should," "must," creating unrealistic standards
        9. Labeling: Defining yourself/others entirely by single behaviors
        10. Personalization: Taking responsibility for things outside your control
        
        This thought demonstrates a pattern of cognitive distortion.
        """.trimIndent()
    }

    private fun parseGemmaAnswer(answer: String): CognitiveDistortion? {
        // Extract number from Gemma's response
        val distortionNumber = answer.trim().firstNotNullOfOrNull { char ->
            if (char.isDigit()) char.toString().toIntOrNull() else null
        }

        return when (distortionNumber) {
            1 -> CognitiveDistortion.ALL_OR_NOTHING
            2 -> CognitiveDistortion.OVERGENERALIZATION
            3 -> CognitiveDistortion.MENTAL_FILTER
            4 -> CognitiveDistortion.DISQUALIFYING_POSITIVE
            5 -> CognitiveDistortion.JUMPING_TO_CONCLUSIONS
            6 -> CognitiveDistortion.MAGNIFICATION
            7 -> CognitiveDistortion.EMOTIONAL_REASONING
            8 -> CognitiveDistortion.SHOULD_STATEMENTS
            9 -> CognitiveDistortion.LABELING
            10 -> CognitiveDistortion.PERSONALIZATION
            else -> {
                // Try to parse text response if number not found
                parseTextualResponse(answer)
            }
        }
    }

    private fun parseTextualResponse(answer: String): CognitiveDistortion? {
        val lowerAnswer = answer.lowercase()
        return when {
            lowerAnswer.contains("all-or-nothing") || lowerAnswer.contains("black and white") -> CognitiveDistortion.ALL_OR_NOTHING
            lowerAnswer.contains("overgeneralization") || lowerAnswer.contains("always") || lowerAnswer.contains("never") -> CognitiveDistortion.OVERGENERALIZATION
            lowerAnswer.contains("mental filter") || lowerAnswer.contains("focusing on negative") -> CognitiveDistortion.MENTAL_FILTER
            lowerAnswer.contains("disqualifying") || lowerAnswer.contains("positive") -> CognitiveDistortion.DISQUALIFYING_POSITIVE
            lowerAnswer.contains("jumping to conclusions") || lowerAnswer.contains("mind reading") -> CognitiveDistortion.JUMPING_TO_CONCLUSIONS
            lowerAnswer.contains("magnification") || lowerAnswer.contains("exaggerating") -> CognitiveDistortion.MAGNIFICATION
            lowerAnswer.contains("emotional reasoning") || lowerAnswer.contains("feelings reflect reality") -> CognitiveDistortion.EMOTIONAL_REASONING
            lowerAnswer.contains("should statements") || lowerAnswer.contains("should") || lowerAnswer.contains("must") -> CognitiveDistortion.SHOULD_STATEMENTS
            lowerAnswer.contains("labeling") || lowerAnswer.contains("defining") -> CognitiveDistortion.LABELING
            lowerAnswer.contains("personalization") || lowerAnswer.contains("taking responsibility") -> CognitiveDistortion.PERSONALIZATION
            else -> null
        }
    }}