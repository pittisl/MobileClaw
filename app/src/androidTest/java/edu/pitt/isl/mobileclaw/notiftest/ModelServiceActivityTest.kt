package edu.pitt.isl.mobileclaw.notiftest

import android.os.Parcel
import android.os.Parcelable
import android.text.method.PasswordTransformationMethod
import android.util.SparseArray
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises real view-state behavior; never saves a profile or contacts any endpoint. */
@RunWith(AndroidJUnit4::class)
class ModelServiceActivityTest {
    @Test fun editingTheBaseUrlClearsAndRemasksTheDraftKey() {
        val revision = LlmConfig.snapshot().revision
        ActivityScenario.launch(ModelServiceActivity::class.java).use { scenario ->
            awaitReady(scenario)
            selectBearer(scenario)
            scenario.onActivity { activity ->
                val key = activity.findViewById<EditText>(R.id.modelApiKey)
                val reveal = activity.findViewById<CompoundButton>(R.id.modelShowKey)
                key.setText("draft-${UUID.randomUUID()}")
                reveal.isChecked = true
                activity.findViewById<EditText>(R.id.modelBaseUrl)
                    .setText("https://changed.example.invalid/v1/")
                assertEquals("", key.text.toString())
                assertFalse(reveal.isChecked)
                assertTrue(key.transformationMethod is PasswordTransformationMethod)
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.modelKeyClearedNotice).visibility)
            }
        }
        assertEquals(revision, LlmConfig.snapshot().revision)
    }

    @Test fun switchingServicesLoadsThatServicesProfileInsteadOfCarryingTheDraftKey() {
        val revision = LlmConfig.snapshot().revision
        val sentinel = "draft-${UUID.randomUUID()}"
        ActivityScenario.launch(ModelServiceActivity::class.java).use { scenario ->
            awaitReady(scenario)
            selectBearer(scenario)
            var target = LlmProvider.GEMINI
            scenario.onActivity { activity ->
                val chooser = activity.findViewById<Spinner>(R.id.modelProvider)
                target = if (chooser.selectedItemPosition == LlmProvider.GEMINI.ordinal)
                    LlmProvider.OPENAI else LlmProvider.GEMINI
                activity.findViewById<EditText>(R.id.modelApiKey).setText(sentinel)
                chooser.setSelection(target.ordinal)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitReady(scenario)
            val expected = LlmConfig.profileFor(target)
            scenario.onActivity { activity ->
                assertEquals(expected.baseUrl,
                    activity.findViewById<EditText>(R.id.modelBaseUrl).text.toString())
                assertEquals(expected.model,
                    activity.findViewById<EditText>(R.id.modelId).text.toString())
                assertNotEquals(sentinel,
                    activity.findViewById<EditText>(R.id.modelApiKey).text.toString())
                assertFalse(activity.findViewById<CompoundButton>(R.id.modelShowKey).isChecked)
            }
        }
        assertEquals(revision, LlmConfig.snapshot().revision)
    }

    @Test fun hierarchyStateAndRecreationNeverPreserveTheDraftKeyOrUrl() {
        val revision = LlmConfig.snapshot().revision
        val sentinel = "draft-${UUID.randomUUID()}"
        val draftUrl = "https://draft-${UUID.randomUUID()}.example.invalid/v1/"
        ActivityScenario.launch(ModelServiceActivity::class.java).use { scenario ->
            awaitReady(scenario)
            selectBearer(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.modelBaseUrl).setText(draftUrl)
                activity.findViewById<EditText>(R.id.modelApiKey).setText(sentinel)
                activity.findViewById<CompoundButton>(R.id.modelShowKey).isChecked = true
                val state = SparseArray<Parcelable>()
                activity.window.decorView.saveHierarchyState(state)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeSparseArray(state)
                    val serialized = String(parcel.marshall(), Charsets.UTF_16LE)
                    assertFalse("Draft key reached saved view state", serialized.contains(sentinel))
                    assertFalse("Draft URL reached saved view state", serialized.contains(draftUrl))
                } finally {
                    parcel.recycle()
                }
            }
            scenario.recreate()
            awaitReady(scenario)
            scenario.onActivity { activity ->
                assertNotEquals(sentinel,
                    activity.findViewById<EditText>(R.id.modelApiKey).text.toString())
                assertNotEquals(draftUrl,
                    activity.findViewById<EditText>(R.id.modelBaseUrl).text.toString())
                assertFalse(activity.findViewById<CompoundButton>(R.id.modelShowKey).isChecked)
            }
        }
        assertEquals(revision, LlmConfig.snapshot().revision)
    }

    private fun selectBearer(scenario: ActivityScenario<ModelServiceActivity>) {
        scenario.onActivity { it.findViewById<Spinner>(R.id.modelAuth).setSelection(0) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitReady(scenario: ActivityScenario<ModelServiceActivity>) {
        awaitUntil(message = "Model settings did not finish loading") {
            var ready = false
            scenario.onActivity { ready = it.findViewById<View>(R.id.modelServiceSave).isEnabled }
            ready
        }
    }
}
