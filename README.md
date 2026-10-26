# Kotlin Night Funkin (KNF)

A [Friday Night Funkin'](https://github.com/FunkinCrew/funkin)-style rhythm game written from scratch in **Kotlin** on the
**[KorGE](https://github.com/korlibs/korge) 6** engine, targeting the JVM desktop (mobile support may come later).

It reads Friday Night Funkin' **0.8.6**'s own data and asset trees directly, so the
songs, charts, note skins and UI art are the real thing - nothing is faked or
placeholder unless a file is missing.

---

## Requirements

* **JDK 21** - KorGE 6.0.0 requires it.
* The FNF **0.8.6 asset tree**, expected at `Funkin-0.8.6-Source/assets` or `./assets` (override with `-Dknf.assets=<path>`).
* **ffmpeg + ffprobe** on `PATH` for video cutscenes (override with `KNF_FFMPEG`/`KNF_FFPROBE` or `-Dknf.ffmpeg=...`/`-Dknf.ffprobe=...`). If they are missing, video cutscenes are skipped and the song still starts.

Gradle itself is bootstrapped by the wrapper (`gradlew.bat`).

## Running

```powershell
$env:JAVA_HOME="[YOUR_JAVA21+_DIR]"
.\gradlew.bat run
```

---

## Environment variables

Launch directly into a song with autoplay on:

```powershell
$env:KNF_SONG="bopeebo"; $env:KNF_DIFFICULTY="easy"; $env:KNF_AUTOPLAY="1"
.\gradlew.bat run
```

| Variable | Effect                                                |
|---|-------------------------------------------------------|
| `KNF_SONG` | skip the menus and load this song id                  |
| `KNF_DIFFICULTY` | `easy` / `normal` / `hard` / `erect` / `nightmare`    |
| `KNF_VARIATION` | `default` / `erect` / `pico` / `bf`                   |
| `KNF_AUTOPLAY` | `1` = botplay                                         |
| `KNF_DEBUG_TEXTURES` | `1` = draw raw note-skin bitmaps for inspection       |
| `KNF_DEBUG_FRAMES` | `1` = per-frame gameplay logging                      |
| `KNF_DEBUG_CHAR` | `1` = character loading diagnostics                   |
| `KNF_DEBUG_AVSYNC` | `1` = measure the audio-vs-chart clock offset         |
| `KNF_DEBUG_INPUT` | `1` = log every key press the game receives           |
| `KNF_DEBUG_CLOCK` | `1` = print wall-clock vs audio-clock drift every 500 ms |
| `KNF_DEBUG_VIDEO` | `1` = log video decoding/playback and `2hot` prelude phases |
| `KNF_STORY` | `1` = treat Freeplay selections as Story Mode (enables story-gated videos) |
| `KNF_END_AT_MS` | debug: treat the song as finished at this song position (for testing endings) |
| `KNF_FFMPEG` / `KNF_FFPROBE` | explicit ffmpeg/ffprobe executable paths |

---

## Credits

Game design, data format etc. are the work of the Friday Night Funkin' crew (PhantomArcade,
ninjamuffin99, Kawai Sprite and many more). This project is an independent
reimplementation for study and experimentation.

---

## Links

* **[Official Friday Night Funkin' GitHub](https://github.com/FunkinCrew/funkin)**
* **[Official Friday Night Funkin' Assets](https://github.com/FunkinCrew/Funkin.assets/tree/724eba9de9e76761d9cefd52169926bc51c760a5)**
* **[Official KorGE GitHub](https://github.com/korlibs/korge)**
* **[My Discord](https://потом.добавлю.ссылку)**