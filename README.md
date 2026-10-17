# Kotlin Night Funkin (KNF)

A Friday Night Funkin'-style rhythm game written from scratch in **Kotlin** on the
**[KorGE](https://github.com/korlibs/korge) 6** engine, targeting the JVM desktop.

It reads Friday Night Funkin' **0.8.6**'s own data and asset trees directly, so the
songs, charts, note skins and UI art are the real thing — nothing is faked or
placeholder unless a file is missing.

---

## Status

| Milestone | State |
|---|---|
| Gradle + KorGE 6 project, 1280×720 window | ✅ |
| `Conductor` timing core (BPM/sig changes, sub-frame interpolation, drift correction) | ✅ |
| Song discovery + v2 metadata/chart parsing | ✅ 27 songs |
| Strumlines, streaming note spawning, pooling | ✅ |
| Note skins (funkin + pixel, with `fallback` support) | ✅ |
| Hit detection, PBOT1 judgement + scoring, combo | ✅ |
| Health bar, score, judgement popups | ✅ |
| Note splashes, sustain trails | ✅ |
| Countdown, song-end, death | ✅ |
| Song events (`FocusCamera`, `ScrollSpeed`, `ZoomCamera`, `SetCameraBop`) | ✅ |
| Camera follow / zoom / beat bop | ✅ |
| Title + Freeplay song select | ✅ |
| Characters (bf / gf / dad + 20 others), full Animate symbol tree | ✅ |
| Stage props in FNF stage space, z-index order, stage camera zoom | ✅ |
| Runtime audio-offset adjustment + persistent save | ✅ |
| Player + opponent vocals (VoicesGroup, one track / two heads) | ✅ |
| Pause menu, results screen, chart editor, practice mode | ⏳ planned |

Verified end-to-end with autoplay: `Bopeebo`, `Senpai` and others play to the end
with 100% accuracy under botplay.

---

## Requirements

* **JDK 21** — KorGE 6.0.0 requires it. A copy is installed at
  `C:\Users\Admin\Tools\jdk-21` and `JAVA_HOME` is set for your user account.
* The FNF **0.8.6 asset tree**, expected at `Funkin-0.8.6-Source/assets`
  (override with `-Dknf.assets=<path>`).

Gradle itself is bootstrapped by the wrapper (`gradlew.bat`).

## Running

```powershell
$env:JAVA_HOME="C:\Users\Admin\Tools\jdk-21"
.\gradlew.bat run
```

### Controls

| Key | Action |
|---|---|
| `A` `S` `W` `D` / arrows | hit the four notes |
| `Z` / `Enter` / `Space` | accept |
| `X` / `Esc` / `Backspace` | back |
| `W`/`S` | song up / down (Freeplay) |
| `A`/`D` | difficulty left / right (Freeplay) |
| `[` / `]` | audio offset −/+ 5 ms (saved to `~/knf-save.json`) |

Those last keys matter: every machine has a different audio output latency, and
a handful of milliseconds is the difference between "locked in" and "off". Nudge
it until the notes sit exactly on the beat — it is remembered between runs.

### Test hooks

Boot straight into a song, with botplay on:

```powershell
$env:KNF_SONG="bopeebo"; $env:KNF_DIFFICULTY="hard"; $env:KNF_AUTOPLAY="1"
.\gradlew.bat run
```

| Variable | Effect |
|---|---|
| `KNF_SONG` | skip the menus and load this song id |
| `KNF_DIFFICULTY` | `easy` / `normal` / `hard` / `erect` / `nightmare` |
| `KNF_VARIATION` | `default` / `erect` / `pico` / `bf` |
| `KNF_AUTOPLAY` | `1` = botplay |
| `KNF_DEBUG_TEXTURES` | `1` = draw raw note-skin bitmaps for inspection |
| `KNF_DEBUG_FRAMES` | `1` = per-frame gameplay logging |
| `KNF_DEBUG_CHAR` | `1` = character loading diagnostics |
| `KNF_DEBUG_AVSYNC` | `1` = measure the audio-vs-chart clock offset |
| `KNF_DEBUG_INPUT` | `1` = log every key press the game receives |
| KNF_DEBUG_CLOCK | 1 = print wall-clock vs audio-clock drift every 500 ms |
| KNF_ONLY | dad / gf / f = show one character at a time |

---

## Layout

```
src/main/kotlin/nightfunkin/
├── Main.kt                 entry point, scene registration
├── core/
│   ├── Constants.kt        every FNF constant, ported 1:1
│   ├── KnfFiles.kt         asset root resolution + PNG/audio loading
│   ├── KnfInput.kt         key bindings and edge detection
│   ├── NoteStyle.kt        note-skin resolution (funkin/pixel/fallback)
│   └── TextureAtlas.kt     Starling XML atlas parser
├── timing/
│   └── Conductor.kt        the rhythm clock
├── audio/
│   └── MusicPlayer.kt      MusicPlayer + SfxPlayer (KorGE AudioChannel)
├── data/
│   ├── SongModels.kt       FNF 0.8.6 metadata/chart JSON schema
│   └── SongRegistry.kt     song discovery and difficulty resolution
├── play/
│   ├── PlayScene.kt        the game loop
│   ├── Strumline.kt        lane: receptors, notes, splashes
│   ├── NoteSprite.kt       note head + sustain trail
│   ├── Scoring.kt          PBOT1 judgement and scoring
│   ├── Hud.kt              health bar, score, combo, popups
│   └── GameplayCamera.kt   follow, zoom, beat bop
└── ui/
    ├── TitleScene.kt
    └── SongSelectScene.kt
```

---

## Porting notes

Values are copied verbatim from FNF 0.8.6 so charts feel identical:

```
beatLengthMs = (60 / bpm) * 1000 * (4 / timeSignatureDenominator)
stepLengthMs = beatLengthMs / 4
noteY        = 0.45 * (conductorTimeWithDelta - noteStrumTime) * scrollSpeed * (downscroll ? +1 : -1)

STRUMLINE_SIZE = 104      NOTE_SPACING = 112      INITIAL_OFFSET = -0.275 * 104
HIT_WINDOW_MS  = ±160     PIXELS_PER_MS = 0.45

judgement (PBOT1): sick ≤45 ms, good ≤90, bad ≤135, shit ≤160, else miss
score     (PBOT1): 500 at ≤5 ms, −100 on miss, sigmoid(0.08 * (|err| − 54.99)) in between
health: sick +1.5%, good +0.75%, bad 0, shit −1%, miss −4%, hold +6%/s
camera bop: zoom × 1.015 every 4 steps, exponential decay pow(0.95, dt*60)
```

### Two engine workarounds worth knowing

1. **Indexed PNGs.** KorGE's Kotlin PNG decoder does not handle colour type 3.
   FNF exports `notes.png`, `NOTE_hold_assets.png` and the hold covers as *palette*
   PNGs, which decode to solid black. `KnfFiles` sniffs the PNG header and routes
   those through `javax.imageio.ImageIO` instead.

2. **Ogg Vorbis.** KorGE's JVM audio backend decodes MP3 and WAV only. Every FNF
   sound ships as both `.ogg` and `.mp3`, so KNF probes MP3 first and compensates
   for the ~12 ms MP3 decoder latency with `Constants.MP3_DELAY_MS` — the same
   constant FNF uses.

3. **Do not ease onto the audio clock.** FNF's `PlayState` pulls the Conductor
   toward `FlxSound.time` exponentially, because Lime reports that position
   coarsely. Measuring it in KorGE showed a permanent **~25 ms lag** — notes
   visibly trailing the music. KorGE's `AudioChannel.current` is precise, so KNF
   snaps straight onto the audio clock and keeps a user-adjustable global offset
   instead.

4. **Premultiplied alpha.** Bitmaps decoded through ImageIO must be
   premultiplied by hand, otherwise KorGE logs `Trying to upload a
   non-premultiplied bitmap` and edges render wrong.

5. **Channel order.** `Bitmap32` stores its pixels as `0xAABBGGRR`, but ImageIO's
   `getRGB` hands back `0xAARRGGBB`. Packing the ARGB value straight in swaps red
   and blue, which turned the cyan down-arrow yellow and the red right-arrow
   blue on every palette PNG (all note skins, the hold strip, the pixel skins).

6. **Sprite anchors.** Flixel draws a sprite centred on its origin
   (`centerOffsets()` + `centerOrigin()`). Anchoring the note head and the
   receptors top-left instead shifts every arrow half a sprite off its receptor.

7. **`flipX` sign.** Mirroring a character flips `scaleX` to `-1` *and* moves the
   container origin to the sprite's right edge. Both the camera focus point and
   the bounds have to follow the sign, or the camera drifts a full character
   width off after every `FocusCamera` event.

### Porting notes for characters

Character sheets are Adobe Animate "Better TA Extension" exports, and the
mapping is two levels deep:

```
Animation.json → SD.S[i]        symbol i, named e.g. "Dad idle dance"
               → SI.SN          references a symbol by that name
               → SI.FF          local frame of the symbol
               → SD.S[i].TL     that frame maps onto an atlas sprite index
spritemap1.json → ATLAS.SPRITES[j]  sprite "j" at (x, y, w, h, rotated)
```

Poses are then placed with `TRP + MX[4..5]` (translation) and `MX[0..3]`
(scale/rotation). Since FNF's authoring origin differs per character, KNF
measures the idle pose's bounding box and normalises: feet on the position, and
everyone scaled to a common height.

---

## Credits

Game design, data format, assets and the reference implementation in
`Funkin-0.8.6-Source/` are the work of the Friday Night Funkin' crew (PhantomArcade,
ninjamuffin99, Kawai Sprite and many more). This project is an independent
reimplementation for study and experimentation.
