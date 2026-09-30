package id.hanifalfaqih.aienglishinterview.feature.experience

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure unit tests for the Slice 2 entry drafts (no coroutines, no I/O). */
class EntryDraftTest {

    @Before
    fun setUp() {
        ManualDraft.clear()
        SelectedExperience.clear()
    }

    @After
    fun tearDown() {
        ManualDraft.clear()
        SelectedExperience.clear()
    }

    @Test
    fun `blank draft fails required validation`() {
        assertTrue(ManualDraft.nameError)
        assertTrue(ManualDraft.didError)
    }

    @Test
    fun `filled required fields pass validation`() {
        ManualDraft.name = "Campus Cart"
        ManualDraft.did = "Built a cart feature"
        assertFalse(ManualDraft.nameError)
        assertFalse(ManualDraft.didError)
    }

    @Test
    fun `toItem maps to backend contract and folds extras`() {
        ManualDraft.type = ExperienceType.PROJECT
        ManualDraft.name = "  Campus Cart  "
        ManualDraft.role = " Android dev "
        ManualDraft.did = "Built a cart feature."
        ManualDraft.challenge = "Flaky stock API."
        ManualDraft.result = "Demo day finalist."

        val item = ManualDraft.toItem()

        assertEquals("Campus Cart", item.title)
        assertEquals("Android dev", item.role)
        assertNull(item.organization)
        assertTrue(item.description.startsWith("Built a cart feature."))
        assertTrue(item.description.contains("Challenge: Flaky stock API."))
        assertTrue(item.description.contains("Result: Demo day finalist."))
        assertTrue(item.skills.isEmpty())
    }

    @Test
    fun `toItem omits blank optionals without labels`() {
        ManualDraft.name = "X"
        ManualDraft.did = "Did things."

        val item = ManualDraft.toItem()

        assertEquals("Did things.", item.description)
        assertNull(item.role)
    }

    @Test
    fun `clear resets selection`() {
        ManualDraft.name = "X"
        ManualDraft.type = ExperienceType.OTHER
        ManualDraft.clear()
        assertTrue(ManualDraft.nameError)
        assertNull(ManualDraft.type)
    }

    @Test
    fun `mimeFor routes docx by extension`() {
        assertEquals(MIME_DOCX, mimeFor("CV.DOCX"))
        assertEquals(MIME_PDF, mimeFor("cv.pdf"))
        assertEquals(MIME_PDF, mimeFor("noextension"))
    }
}
