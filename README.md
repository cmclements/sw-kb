# Satisfying Sand

An Android game that turns any picture into sand, then lets you drain it grain by grain.

1. **Pick a picture**: take one with the camera, choose one from the gallery, share one into the app
   from any other app, or pick from the built-in library (original illustrations drawn in code).
2. **It becomes sand**: the picture is centre-cropped, pixelated into a grid (each cell is the
   area average of its pixels, in linear light) and the colours are bucketed into a small palette
   with k-means in Oklab space. Each grain gets a tiny brightness jitter so it reads as sand.
3. **Drain it**: below the picture is a 6-wide grid of boxes. Drag a box up to the base of the
   picture and it sucks in every grain within one box length above it. The box shows the grains
   stacking up inside it plus a fill meter. At 100% it pops (flash, shockwave ring, grain burst,
   haptic + a rising pentatonic "pop"). The sand above falls down to fill the gap, and new
   empty boxes appear at the bottom while more are needed.
4. **Win** when the picture is completely empty. You get the restored picture, grain count, moves and time.

Grain size (Coarse / Medium / Fine) sets the grid resolution: 24, 36 or 48 grains wide; each box is
1/6 of the width and holds exactly one box-area of grains.

## Project layout

```
app/src/main/java/app/satisfyingsand/
  game/Pixelator.kt   crop, area-average, Oklab k-means bucketing (pure Kotlin)
  game/SandGame.kt    falling sand, boxes, path-finding, suction, pops, spawning, win (pure Kotlin)
  ui/MainActivity.kt  menu, camera/gallery/share intake, grain-size picker
  ui/GameView.kt      rendering, drag input, effects, win screen
  ui/BuiltInImages.kt procedural picture library
  ui/Sfx.kt           synthesized sound effects (no audio assets)
app/src/test/...      JVM unit tests, including a bot that plays full games to a win
```

No third-party dependencies at runtime. minSdk 28, targetSdk 35.

## Build

Needs JDK 17+ and the Android SDK (platform 35). Point `local.properties` at your SDK
(`sdk.dir=/path/to/Android/sdk`) or open the project in Android Studio.

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
