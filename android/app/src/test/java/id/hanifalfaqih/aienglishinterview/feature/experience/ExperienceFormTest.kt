package id.hanifalfaqih.aienglishinterview.feature.experience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceFormTest {

    @Test
    fun parseSkills_splitsTrimsAndDropsBlanks() {
        assertEquals(
            listOf("Kotlin", "Leadership", "SQL"),
            parseSkills("Kotlin, Leadership,, SQL ,"),
        )
    }

    @Test
    fun parseSkills_emptyIsEmpty() {
        assertTrue(parseSkills("").isEmpty())
        assertTrue(parseSkills(" , , ").isEmpty())
    }

    @Test
    fun form_requiresTitleAndDescription() {
        assertTrue(ExperienceForm().titleError)
        assertTrue(ExperienceForm().descriptionError)
        val valid = ExperienceForm(title = " T ", description = " D ")
        assertFalse(valid.titleError)
        assertFalse(valid.descriptionError)
    }

    @Test
    fun toItem_trimsAndNullsBlanks() {
        val item = ExperienceForm(
            title = "  Intern  ",
            organization = "   ",
            role = "",
            description = " Did things ",
            skillsRaw = "Kotlin,  ",
        ).toItem()
        assertEquals("Intern", item.title)
        assertNull(item.organization)
        assertNull(item.role)
        assertEquals("Did things", item.description)
        assertEquals(listOf("Kotlin"), item.skills)
    }
}
