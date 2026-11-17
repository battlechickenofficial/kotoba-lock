# ことばロック · Kotoba Lock

An Android app that makes you answer a word from your [Kotoba](https://kotobaweb.com) deck — in hiragana — every time you unlock your phone.

## Features

- **Quiz on unlock**: a random card from your Kotoba deck appears full-screen after you unlock. Type the reading in hiragana and tap 確認.
- **Answer + meaning**: after your attempt you see ✓/✗, the correct reading, and the meaning (the card's comment, or a one-time lookup on jisho.org).
- **Attempt or must-be-correct**: by default one attempt is enough; optionally you must type the correct answer to unlock (解放).
- **Lock screen mode**: the quiz is put up whenever the screen turns off, so with Android's screen lock set to None/Swipe it acts as your lock screen. Includes 緊急通報 (emergency call) and a flashlight toggle, and hides the navigation bar.
- **Deck sync**: log in once with Discord inside the app; the deck re-syncs in the background so new words show up automatically.
- **Themes**: presets (Midnight, Light, Sakura, Matcha, Ocean, OLED) plus per-colour customisation; the settings page uses the same theme. A calm animated background floats either words from your deck or hiragana.
- Optional romaji input, questions per unlock, cooldown, configurable word size.

## Not real security

Android doesn't allow apps to replace a real PIN/pattern lock. Lock screen mode is a study aid, not protection: anyone can get past it, and notifications / quick settings / power menu stay reachable.

## Build

Requires the Android SDK (compileSdk 36) and JDK 17+.

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk` (debug-signed so it installs directly).

## Setup on the phone

1. Install the APK (allow installing unknown apps).
2. Allow **Display over other apps** and disable battery optimisation from the Setup section.
3. Paste your Kotoba deck link (`https://kotobaweb.com/dashboard/decks/<id>?secret=<secret>`), tap **Log in with Discord**, and sync.
4. Tap **Try the quiz now**.

Keep your deck's `secret` private — it allows editing the deck.
