# Satisfying Sand

An Android game that turns any picture into sand, then lets you drain it grain by grain.

1. **Pick a picture**: take one with the camera (full resolution, turned upright from its EXIF tag), choose one from the gallery, share one into the app
   from any other app, or pick from the built-in library (original illustrations drawn in code).
2. **It becomes sand**: the picture is center-cropped and pixelated into a coarse grid (each cell is
   the area average of its pixels, in linear light), and the colors are bucketed into a small
   palette with k-means in Oklab space. The sand itself always has the same fine resolution
   (200 grains across, close to individual pixels): each picture pixel becomes a block of grains in its color, each grain
   with its own slight shade, drawn as a little bead.
3. **Drain it**: below the picture is a tray of cells holding *every* box the picture will need,
   packed into the rows below the empty top row with three gaps. Boxes are a bit smaller than a
   cell so they slip past each other. Each box has a color and only collects grains of that color.
   Slide boxes around to clear a way for the one you want, then bring it up to the picture: it pulls
   matching grains from anywhere within a small radius of the notch on its top edge, and they fly
   into the center of the box. A box pops when it's full or when its color is gone from the picture.
   The sand behaves like real dry sand: grains fall under gravity, starting from rest when their
   support goes and accelerating, so when sand is taken from the bottom the gap climbs the pile at
   a natural pace and the top only falls once it gets there. Falling sand flows as a dense stream,
   grains roll into gaps from the sides, and slopes avalanche to a ~32 degree angle of repose,
   washing the picture out as they go.
4. **Win** when the picture is completely empty. You get the restored picture, grain count, moves and time.

After picking a picture you choose a level from a 3x3 grid of difficulty (rows) by colors (columns).
For library pictures, completed levels are remembered with a checkmark and best time, and each
library card shows how many of its 9 levels are done.

Difficulty (Coarse / Medium / Fine) sets how big a block of sand each picture pixel becomes:
flat 10x10-grain blocks, 4x4 blocks, or a single grain. Medium and Fine use Floyd-Steinberg
dithering, so gradients and detail become a fine mix of the palette colors and every grain (or
block) carries its own pixel's detail. Finer pictures scatter colors through the sand, so each box
has to hunt harder for its color.

Colors (4 / 8 / 16) sets how many color buckets the picture is reduced to, and the tray scales to
fit the boxes needed: 6, 8 or 12 cells across (4 rows deep, top row empty), holding 15, 21 or 33
boxes with three gaps. More colors means more, smaller boxes in a tighter grid.

Sound: a soft sand-friction hiss follows how much sand is flowing, and each box pulling its color
rings a very quiet xylophone note (one pentatonic note per color, darker colors lower). Settings
(gear on the menu and in-game) has volume sliders for sound effects and background music; music is
saved for when it's added.

Physics and feel constants (fall/roll/drift chances, pull radius and rate, box size) live at the
bottom of `SandGame.kt`.

## Project layout

```
app/src/main/java/app/satisfyingsand/
  game/Pixelator.kt   crop, area-average, Oklab k-means bucketing (pure Kotlin)
  game/SandGame.kt    falling sand, free-moving boxes, color-matched radius suction, pops, win (pure Kotlin)
  ui/MainActivity.kt  menu, camera/gallery/share intake, 3x3 level select
  ui/Progress.kt      saved completions and best times for library pictures
  ui/Settings.kt      saved volume settings
  ui/CaptureProvider.kt  private one-file provider the camera app writes full-size photos into
  ui/GameView.kt      rendering, drag input, effects, win screen
  ui/BuiltInImages.kt procedural picture library
  ui/Sfx.kt           synthesized sound effects (no audio assets)
app/src/test/...      JVM unit tests, including a sliding-puzzle bot that plays full games to a win,
                      and Robolectric screen tests that render the menu and level select to build/screens
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

## Accounts, leaderboards and monetization

Code lives in `app/src/main/java/app/satisfyingsand/play/`:

| File | What it does |
|---|---|
| `PlayGamesAccount.kt` | Google Play Games v2: automatic sign-in at launch, a sign-in button for players who declined, score submission, leaderboard screens |
| `Leaderboards.kt` | Leaderboard rules (below) and the ID lookup |
| `Store.kt` | Play Billing: the one-time **Remove ads** product; ownership cached locally and re-checked with Play on every launch (restores automatically on a new phone) |
| `Ads.kt` | AdMob full-screen ad before each game (skipped if ads are removed); Google's consent flow (UMP) runs first; never blocks play if no ad is ready |
| `SandApp.kt` | Initializes Play Games |

**Leaderboards.** Each library picture has one *Fastest time* leaderboard for its ranked level,
**Medium · 8 colors** (marked 🏆 on the level screen). A global **Total time** leaderboard ranks the
sum of a player's best ranked times across all library pictures; it's only submitted once every
picture's ranked level is done, so skipping pictures can't give a smaller total. Times earned while
signed out are uploaded on the next sign-in. That's 9 of Play Games' 70-leaderboard limit.

Until it's configured, everything degrades quietly: Play Games UI is hidden, the store button says
the store isn't available, and debug builds show Google's **test** ads.

### One-time setup (Play Console / AdMob)

1. **Play Console**: create the app (package `app.satisfyingsand`), set up a release keystore, and
   upload a signed build to an internal testing track (Billing and Play Games only work for builds
   installed from Play, by testers you add). Signing properties: `releaseStoreFile`,
   `releaseStorePassword`, `releaseKeyAlias`, `releaseKeyPassword`.
2. **Play Games Services** (Grow users > Play Games Services): create the project, add an Android
   credential with the package name and the SHA-1 of the app-signing key (and your debug key for
   testing), add testers, then create 9 leaderboards, all *Time* format, *Smaller is better*:
   one per picture (Sunset, Mountains, Heart, Rainbow, Smiley, Balloon, Mushroom, Night City) and
   Total time. Copy their IDs into `app/src/main/res/values/games-ids.xml`, and set
   `playGamesProjectId` (the numeric project ID).
3. **In-app product** (Monetize > Products > In-app products): create `remove_ads` (or set
   `removeAdsProductId`), give it a price and activate it. Add license testers to buy it for free.
4. **AdMob**: create the app and an *Interstitial* ad unit, and set `admobAppId` and
   `admobInterstitialId` (release builds only; debug builds always use Google's test IDs). In
   AdMob > Privacy & messaging, create a GDPR consent message (shown by the UMP flow).
5. **Store listing**: ads and Play Games mean you need a privacy policy URL, the Data safety form
   (advertising ID, gameplay data), and the "Contains ads" declaration.

Put the properties in `~/.gradle/gradle.properties` (keeps keys out of git), e.g.:

```properties
playGamesProjectId=123456789012
admobAppId=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
admobInterstitialId=ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ
releaseStoreFile=/path/to/release.jks
releaseStorePassword=...
releaseKeyAlias=...
releaseKeyPassword=...
```

```bash
./gradlew bundleRelease
```
