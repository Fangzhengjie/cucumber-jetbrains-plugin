package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.Language

/**
 * Decides which languages this plugin handles. A language is skipped while another plugin
 * is enabled. No language-specific plugin is required.
 */
object LanguageAvailability {

  /** Whether this plugin provides step definitions for `language`. */
  fun isEnabled(language: Language): Boolean {
    val override = CucumberSettings.getInstance().override(language)
    return override == null || override
  }
}
