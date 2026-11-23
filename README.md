# Satisfying Sand

An Android game that turns any picture into sand, then lets you drain it grain by grain.

1. **Pick a picture**: take one with the camera, choose one from the gallery, share one into the app
   from any other app, or pick from the built-in library (original illustrations drawn in code).
2. **It becomes sand**: the picture is centre-cropped, pixelated into a grid (each cell is the
   area average of its pixels, in linear light) and the colours are bucketed into a small palette
   with k-means in Oklab space. Each grain gets a tiny brightness jitter so it reads as sand.
3. **Drain it**: below the picture is a tray holding *every* box the picture will need, packed into
   the rows below the top one with only a couple of gaps. Each box has a colour and only collects
   grains of that colour. Slide boxes around (they can't overlap) to clear a way for the one you
   want, then bring it up to the picture: it pulls matching grains from anywhere within a small
   radius (0.9 box lengths) of the notch on its top edge, and they fly into the centre of the
   box. Falling sand drifts and mixes as it settles into the holes. A box pops when it's full or
   when its colour is gone from the picture (flash, ring, grain burst, haptic, rising "pop").
4. **Win** when the picture is completely empty. You get the restored picture, grain count, moves and time.

Grain size (Coarse / Medium / Fine) sets the grid resolution: 24, 36 or 48 grains wide; each box is
1/6 of the width. The picture is split into about 16 boxes in total, each colour's sand shared
evenly between its boxes. Colours (4 / 6 / 8) sets how many colour
buckets the picture is reduced to, i.e. how many box colours there are.

## Project layout

```
app/src/main/java/app/satisfyingsand/
  game/Pixelator.kt   crop, area-average, Oklab k-means bucketing (pure Kotlin)
  game/SandGame.kt    falling sand, free-moving boxes, colour-matched radius suction, pops, spawning, win (pure Kotlin)
  ui/MainActivity.kt  menu, camera/gallery/share intake, grain-size picker
  ui/GameView.kt      rendering, drag input, effects, win screen
  ui/BuiltInImages.kt procedural picture library
  ui/Sfx.kt           synthesized sound effects (no audio assets)
app/src/test/...      JVM unit tests, including a sliding-puzzle bot that plays full games to a win
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
