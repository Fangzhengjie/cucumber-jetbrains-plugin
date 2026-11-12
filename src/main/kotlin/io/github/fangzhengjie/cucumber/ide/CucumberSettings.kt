package io.github.fangzhengjie.cucumber.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.ModificationTracker
import io.github.fangzhengjie.cucumber.core.Language
import java.util.TreeMap

/** Per-language on/off overrides; languages without one follow [LanguageAvailability]. */
@State(name = "CucumberSettings", storages = [Storage("cucumber-jetbrains-plugin.xml")])
class CucumberSettings :
    PersistentStateComponent<CucumberSettings.SettingsState>, ModificationTracker {

  class SettingsState {
    var overrides: MutableMap<String, Boolean> = TreeMap()
  }

  private var currentState = SettingsState()
  @Volatile private var modifications: Long = 0

  override fun getState(): SettingsState = currentState

  override fun loadState(state: SettingsState) {
    currentState = state
    modifications++
  }

  /** The user's choice for `language`, or null to decide automatically. */
  fun override(language: Language): Boolean? = currentState.overrides[language.name]

  fun setOverride(language: Language, enabled: Boolean?) {
    if (enabled == null) {
      currentState.overrides.remove(language.name)
    } else {
      currentState.overrides[language.name] = enabled
    }
    modifications++
  }

  override fun getModificationCount(): Long = modifications

  companion object {
    @JvmStatic
    fun getInstance(): CucumberSettings =
        ApplicationManager.getApplication().getService(CucumberSettings::class.java)
  }
}
