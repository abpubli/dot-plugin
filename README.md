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

## Usage

1.  Open a `.dot` file in IntelliJ IDEA.
2.  Use the view mode buttons (usually in the top-right corner of the editor panel) to switch between `Editor`, `Split` (Editor and Preview side-by-side), and `Preview` modes.
3.  The preview will update automatically as you modify the DOT file (or after a short delay).

## License

Distributed under the Apache License. See `LICENSE` file for more information.
