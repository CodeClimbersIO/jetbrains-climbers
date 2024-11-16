# jetbrains-climbers

This is a fork of the Wakatime plugin for Jetbrains [here](https://github.com/wakatime/jetbrains-wakatime).

![Build](https://github.com/CodeClimbersIO/jetbrains-climbers/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/io.codeclimbers.jetbrains.svg)](https://plugins.jetbrains.com/plugin/io.codeclimbers.jetbrains)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/io.codeclimbers.jetbrains.svg)](https://plugins.jetbrains.com/plugin/io.codeclimbers.jetbrains)

<!-- Plugin description -->
Code Climbers plugin for Jetbrains. Collect metrics, insights, and time tracking automatically from your programming activity.
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Code Climbers"</kbd> >
  <kbd>Install</kbd>
- Re-launch your IDE.
- Use your IDE like you normally do and your time will be tracked for you automatically.
- Visit https://local.codeclimbers.io to see your logged time.

  
- Manually:

  Download the [latest release](https://github.com/CodeCLimbersIO/jetbrains-climbers/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation

## Development

### Building Locally
1. Clone the repository
2. Open the project in IntelliJ IDEA
3. Ensure you have the "Gradle" and "Plugin DevKit" plugins enabled
4. Import the project as a Gradle project
5. To build and run the plugin locally:
   - Run `./gradlew buildPlugin` to build the plugin
   - Run `./gradlew runIde` to start a new IntelliJ instance with the plugin installed

### Publishing to JetBrains Marketplace
1. Update the version in `gradle.properties`
2. Create a production build:
   ```bash
   ./gradlew buildPlugin
   ```
3. Test the plugin thoroughly
4. Create a new release on GitHub with the version number
5. Upload to JetBrains Marketplace:
   - Log in to [JetBrains Marketplace](https://plugins.jetbrains.com/)
   - Go to "Upload plugin"
   - Select the zip file from `build/distributions/`
   - Fill in the release notes
   - Submit for review

## Installation