package org.rampart

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleEditorTest {
    private val rule = Rule(
        name = "One part",
        tests = listOf(Test(Field.FROM, Match.CONTAINS, "example.test")),
        acts = listOf(Act.MarkRead),
    )

    @Test
    fun `the structured editor only accepts one condition and one action`() {
        assertTrue(rule.canEditStructured())
        assertFalse(rule.copy(tests = rule.tests + Test(Field.SUBJECT, Match.CONTAINS, "news")).canEditStructured())
        assertFalse(rule.copy(acts = rule.acts + Act.Delete).canEditStructured())
    }
}
