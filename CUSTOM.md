# This branch

Personal build of [Aerial](https://github.com/pengchujin/aerial-tv) for one household's TV.
Not meant for a pull request: it is our own line of changes, kept on top of `main` upstream.

| | |
|---|---|
| Branch | `custom/weather-title` (fork: `caolei-miracle/aerial-tv`) |
| Baseline | upstream `a07a6ae` — Aerial 1.67, versionCode 81 |
| Version name | `1.67-weather` |
| Signing key | `local/aerial-custom.jks`, alias `aerial`, SHA-256 `3459af817ccbfed38b10eff9923662d5a3d9c889ac4f509d19c465a0763e0558` |

## What it adds

1. **More weather under the clock.** Greeting and lunar date, the weather, and the next three
   days in small type — all directly on the wallpaper, with no plate of their own.
2. **A day page.** Control Center → Weather: today, six more days, humidity, wind, apparent
   temperature and China's AQI (Open-Meteo, no key). Bilingual by system locale.
3. **The focused app's name on the glass plate.** The plate grows upward by a name band and the
   name sits at its top left, as tvOS 26 has it. The band is capped by where the weather line
   really ends, so the two never meet; under 28 dp of room the name is left off instead.
4. **Build for mainland China**: Aliyun/Tencent mirrors ahead of Google and Maven Central.

## How upstream updates are taken

```sh
git fetch upstream
git rebase upstream/main          # conflicts, if any, in HomeScreen / HomeChrome / Weather
./gradlew assembleStandardRelease
adb install -r app/build/outputs/apk/standard/release/app-standard-release.apk
git tag -a v1.6x-weather.N -m "Aerial 1.6x + custom" && git push origin --tags
```

`main` here is a mirror of upstream and never carries our code — only a fast-forward from
GitHub's *Sync fork*. Keep `versionName`'s suffix in step with upstream's version on each rebase.

`local/` (keystore, `signing.properties`, `local.properties`) is gitignored and must be backed up
elsewhere: lose the key and every later build needs an uninstall, which wipes the TV's settings.
