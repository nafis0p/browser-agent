# Browser Agent (Android)

An Android app with an in-app browser that an AI agent can operate. Everything runs on the phone: no server needed.

- **Text snapshots, not screenshots.** The page is turned into compact text (page text + interactive elements with ids like `[e12] button "Sign in"`), so it works with any text model.
- **Actions:** navigate, click, fill (React/Vue-safe), select, press Enter, scroll, back, wait.
- **Your own providers.** Add any OpenAI-compatible endpoint + key. Define models as an ordered list of routes; on 429, 5xx, timeouts or auth/quota errors the agent falls back to the next provider automatically.
- **MCP.** Add remote MCP servers (Streamable HTTP). Their tools appear next to the browser tools. Non read-only tools ask for confirmation.
- **Safety.** Page content is treated as untrusted data; the agent must `confirm` before payments, messages, account changes or passwords. Keys are stored with EncryptedSharedPreferences.

## Get the APK

GitHub Actions builds it on every push: open the **Actions** tab, pick the latest run, download the `browser-agent-debug-apk` artifact, unzip, install `app-debug.apk` (allow installs from unknown sources).

Build locally: open in Android Studio (JDK 17), or `gradle assembleDebug` with Gradle 8.9+.

## Use

1. **Providers** tab: add your API keys, check the model ids (the defaults are examples), Save.
2. Pick a model in the panel under the browser.
3. Tell the agent what to do, e.g. "Open example.com and fill the contact form with ...".

## Limits

- Use models that support tool/function calling.
- Cross-origin iframes, canvas UIs and bot-protected sites are hard for any WebView agent.
- Only remote MCP servers work (stdio servers can't run on Android).
- LLM calls are non-streaming.
- Early version: expect rough edges.
