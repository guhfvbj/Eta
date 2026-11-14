package io.github.mangi.eta.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomScriptsTest {
    @Test
    fun `readable extraction is bounded and preserves absolute urls`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.readable(offset = 0, maxChars = 8_000))

        assertTrue(script.contains("!visible(node)"))
        assertTrue(script.contains("remainingNodes: 8000"))
        assertTrue(script.contains("deadline: Date.now() + 750"))
        assertTrue(script.contains("return boundedString(parsed.href"))
        assertFalse(script.contains("parsed.protocol !== 'https:'"))
        assertFalse(script.contains("innerText"))
        assertFalse(script.contains("textContent"))
    }

    @Test
    fun `click preparation scrolls selectors before checking visibility and hit targets`() {
        val preparation = BrowserDomScripts.prepareInteraction("#submit", null, null)
        assertTrue(preparation.contains("scrollIntoView({ block: 'center', inline: 'nearest'"))
        assertTrue(preparation.indexOf("scrollIntoView") < preparation.indexOf("interactionPoint(target)"))
        assertTrue(preparation.contains("interaction_ready: false"))
        assertFalse(preparation.contains("target.click()"))
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.click(selector = "#submit", x = null, y = null)
        )

        assertTrue(script.contains("document.querySelector(selector);"))
        assertTrue(script.contains("TARGET_OCCLUDED"))
        assertTrue(script.contains("document.elementFromPoint(x, y)"))
        assertTrue(script.indexOf("var point = interactionPoint(target)") < script.indexOf("target.click()"))
    }

    @Test
    fun `typing checks interactability before focusing and preserves coordinate targeting`() {
        val script = BrowserDomScripts.type("#input", null, null, "quoted \"text\"", false)
        assertTrue(script.contains("TARGET_NOT_EDITABLE"))
        assertTrue(script.indexOf("interactionPoint(target)") < script.indexOf("target.focus"))
        assertTrue(script.contains("preventScroll: true"))
        val preparation = BrowserDomScripts.prepareInteraction(null, 10, 20)
        assertTrue(preparation.contains("resolveTarget(null, 10, 20)"))
        assertTrue(preparation.contains("if (false) target.scrollIntoView"))
    }
}
