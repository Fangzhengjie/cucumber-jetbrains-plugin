package io.github.fangzhengjie.cucumber.ide

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.Locale
import javax.swing.Icon

/** A small rounded tile with a letter, e.g. the G / W / T keyword markers in the tool window. */
internal class LetterIcon(private val letter: String, private val color: JBColor) : Icon {

  override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      val size = getIconWidth()
      val inset = JBUI.scale(1)
      val arc = JBUI.scale(5)
      g2.color = color
      g2.fillRoundRect(x + inset, y + inset, size - 2 * inset, size - 2 * inset, arc, arc)
      val font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
      g2.font = font
      val metrics = g2.fontMetrics
      val textX = x + (size - metrics.stringWidth(letter)) / 2
      val textY = y + (size - metrics.height) / 2 + metrics.ascent
      g2.color = JBColor.WHITE
      g2.drawString(letter, textX, textY)
    } finally {
      g2.dispose()
    }
  }

  override fun getIconWidth(): Int = JBUI.scale(16)

  override fun getIconHeight(): Int = JBUI.scale(16)

  companion object {
    @JvmField val GIVEN = JBColor(0x13857F, 0x3FB6AE)
    @JvmField val WHEN = JBColor(0x8A4BAF, 0xB282D6)
    @JvmField val THEN = JBColor(0x2E7D32, 0x5FAE63)
    @JvmField val OTHER = JBColor(0x7B8794, 0x8A949E)
    @JvmField val UNDEFINED = JBColor(0xD32F2F, 0xE0605C)

    @JvmStatic
    fun forKeyword(keyword: String, defined: Boolean): LetterIcon {
      if (!defined) {
        return LetterIcon("?", UNDEFINED)
      }
      return when (keyword.lowercase(Locale.ROOT)) {
        "given" -> LetterIcon("G", GIVEN)
        "when" -> LetterIcon("W", WHEN)
        "then" -> LetterIcon("T", THEN)
        "and" -> LetterIcon("A", OTHER)
        "but" -> LetterIcon("B", OTHER)
        else -> LetterIcon("•", OTHER)
      }
    }
  }
}
