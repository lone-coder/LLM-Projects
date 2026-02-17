package com.yourcompany.anxietymonitor.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yourcompany.anxietymonitor.R
import com.yourcompany.anxietymonitor.domain.interfaces.CognitiveAnalyzer
import com.yourcompany.anxietymonitor.data.repository.DataRepository
import com.yourcompany.anxietymonitor.domain.engine.AnxietyDetectionEngine
import com.yourcompany.anxietymonitor.domain.models.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CbtPromptActivity : AppCompatActivity() {

    @Inject
    lateinit var cognitiveAnalyzer: CognitiveAnalyzer
    @Inject
    lateinit var repository: DataRepository
    @Inject
    lateinit var anxietyDetectionEngine: AnxietyDetectionEngine

    private var anxietyEventId: String? = null

    // Declare views as class properties to avoid repeated findViewById calls
    private lateinit var promptText: TextView
    private lateinit var thoughtInput: EditText
    private lateinit var submitButton: Button
    private lateinit var skipButton: Button
    private lateinit var resultCard: CardView
    private lateinit var resultText: TextView
    private lateinit var saveButton: Button
    private lateinit var confirmationCard: CardView
    private lateinit var confirmationText: TextView
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cbt_prompt)

        anxietyEventId = intent.getStringExtra("anxiety_event_id")

        initializeViews()
        setupListeners()

        if (anxietyEventId != null) {
            // Show feedback dialog if this was triggered by an anxiety detection
            showFeedbackDialog()
        } else {
            // Show manual anxiety reporting option if user opened CBT manually
            showManualAnxietyOption()
        }
    }

    // Option for users who manually opened CBT to report missed anxiety
    private fun showManualAnxietyOption() {
        AlertDialog.Builder(this)
            .setTitle("Anxiety Journal")
            .setMessage("Are you feeling anxious right now?")
            .setPositiveButton("Yes, report anxiety") { _, _ ->
                reportManualAnxiety()
            }
            .setNegativeButton("No, just journal") { _, _ ->
                // Continue to normal CBT analysis
            }
            .show()
    }

    // Report manually detected anxiety (false negative)
    private fun reportManualAnxiety() {
        lifecycleScope.launch {
            try {
                // Report to ML system for learning
                anxietyDetectionEngine.reportManualAnxietyEvent(
                    timestamp = System.currentTimeMillis(),
                    userNotes = null
                )

                // Show confirmation and continue to CBT analysis
                confirmationText.text = "Anxiety reported. This helps improve detection accuracy."
                confirmationCard.visibility = View.VISIBLE

                rootView.postDelayed({
                    confirmationCard.visibility = View.GONE
                    // Continue to normal CBT flow
                }, 2000)

            } catch (e: Exception) {
                showError("Failed to report anxiety. Please try again.")
            }
        }
    }

    private fun initializeViews() {
        promptText = findViewById(R.id.promptText)
        thoughtInput = findViewById(R.id.thoughtInput)
        submitButton = findViewById(R.id.submitButton)
        skipButton = findViewById(R.id.skipButton)
        resultCard = findViewById(R.id.resultCard)
        resultText = findViewById(R.id.resultText)
        saveButton = findViewById(R.id.saveButton)
        confirmationCard = findViewById(R.id.confirmationCard)
        confirmationText = findViewById(R.id.confirmationText)
        rootView = findViewById(R.id.rootView)

        promptText.text = getString(R.string.negative_thoughts_prompt)
    }

    private fun setupListeners() {
        submitButton.setOnClickListener {
            val thought = thoughtInput.text.toString().trim()
            if (thought.isNotEmpty()) {
                analyzeThought(thought)
            }
        }

        skipButton.setOnClickListener {
            finish()
        }
    }

    // Simple feedback dialog instead of complex UI
    private fun showFeedbackDialog() {
        AlertDialog.Builder(this)
            .setTitle("Anxiety Detection Feedback")
            .setMessage("Was the anxiety detection accurate?")
            .setPositiveButton("Yes, I was anxious") { _, _ ->
                submitFeedback(wasCorrect = true)
            }
            .setNegativeButton("No, I wasn't anxious") { _, _ ->
                submitFeedback(wasCorrect = false)
            }
            .setNeutralButton("Skip") { _, _ ->
                // Continue to CBT analysis
            }
            .show()
    }

    private fun submitFeedback(wasCorrect: Boolean) {
        lifecycleScope.launch {
            try {
                // Train the ML model with this feedback
                if (anxietyEventId != null) {
                    anxietyDetectionEngine.updateWithFeedback(
                        anxietyEventId = anxietyEventId!!,
                        wasCorrect = wasCorrect,
                        actualAnxietyLevel = null,
                        userNotes = null
                    )
                }
            } catch (e: Exception) {
                // Silent failure - don't interrupt user experience
            }
        }
    }

    private fun analyzeThought(thought: String) {
        lifecycleScope.launch {
            try {
                submitButton.isEnabled = false
                submitButton.text = getString(R.string.analyzing)

                val distortion = cognitiveAnalyzer.analyzeThought(thought)

                if (distortion != null) {
                    showDistortionResult(distortion)
                } else {
                    showError(getString(R.string.unable_to_analyze))
                }

            } catch (e: Exception) {
                showError(getString(R.string.analysis_failed))
            } finally {
                submitButton.isEnabled = true
                submitButton.text = getString(R.string.analyze_thought)
            }
        }
    }

    private fun showDistortionResult(distortion: CognitiveDistortion) {
        val displayName = getDistortionDisplayName(distortion)
        val explanation = getDistortionExplanation(distortion)

        resultText.text = getString(R.string.distortion_result, displayName.lowercase(), explanation)
        resultCard.visibility = View.VISIBLE

        saveButton.visibility = View.VISIBLE
        saveButton.setOnClickListener {
            val thought = thoughtInput.text.toString().trim()
            authenticateAndSave(thought, distortion)
        }

        submitButton.visibility = View.GONE
        skipButton.visibility = View.GONE
    }

    private fun showError(message: String) {
        resultText.text = message
        resultCard.visibility = View.VISIBLE
    }

    private fun authenticateAndSave(thought: String, distortion: CognitiveDistortion) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auth_title))
            .setSubtitle(getString(R.string.auth_subtitle))
            .setNegativeButtonText(getString(R.string.auth_cancel))
            .build()

        val biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    saveToJournal(thought, distortion)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showError(getString(R.string.auth_failed))
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }

    private fun saveToJournal(thought: String, distortion: CognitiveDistortion) {
        lifecycleScope.launch {
            try {
                val feedback = UserFeedback(
                    timestamp = System.currentTimeMillis(),
                    anxietyEventId = anxietyEventId ?: "",
                    wasCorrect = true, // Assume correct since user engaged with CBT
                    userNotes = thought,
                    actualAnxietyLevel = null,
                    contextNotes = "Cognitive distortion: ${distortion.name}",
                    feedbackType = FeedbackType.IMMEDIATE
                )

                repository.saveUserFeedback(feedback)
                showConfirmation()

            } catch (e: Exception) {
                showError(getString(R.string.save_failed))
            }
        }
    }

    private fun showConfirmation() {
        confirmationText.text = getString(R.string.journal_saved)
        confirmationCard.visibility = View.VISIBLE
        resultCard.visibility = View.GONE

        rootView.postDelayed({
            finish()
        }, 2000)
    }

    private fun getDistortionDisplayName(distortion: CognitiveDistortion): String {
        return when (distortion) {
            CognitiveDistortion.ALL_OR_NOTHING -> getString(R.string.all_or_nothing_thinking)
            CognitiveDistortion.OVERGENERALIZATION -> getString(R.string.overgeneralization)
            CognitiveDistortion.MENTAL_FILTER -> getString(R.string.mental_filter)
            CognitiveDistortion.DISQUALIFYING_POSITIVE -> getString(R.string.disqualifying_positive)
            CognitiveDistortion.JUMPING_TO_CONCLUSIONS -> getString(R.string.jumping_to_conclusions)
            CognitiveDistortion.MAGNIFICATION -> getString(R.string.magnification)
            CognitiveDistortion.EMOTIONAL_REASONING -> getString(R.string.emotional_reasoning)
            CognitiveDistortion.SHOULD_STATEMENTS -> getString(R.string.should_statements)
            CognitiveDistortion.LABELING -> getString(R.string.labeling)
            CognitiveDistortion.PERSONALIZATION -> getString(R.string.personalization)
        }
    }

    private fun getDistortionExplanation(distortion: CognitiveDistortion): String {
        return when (distortion) {
            CognitiveDistortion.ALL_OR_NOTHING -> getString(R.string.explanation_all_or_nothing)
            CognitiveDistortion.OVERGENERALIZATION -> getString(R.string.explanation_overgeneralization)
            CognitiveDistortion.MENTAL_FILTER -> getString(R.string.explanation_mental_filter)
            CognitiveDistortion.DISQUALIFYING_POSITIVE -> getString(R.string.explanation_disqualifying_positive)
            CognitiveDistortion.JUMPING_TO_CONCLUSIONS -> getString(R.string.explanation_jumping_to_conclusions)
            CognitiveDistortion.MAGNIFICATION -> getString(R.string.explanation_magnification)
            CognitiveDistortion.EMOTIONAL_REASONING -> getString(R.string.explanation_emotional_reasoning)
            CognitiveDistortion.SHOULD_STATEMENTS -> getString(R.string.explanation_should_statements)
            CognitiveDistortion.LABELING -> getString(R.string.explanation_labeling)
            CognitiveDistortion.PERSONALIZATION -> getString(R.string.explanation_personalization)
        }
    }
}