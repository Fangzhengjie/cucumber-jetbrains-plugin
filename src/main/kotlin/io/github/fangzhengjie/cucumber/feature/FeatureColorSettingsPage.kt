package io.github.fangzhengjie.cucumber.feature

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class FeatureColorSettingsPage : ColorSettingsPage {

  override fun getIcon(): Icon? = FeatureFileType.icon

  override fun getHighlighter(): SyntaxHighlighter = FeatureSyntaxHighlighter()

  override fun getDemoText(): String =
      """
      # language: en
      @smoke @checkout
      Feature: Checkout
        Customers can pay for the items in their cart.

        Background:
          Given a logged in user "alice"

        Scenario Outline: Pay with a card
          When I add <count> items costing 12.50 each
          And I pay with the card:
            | number           | cvc |
            | 4111111111111111 | 123 |
          Then the receipt says:
            $DOC_STRING_DELIMITER
            Thank you for your order
            $DOC_STRING_DELIMITER

          Examples:
            | count |
            | 3     |
      """
          .trimIndent() + "\n"

  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

  override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

  override fun getDisplayName(): String = "Cucumber"

  private companion object {
    const val DOC_STRING_DELIMITER = "\"\"\""

    val DESCRIPTORS =
        arrayOf(
            AttributesDescriptor(
                "Keyword (Feature, Scenario, ...)", FeatureSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("Title", FeatureSyntaxHighlighter.TITLE),
            AttributesDescriptor(
                "Step keyword (Given, When, ...)", FeatureSyntaxHighlighter.STEP_KEYWORD),
            AttributesDescriptor("Step text", FeatureSyntaxHighlighter.STEP_TEXT),
            AttributesDescriptor("Quoted string", FeatureSyntaxHighlighter.QUOTED),
            AttributesDescriptor("Number", FeatureSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Outline parameter", FeatureSyntaxHighlighter.OUTLINE_PARAM),
            AttributesDescriptor("Tag", FeatureSyntaxHighlighter.TAG),
            AttributesDescriptor("Comment", FeatureSyntaxHighlighter.COMMENT),
            AttributesDescriptor("Table separator", FeatureSyntaxHighlighter.TABLE_PIPE),
            AttributesDescriptor("Table cell", FeatureSyntaxHighlighter.TABLE_CELL),
            AttributesDescriptor("Doc string", FeatureSyntaxHighlighter.DOC_STRING),
            AttributesDescriptor("Description text", FeatureSyntaxHighlighter.TEXT),
        )
  }
}
