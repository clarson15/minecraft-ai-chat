# AI Chat

A lightweight Fabric singleplayer or server mod that lets players chat with an AI assistant. The AI can answer questions and, if enabled, request the server to run safe commands. The mod can be installed on [curseforge](https://www.curseforge.com/minecraft/mc-mods/ai-chat).

## Features
- /ai ask <message> — ask the AI; keeps short per-player history
- /ai reset — clear your conversation history
- /ai reload — reload config (op-only)
- Providers: OpenAI API or local Ollama
- Optional tool-calling: AI can request a server command, gated by an allowlist

## Install
- Build the mod jar (see Build) and place it in your server's `mods/` folder
- Start the server once to generate `config/ai-chat.json`

## Configuration: `config/ai-chat.json`
Key options:
- provider: "openai" | "ollama"
- systemPrompt: server-wide instructions for the AI
- allowRunCommands: false by default; set true to allow tool-calling
- commandAllowlist: list of allowed command prefixes (e.g., ["say", "time set", "weather"]) — only commands beginning with one of these prefixes will be executed
- cooldownSeconds: per-player rate limit
- maxHistory: number of user/assistant pairs retained
- temperature, maxTokens: model generation controls
- HTTP timeouts: httpConnectTimeoutSec, httpReadTimeoutSec, httpWriteTimeoutSec, httpCallTimeoutSec

Provider-specific:
- OpenAI: openaiApiBase, openaiApiKey (or env OPENAI_API_KEY), openaiModel
- Ollama: ollamaBaseUrl, ollamaModel

## Usage and security
- By default, commands are NOT executed (allowRunCommands=false)
- If enabled, whitelist commands via commandAllowlist

## Build
Project uses Gradle with Fabric Loom.

Windows PowerShell:
```powershell
./gradlew.bat build -x test
```
The jar will be in `build/libs/`.

## Notes
- Requires Java compatible with your Fabric/Minecraft target (see gradle.properties)
- Chat history is in-memory per-player only
