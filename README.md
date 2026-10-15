# USMAN AND UMAR

USMAN AND UMAR is an Android automation assistant built on the OpenDroid codebase. This branch moves reasoning to cloud LLM providers, with OpenRouter as the default, while keeping device actions explicit and separately permissioned.

## Runtime architecture

- **Cloud reasoning:** OpenRouter is selected for new installations. Other configured providers are available from Settings. Provider credentials are stored through the app's protected credential store; requests require a configured provider and, where applicable, an API key.
- **Tool execution:** The cloud model can request registered tools through the OpenAI-compatible tool-calling protocol. The app validates and dispatches each tool locally; Android actions remain subject to their existing grants and approval rules.
- **GitHub discovery:** Public repository search and inspection use GitHub's REST API. The app can save a repository as supported project context, make it active, inspect metadata/README/root entries, and start a real `git clone` through the Termux `RUN_COMMAND` API.
- **Integrated Linux terminal:** The terminal screen owns an app-private Alpine ARMv7 userspace and starts it through the reviewed Termux-maintained PRoot source with `--link2symlink`. The rootfs is downloaded and SHA-256 verified on first use; the app does not require root or a separate Termux installation. The PRoot ARM32 executable must be built and installed for the target device before the runtime can report ready.
- **Optional Termux bridge:** The legacy external Termux integration remains available for explicitly authorized compatibility tasks, but it is not the integrated terminal backend and is not required for normal app operation.
- **No bundled local inference:** The app no longer packages a GGUF model, llama.cpp module, or LiteRT local inference engine. Termux remains an optional user-authorized execution backend; it is not an AI reasoning engine.

GitHub and Linux-runtime installation require connectivity. GitHub public endpoints can be rate-limited. Tool availability and successful model tool-calling vary by provider/model. Android can stop background processes, so terminal sessions are recoverable but are not advertised as unlimited background execution.

## Android and build requirements

| Setting | Value |
|---|---|
| Minimum Android SDK | 28 (Android 9) |
| Target Android SDK | 34 (Android 14) |
| Current app version | `3.0.0-cloud-ai` (versionCode 23) |
| Packaging | Android application; no model asset or inference JNI module |

Build with JDK 21 and the Android SDK installed:

```bash
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease -PallowUnsignedRelease=true
```

The opt-in unsigned release is for validation only and is not installable as a distributable signed release. A distributable release requires a release keystore and passwords supplied through private Gradle properties or environment variables. Never commit signing material.

## Device setup

After installation, configure an LLM provider and its API key in Settings. Open the integrated terminal to install the verified Alpine userspace, then use its real shell and `apk` package manager. The optional compatibility bridge separately requires Termux, its external-command setting, and the Android `RUN_COMMAND` permission. Android permission prompts and high-impact plan approvals remain in force.

## Verification status

Build and unit-test results are recorded in the pull request. Physical-device execution of the full GitHub → Termux clone → file inspection workflow is **UNVERIFIED — REQUIRES DEVICE** until exercised on a supported Android device with Termux installed and authorized.

## Project provenance and license

The Android foundation is based on the upstream [OpenDroid project](https://github.com/yashab-cyber/opendroid). Apache License 2.0 notices are retained in [LICENSE](LICENSE). Project notes are in [`docs/`](docs/).
