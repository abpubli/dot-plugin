# IntelliJ DOT Language Support Plugin (intellij-dot-plugin)

IntelliJ Platform plugin providing enhanced support for the Graphviz DOT language.

## Features

* Live preview panel to visualize graphs as you type.
* Split editor view (Editor / Split / Preview modes).
* Error highlighting: underlines the line in the `.dot` file editor where a syntax error is detected, helping you quickly find and fix issues.
* New file template: option available under `File -> New` to create a new `.dot` file with sample content, getting you started faster.
## Prerequisites

This plugin requires a working **Graphviz** installation on your system to render graphs. Please install it using the appropriate method for your operating system:

### Linux

* **Debian / Ubuntu / Mint (and other `apt`-based distros):**
```bash
sudo apt update && sudo apt install graphviz
```

* **Fedora / CentOS Stream / RHEL / Rocky Linux / AlmaLinux (and other `dnf`/`yum`-based distros):**
```bash
sudo dnf install graphviz
```
*(Note: On older systems or RHEL/CentOS, you might need `sudo yum install graphviz`. If the package is not found, you may need to enable the EPEL repository first.)*

* **openSUSE (using `zypper`):**
```bash
sudo zypper refresh && sudo zypper install graphviz
```

### macOS

* **Using Homebrew (recommended):**
```bash
brew install graphviz
```

### Windows

1.  Download the installer package or ZIP archive from the official **[Graphviz Download page](https://graphviz.org/download/)**.
2.  Run the installer or extract the archive.
3.  **Crucial:** Add the path to the Graphviz `bin` directory (e.g., `C:\Program Files\Graphviz\bin`) to your system's **`PATH` environment variable**. This allows the plugin (and command line) to find the `dot.exe` executable.

### Verification

After installation, open a terminal or command prompt and run:
```bash
dot -V
```

## Building the Plugin

For local development and debugging, use local insalled IDEA.

### Clean Builds

In some cases, especially if you encounter inconsistent build results or suspect caching issues, you might need to perform a clean build. This process bypasses the Gradle build and configuration caches, ensuring that the plugin is built entirely from scratch. Note that clean builds are typically slower than cached builds.

To perform a clean build, open your terminal in the project's root directory and run the following command:

```bash
./gradlew clean buildPlugin --no-build-cache --no-configuration-cache
```

This command will first clean the previous build outputs and then execute the `buildPlugin` task without using any Gradle caches.

## Known Build Issue (`buildSearchableOptions`)

During the plugin build process, specifically in the `buildSearchableOptions` task, `SEVERE` errors (e.g., `Memory leak detected... registered ... as child of 'ROOT_DISPOSABLE'`) might appear in the logs. This has been observed when building against certain IntelliJ Platform versions (e.g., stable 2024.3.5 and EAP 2025.1) and seems related to issues within the platform itself or its bundled components when initializing settings pages for indexing. Importantly, these errors often do not halt the entire build process, and the final plugin `.zip` file is still created correctly.

### Applied Workaround

To eliminate these potentially misleading errors from the build logs and ensure a more predictable build process, the `buildSearchableOptions` task has been intentionally **disabled** in the Gradle configuration (`build.gradle.kts`).

Since this plugin **currently does not add any custom settings pages** to the IntelliJ Settings/Preferences dialog, the `buildSearchableOptions` task (which indexes these settings for the search functionality) is not essential for the plugin's operation. Disabling it does not negatively impact the plugin's functionality for the end-user.

The configuration disabling the task in `build.gradle.kts` (for the `org.jetbrains.intellij` v1.x plugin) looks like this:

```kotlin
// Inside the tasks { ... } block or at the top level
tasks.named<org.jetbrains.intellij.tasks.BuildSearchableOptionsTask>("buildSearchableOptions") {
    enabled = false // Disabled due to non-fatal SEVERE errors (platform issue) logged by this task during build.
}
```

If custom configurable settings are added to the plugin in the future, this task may need to be re-enabled (by setting `enabled = true`). This might require addressing the underlying platform issues or targeting a newer, fixed version of the IntelliJ Platform.


## Usage

1.  Open a `.dot` file in IntelliJ IDEA.
2.  Use the view mode buttons (usually in the top-right corner of the editor panel) to switch between `Editor`, `Split` (Editor and Preview side-by-side), and `Preview` modes.
3.  The preview will update automatically as you modify the DOT file (or after a short delay).

## License

Distributed under the Apache License. See `LICENSE` file for more information.
