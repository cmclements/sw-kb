# Satisfying Sand

An Android game that turns any picture into sand, then lets you drain it grain by grain.

1. **Pick a picture**: take one with the camera, choose one from the gallery, share one into the app
   from any other app, or pick from the built-in library (original illustrations drawn in code).
2. **It becomes sand**: the picture is center-cropped and pixelated into a coarse grid (each cell is
   the area average of its pixels, in linear light), and the colors are bucketed into a small
   palette with k-means in Oklab space. The sand itself always has the same fine resolution
   (120 grains across): each picture pixel becomes a block of grains in its color, each grain
   with its own slight shade, drawn as a little bead.
3. **Drain it**: below the picture is a tray of cells holding *every* box the picture will need,
   packed into the rows below the empty top row with three gaps. Boxes are a bit smaller than a
   cell so they slip past each other. Each box has a color and only collects grains of that color.
   Slide boxes around to clear a way for the one you want, then bring it up to the picture: it pulls
   matching grains from anywhere within a small radius of the notch on its top edge, and they fly
   into the center of the box. A box pops when it's full or when its color is gone from the picture.
   The sand behaves like real dry sand: when grains are taken, the gap works its way up the pile
   one grain at a time and neighbors roll in from the sides. Grains carry sideways momentum, so
   sand avalanches down slopes in streams and runs out to a ~32 degree angle of repose, washing the
   picture out as it goes.
4. **Win** when the picture is completely empty. You get the restored picture, grain count, moves and time.

Difficulty (Coarse / Medium / Fine) sets how big a block of sand each picture pixel becomes:
flat 10x10-grain blocks, 4x4 blocks, or a single grain. Medium and Fine use Floyd-Steinberg
dithering, so gradients and detail become a fine mix of the palette colors and every grain (or
block) carries its own pixel's detail. Finer pictures scatter colors through the sand, so each box
has to hunt harder for its color.

Colors (4 / 8 / 16) sets how many color buckets the picture is reduced to, and the tray scales to
fit the boxes needed: 6, 8 or 12 cells across (4 rows deep, top row empty), holding 15, 21 or 33
boxes with three gaps. More colors means more, smaller boxes in a tighter grid.

Physics and feel constants (fall/roll/drift chances, pull radius and rate, box size) live at the
bottom of `SandGame.kt`.

## Project layout

```
app/src/main/java/app/satisfyingsand/
  game/Pixelator.kt   crop, area-average, Oklab k-means bucketing (pure Kotlin)
  game/SandGame.kt    falling sand, free-moving boxes, color-matched radius suction, pops, win (pure Kotlin)
  ui/MainActivity.kt  menu, camera/gallery/share intake, difficulty and color pickers
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
