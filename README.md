# ことばロック · Kotoba Lock

An Android app that makes you answer a word from your [Kotoba](https://kotobaweb.com) decks — in hiragana — every time you unlock your phone. You can also study in the app whenever you like.

## Features

### Quiz
- **Quiz on unlock**: a card from your decks appears full-screen after you unlock. Type the reading in hiragana and tap 確認.
- **Answer + meaning**: after your attempt you see ✓/✗, the correct reading, and the meaning (the card's comment, or a one-time lookup on jisho.org).
- **Attempt or must-be-correct**: by default one attempt is enough; optionally you must type the correct answer to unlock (解放).
- **Example sentences**: the 例文を見る button at the bottom of the quiz opens a panel of example sentences from jisho.org, with furigana and English. The quiz word is highlighted, and its own reading stays hidden until you've answered.
- **Unlock animations**: choose how the quiz leaves the screen (fade, slides, zoom, circle close, curtain, spin, card flip, drop, blur dissolve, or none) and how long it takes.
- Optional romaji input, questions per unlock, cooldown, configurable word size.

### Study mode
- **学習する · Study** on the Home tab quizzes you on your enabled decks inside the app, with no limit on questions. Exit with 終了 or back.
- No emergency call or flashlight buttons; answers count toward your progress.

### Decks
- **Multiple decks**: add as many Kotoba deck links as you like and switch each one on or off.
- **Overlap handled**: a word that appears in more than one enabled deck is only asked once, and the answers from every deck are accepted.
- **Deck sync**: log in once with Discord inside the app; decks re-sync in the background so new words show up automatically.

### Progress
- **Answer counter**: your lifetime total sits in the middle of the quiz's bottom bar.
- **Per-word tracking**: every first attempt is recorded. With **Prioritise words I struggle with** on, words you miss come up more often until you get them right.
- **Daily new-word limit**: caps how many never-seen words you get per day (20 by default, can be turned off). After that you only review words you've met.
- **Graphs** (Progress tab): totals, day streak, first-try accuracy, answers per day, words seen over time, how well you know your words (struggling / learning / known / new), and how much of each deck you've seen.

### Lock screen mode
- The quiz is put up whenever the screen turns off, so with Android's screen lock set to None/Swipe it acts as your lock screen. Includes 緊急通報 (emergency call) and a flashlight toggle, and hides the navigation bar.

### Looks
- **Themes**: presets (Midnight, Light, Sakura, Matcha, Ocean, OLED) plus per-colour customisation; the settings use the same theme. A calm animated background floats either words from your decks or hiragana.
- Settings are split into tabs: **Home · Decks · Quiz · Progress · Look · Lock**.

## Not real security

Android doesn't allow apps to replace a real PIN/pattern lock. Lock screen mode is a study aid, not protection: anyone can get past it, and notifications / quick settings / power menu stay reachable.

## Build

Requires the Android SDK (compileSdk 36) and JDK 17–24 (Gradle 8.13 doesn't run on JDK 25).

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk` (debug-signed so it installs directly). Updates only install over a build signed with the same debug key, so build future versions on the same machine.

## Setup on the phone

1. Install the APK (allow installing unknown apps).
2. On the **Home** tab, allow **Display over other apps** and disable battery optimisation.
3. On the **Decks** tab, paste a Kotoba deck link (`https://kotobaweb.com/dashboard/decks/<id>?secret=<secret>`) and tap **Add deck**. Repeat for more decks.
4. Tap **Log in with Discord**, then **Sync all**.
5. Tap **学習する · Study** on the Home tab, or **Preview the unlock quiz** on the Lock tab.

Keep your decks' `secret` private — it allows editing the deck.

## Data

Everything stays on the phone: your decks, settings and answer history are stored in the app's private storage. The app talks to kotobaweb.com (deck sync) and jisho.org (meanings and example sentences).
