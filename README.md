# Satisfying Sand

An Android game that turns any picture into sand, then lets you drain it grain by grain.

1. **Pick a picture**: take one with the camera, choose one from the gallery, share one into the app
   from any other app, or pick from the built-in library (original illustrations drawn in code).
2. **It becomes sand**: the picture is centre-cropped and pixelated into a coarse grid (each cell is
   the area average of its pixels, in linear light), and the colours are bucketed into a small
   palette with k-means in Oklab space. The sand itself always has the same fine resolution
   (108 grains across): each picture pixel becomes a block of grains in its colour, each grain
   with its own slight shade, drawn as a little bead.
3. **Drain it**: below the picture is a tray of cells holding *every* box the picture will need,
   packed into the rows below the empty top row with three gaps. Boxes are a bit smaller than a
   cell so they slip past each other. Each box has a colour and only collects grains of that colour.
   Slide boxes around to clear a way for the one you want, then bring it up to the picture: it pulls
   matching grains from anywhere within a small radius of the notch on its top edge, and they fly
   into the centre of the box. A box pops when it's full or when its colour is gone from the picture.
   The sand behaves like round grains: when grains are taken, the gap works its way up the pile
   one grain at a time, neighbours roll in from the sides, and slopes slide, so the sand settles
   and mixes rather than dropping in columns.
4. **Win** when the picture is completely empty. You get the restored picture, grain count, moves and time.

Difficulty (Coarse / Medium / Fine) sets how detailed the picture is (12, 18 or 27 picture pixels
across) and how big the puzzle is (a 2, 3 or 4-row tray under the top row with 13, 21 or 29 boxes).
Colours (4 / 6 / 8) sets how many colour buckets the picture is reduced to, i.e. how many box colours
there are.

Physics and feel constants (fall/roll/drift chances, pull radius and rate, box size) live at the
bottom of `SandGame.kt`.

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
