<img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/iOS-music/main/icon-512.png" width="96" alt="Glass Player icon">

# Glass Player

A frosted-glass music player for Android. Play the songs already on your phone inside a liquid
glass card that floats over animated colour blobs — with spring physics on every tap, a live
colour shift tied to the current track, and a playlist that slides with the album art.

The whole interface is one self-contained HTML file rendered in a full-screen Android WebView,
so what you see here is exactly what runs on the device.

**[Download the APK](https://github.com/technicalnihilist-lgtm/iOS-music/releases/latest)** · Requires Android 7.0 (API 24) or newer

## Screenshots

| Now playing | Playlist | Settings |
|:---:|:---:|:---:|
| <img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/iOS-music/main/screenshots/01-now-playing.png" width="250"> | <img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/iOS-music/main/screenshots/02-playlist.png" width="250"> | <img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/iOS-music/main/screenshots/03-glass-options.png" width="250"> |

**4D depth** — the card tilts with the device while the layers drift at different rates:

<img src="https://raw.githubusercontent.com/technicalnihilist-lgtm/iOS-music/main/screenshots/04-depth.png" width="250">

## Features

- **Liquid glass card** — layered translucency, 28px backdrop blur, saturate boost, inset
  highlights and a diagonal sheen, all sitting on three drifting colour blobs.
- **Colour follows the song** — every track carries a hue; the album art, the blob field and the
  glass tint shift together, and the hue is adjustable live from the options sheet.
- **Real audio** — pick any audio file from your phone with the system file picker. Nothing is
  uploaded anywhere; playback is local.
- **Spring animations** — the player pops in from the bottom, collapses into a floating bubble on
  close, and the album art wipes sideways with a blur when you change track.
- **Playlist** — tap a row to jump to a track, the current row shows an animated equaliser.
- **4D depth** — the card behaves like a physical object. It tilts with the phone's gyroscope
  (or with your finger on the artwork), the art, title, transport and playlist slide at different
  rates, the drop shadow swings to the opposite side, and a specular highlight travels across the
  glass as you move. It also sways gently on its own when you leave it alone.
- **A settings panel for all of it** — depth, tilt range, parallax, light and shadow are each
  adjustable with live preview, alongside a Motion switch (Auto / Touch / Off) and an idle-drift
  toggle. Transparency, blur and colour shift live there too, and every setting is remembered
  across launches.
- **Light and dark** — follows the system theme, and the dark theme is the signature look.

## Install

1. Download `GlassPlayer.apk` from the [releases page](https://github.com/technicalnihilist-lgtm/iOS-music/releases/latest).
2. On your phone, open the file and allow "install from unknown sources" for your browser or file manager.
3. Launch **Glass Player**, tap **Add songs** and pick audio files from your device.

The APK is signed with a self-signed debug key, so Play Protect may ask you to confirm the install.

## Project layout

```
source/index.html                 the entire player UI (HTML + CSS + JS)
android/app/src/main/assets/      the same file, shipped inside the APK
android/app/src/main/java/         MainActivity: full-screen WebView host
android/app/src/main/res/          manifest resources, layout, launcher icon
android/build.sh                   builds and signs the APK without Gradle
```

## Build it yourself

You need JDK 17 and the Android SDK (platform 34 + build-tools 34.0.0). No Gradle required —
the build script drives `aapt2`, `javac`, `d8`, `zipalign` and `apksigner` directly.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash android/build.sh
# -> android/app/build/GlassPlayer.apk
```

To change the UI, edit `source/index.html`, copy it to
`android/app/src/main/assets/index.html`, and rebuild.

## Changelog

### 3.1 — a rotation can't leave the card stuck mid-tilt

- `resize` and `orientationchange` dropped the cached artwork box but left the tilt target where the last `touchmove` put it, so a rotation that cancelled an active touch — and so never delivered the `touchend` that springs the card back — left the card holding the pre-rotation angle with no finger on it; both layout events now settle it flat, exactly as lifting the finger does, while scrolling still only drops the box and a gyro-driven tilt is left alone.

### 3.0 — a fast second tap can't stop the track it started

- Tapping a playlist row twice in quick succession ran a second load for the track already on deck, which reloaded the source and queued a pause — so the row stopped the song it had just started; a repeat request for the current track is now a no-op and a repeat tap in the same burst is absorbed, while a deliberate later tap still pauses and resumes.

### 2.9 — a broken tilt reading can't freeze the depth

- The gyroscope handler only rejected a `null` `beta`/`gamma`, so a reading that was not a number at all reached the tilt spring, and because `NaN` survives the smoothing the depth, glow and parallax stayed dead for the rest of the session even after the sensor recovered; both angles must now be finite, so a partial or broken event is ignored and the next real one still lands.

### 2.8 — the seek bar stays inside the track

- The bar was handed `currentTime / duration * 100` unchecked, so a stream running past its header duration (VBR MP3, some WAVs) pushed the fill past the end of the track and left the value the slider believed it held out of step with the value it actually held; the position and the time it converts to are now clamped to the track's own range.

### 2.7 — track hues stay inside 0–359

- The golden-angle hue was rounded before being stored, and for the 611th, 1221st and 1831st track added the rounding landed on exactly 360, a hue outside the 0–359 range the rest of the page assumes; the rounded value is now wrapped, so the colour-shift readout says 0° instead of 360° for that track while the colour itself — identical at 0 and 360 — does not change.

### 2.6 — a cancelled picker is a no-op

- Dismissing the system file picker without choosing anything could hand the page `files === null` (the Android WebView reports a back-out that way), and spreading it threw an uncaught `TypeError` on every dismissal; the handler now reads the list defensively, so a cancel in either shape leaves the playlist, the current track and the seek bar untouched.
### 2.5 — a run of bad files stops

- The skip-unplayable path advanced through every remaining track while the failure count stayed below the playlist length, so a playlist full of unplayable files walked the whole list; the chain now stops after three consecutive failures, keeps the same on-screen message and reports the count through the off-screen status region.

### 2.4 — a refused play says so

- Every `au.play()` rejection was swallowed by `.catch(()=>{})`, so a track the platform refused to start looked like a dead button; the rejection is now classified (autoplay policy vs unplayable file) and surfaced through a `data-blocked` state and an off-screen status message, with the stale `playing` state dropped when nothing is actually playing — nothing on screen moves.

### 2.3 — an unknown length says so

- `loadedmetadata` and `durationchange` formatted `au.duration` unconditionally, and `fmt()` turns the `Infinity` some streams report (or the `NaN` that arrives before metadata) into `0:00` — a file with an unknown length read as a zero-length one. Both handlers now share `showDur()`, which leaves the `--:--` marker the player already uses for "not known yet" whenever the duration is not finite and positive, and clears the cached value so a real duration arriving later still lands. Nothing else moves: every element's geometry and computed style is identical, and the times row is pixel-identical.

### 2.2 — the player looks its nodes up once

- `load()` re-queried nine DOM nodes by id on every track change, skip and playlist tap; they are now resolved once at startup into a single element map, taking a `load()` call from ten DOM lookups to one with no change to anything on screen.

### 2.1 — depth writes only when the depth moves

- `paint()` re-wrote eight CSS custom properties on the root element on every frame of the tilt spring, even when a property's formatted value had not changed since the last frame. Each one now remembers what it last wrote and skips the write when the new value is identical: twenty frames in an unchanged state perform no DOM writes at all, down from 160, while the values applied to the page are byte-for-byte the same as before.

### 2.0 — the backdrop pauses when nobody is looking

- The three blurred blobs behind the glass kept their 14-second float loop compositing while the player was collapsed to the bubble and while the app was in the background, where none of it is on screen. Both states now set `animation-play-state: paused` on the blob field, so the loop freezes in place rather than disappearing — the frame you see is identical, and the collapse animation, reopening and returning to the app all behave exactly as before.

### 1.9 — settings writes coalesced

- A slider fires `input` continuously during a drag, and every one of those events serialised the whole settings object and wrote it to `localStorage` synchronously on the main thread. Slider writes are now coalesced into at most one every 200 ms, with a pending write flushed on `pagehide` and when the page is hidden, so nothing is lost. A 12-event drag now performs one write instead of twelve; the Motion switch, the drift toggle and *Reset defaults* still write immediately.

### 1.8 — one box read per tilt gesture

- `touchTilt()` read the artwork's `getBoundingClientRect()` on every `touchmove`, forcing a synchronous layout on each frame of the drag; the box is now measured once at `touchstart` and reused for the rest of the gesture, dropping the layout reads per gesture from one per move to one.

### 1.7 — animation restart without a forced reflow

- `restart()` re-triggered every CSS animation by reading `offsetWidth`, which forces a synchronous layout on each tap. It now flushes styles only (`getComputedStyle(el).animationName`) before re-adding the class, so the engine still sees the removal and the animation restarts exactly as before, minus the layout. A `requestAnimationFrame` toggle was rejected: a single rAF runs before the frame's style recalc so the animation would not restart, and rAF is paused while the page is hidden, which would drop the class during background playback.

### 1.6 — seek bar write budget

- `timeupdate` wrote four DOM values on every audio tick even when nothing had changed; it now skips the write when the rounded value is unchanged, cutting DOM writes per tick from about three to one with identical output.

### 1.5 — playlist rendering

- The playlist rebuilt its entire markup on every track change. It now reuses the existing rows
  and only moves the highlight, so switching tracks no longer recreates the list, drops scroll
  position or re-runs the equaliser animation from scratch. Adding songs still rebuilds.
- Repository housekeeping: the packaged page (`android/app/src/main/assets/index.html`) and the
  manifest had drifted out of sync with `source/index.html`; both are now identical and the version
  in the manifest matches the released APK again.
- The signing key is now committed, so every future build stays installable over the previous one.

### 1.4 — touch fix

- **Taps no longer miss.** The 3D tilt was applied through a `perspective` on `<body>`, which turned
  every fixed-position element — the settings sheet, the dimming scrim and the floating bubble —
  into a 3D-transformed containing block. Hit-testing against those was unreliable, which is why
  the sheet sometimes ignored taps. The perspective now lives inside the card's own transform, so
  nothing outside it is affected.
- **Tap targets stopped moving.** The artwork, title and background still parallax, but the close
  and options chips, the seek bar, the transport buttons and the playlist rows no longer shift
  under your finger while the card tilts.
- **Bigger hit areas.** Previous/next were 34px buttons; every control is now at least 48px, the
  minimum comfortable touch target.
- **No more 300ms tap delay** — `touch-action: manipulation` on every button, row and chip.
- **The file picker is reliable.** The hidden `<input type=file>` used the `hidden` attribute,
  which some WebViews refuse to open programmatically; it is now visually hidden but still a real
  element.
- **The seek bar can no longer get stuck.** If a touch ended outside the slider, the drag flag
  stayed set and progress stopped updating; the flag is now released from the window as well.
- Flattened the 3D layers (`preserve-3d` and `will-change` removed) for stabler hit-testing in the
  Android WebView.

### 1.3 — settings release

- **A full settings panel** replaces the three-slider options sheet: Glass (transparency, blur,
  colour shift) and 4D depth (depth, tilt, parallax, light, shadow), each with its own slider and
  live preview.
- **Motion switch** — *Auto* uses the gyroscope when the device has one, *Touch* ignores sensors
  and tilts only while you drag the artwork, *Off* disables motion entirely.
- **Idle drift** — the card sways slowly on its own, so the depth is visible even when the phone is
  lying still. Toggleable, and it runs as a CSS animation rather than a JS loop, so it costs
  nothing to leave on.
- **Settings persist** — everything is saved to local storage and restored on the next launch.
  *Reset defaults* puts it all back.
- The settings sheet got a darker glass tint so the labels stay readable over a bright card.

### 1.2 — depth release

- **4D depth effect.** The card is a 3D stage: it rotates up to 8° / 11° on two axes inside a
  1400px perspective, driven by the gyroscope, by the mouse on desktop, or by dragging a finger
  across the artwork.
- **Parallax layers.** Artwork, title, seek bar, transport, playlist and the background blobs each
  travel a different distance, so the card reads as stacked panes. The blobs move against the tilt.
- **Light and shadow react.** A specular highlight tracks the tilt across the glass and the drop
  shadow swings to the opposite side and lengthens.
- Motion is smoothed in a single `requestAnimationFrame` loop that stops as soon as the tilt
  settles, and the effect is disabled when the system asks for reduced motion.

### 1.1 — bug fix release

- **Adding songs works.** The WebView never had a file chooser handler, so tapping *Add songs* did
  nothing on a device. `MainActivity` now implements `onShowFileChooser` and returns every file you
  pick, including multi-select.
- **Hardware back button** now closes the options sheet, then collapses the player to its bubble,
  and only then leaves the app — instead of quitting from anywhere.
- **No more art flicker on track change.** Swapping the audio source fires a `pause` event, which
  was shrinking the album art mid-transition; those events are now ignored while a track loads.
- **Reopening the player mid-collapse** no longer hides it again a moment later — the pending
  close timer is cancelled instead of racing the open animation.
- **Same for the options sheet**: closing and reopening it quickly used to leave it invisible.
- **Fast skipping no longer shows the wrong song** — the delayed title swap is discarded when a
  newer track takes over.
- **The seek bar no longer fights your thumb** — progress updates pause while you drag.
- **Picking the same files twice no longer duplicates them**, and picking files that are all
  already in the list no longer restarts playback from the top.
- **Re-picking a file that was already chosen** works (the input is reset after each pick).
- **Unplayable files are skipped** instead of silently doing nothing; if every file fails the
  artist line says so.
- **Lock-screen metadata** (title, artist) is now published through MediaSession, with play,
  pause, next and previous handled from the notification.
- Track hues are now spread with a golden-angle step instead of random values, so two songs rarely
  end up looking identical.
- Duration shows `--:--` until the file's length is known instead of a misleading `0:00`.

### 1.0

- First release.

## Notes

- Package name: `com.glassplayer.app` · version 1.6 · minSdk 24 · targetSdk 34
- The app requests no permissions and has no network access; audio is read only through the
  system file picker you invoke yourself.
- `source/index.html` also runs in any browser if you want to try the UI without installing.

---

## ⭐ Like this work? Give it a star

If you like this app and our work, please **star this repository** ⭐ and **[follow technicalnihilist-lgtm on GitHub](https://github.com/technicalnihilist-lgtm)**. If our work isn't for you, that's completely fine — the choice is yours. Thank you for stopping by!

<details>
<summary><b>🌍 Same message in 32 languages (A–Z + Indian languages)</b></summary>

- **A — Arabic** (العربية): ⭐ إذا أعجبك هذا التطبيق وعملنا، فيرجى منح المستودع نجمة ومتابعتنا. وإذا لم يعجبك عملنا، فلا مشكلة على الإطلاق — القرار قرارك. شكرًا لزيارتك!
- **B — Bengali** (বাংলা): ⭐ যদি আপনার এই অ্যাপ এবং আমাদের কাজ ভালো লাগে, তবে অনুগ্রহ করে রিপোজিটরিটিকে একটি স্টার দিন এবং আমাদের ফলো করুন। আমাদের কাজ যদি আপনার পছন্দ না হয়, তাতেও কোনো সমস্যা নেই — সিদ্ধান্ত আপনার। আমাদের পেজে আসার জন্য ধন্যবাদ!
- **C — Chinese** (中文): ⭐ 如果你喜欢这个应用和我们的作品，请给仓库点个星标并关注我们。如果我们的作品不适合你，也完全没关系——选择权在你。感谢你的到访！
- **D — Dutch** (Nederlands): ⭐ Vind je deze app en ons werk leuk? Geef de repository dan een ster en volg ons. Vind je ons werk niets, dan is dat helemaal prima — de keuze is aan jou. Bedankt voor je bezoek!
- **E — English** (English): ⭐ If you like this app and our work, please give the repository a star and follow us. If our work isn't for you, that's completely fine — the choice is yours. Thank you for stopping by!
- **F — French** (Français): ⭐ Si cette application et notre travail vous plaisent, mettez une étoile au dépôt et suivez-nous. Si notre travail n'est pas pour vous, aucun souci — c'est votre choix. Merci de votre visite !
- **G — German** (Deutsch): ⭐ Wenn dir diese App und unsere Arbeit gefallen, gib dem Repository einen Stern und folge uns. Wenn unsere Arbeit nichts für dich ist, ist das völlig in Ordnung — die Wahl liegt bei dir. Danke fürs Vorbeischauen!
- **H — Hindi** (हिन्दी): ⭐ अगर आपको यह ऐप और हमारा काम पसंद है, तो कृपया रिपॉजिटरी को स्टार दें और हमें फॉलो करें। अगर हमारा काम आपके लिए नहीं है, तो भी कोई बात नहीं — यह पूरी तरह आपकी मर्ज़ी है। यहाँ आने के लिए आपका धन्यवाद!
- **I — Indonesian** (Bahasa Indonesia): ⭐ Jika kamu suka aplikasi ini dan karya kami, beri bintang pada repositori ini dan ikuti kami. Kalau karya kami bukan untukmu, tidak masalah sama sekali — itu pilihanmu. Terima kasih sudah mampir!
- **J — Japanese** (日本語): ⭐ このアプリと私たちの仕事が気に入ったら、リポジトリにスターを付けてフォローしてください。合わない場合はまったく問題ありません — 選ぶのはあなたです。お越しいただきありがとうございます！
- **K — Korean** (한국어): ⭐ 이 앱과 저희 작업이 마음에 드신다면 저장소에 스타를 눌러 주시고 팔로우해 주세요. 저희 작업이 맞지 않으셔도 전혀 괜찮습니다 — 선택은 여러분의 몫입니다. 방문해 주셔서 감사합니다!
- **L — Lithuanian** (Lietuvių): ⭐ Jei tau patinka ši programa ir mūsų darbas, duok saugyklai žvaigždutę ir sek mus. Jei mūsų darbas ne tau, viskas gerai — pasirinkimas tavo. Ačiū, kad užsukai!
- **M — Malayalam** (മലയാളം): ⭐ നിങ്ങൾക്ക് ഈ ആപ്പും ഞങ്ങളുടെ പ്രവർത്തനവും ഇഷ്ടപ്പെട്ടെങ്കിൽ, ദയവായി റെപ്പോസിറ്ററിക്ക് ഒരു സ്റ്റാർ നൽകുകയും ഞങ്ങളെ ഫോളോ ചെയ്യുകയും ചെയ്യുക. ഞങ്ങളുടെ പ്രവർത്തനം നിങ്ങൾക്ക് അനുയോജ്യമല്ലെങ്കിൽ, അതും തികച്ചും കുഴപ്പമില്ല — തീരുമാനം നിങ്ങളുടേതാണ്. ഇവിടെ വന്നതിന് നന്ദി!
- **N — Nepali** (नेपाली): ⭐ यदि तपाईंलाई यो एप र हाम्रो काम मन पर्‍यो भने, कृपया रिपोजिटरीलाई स्टार दिनुहोस् र हामीलाई फलो गर्नुहोस्। यदि हाम्रो काम तपाईंलाई मन पर्दैन भने, पनि ठीकै छ — यो तपाईंको इच्छा हो। यहाँ आउनुभएकोमा धन्यवाद!
- **O — Odia** (ଓଡ଼ିଆ): ⭐ ଯଦି ଆପଣଙ୍କୁ ଏହି ଆପ୍ ଏବଂ ଆମର କାମ ଭଲ ଲାଗୁଛି, ତେବେ ଦୟାକରି ରିପୋଜିଟୋରୀକୁ ଗୋଟିଏ ଷ୍ଟାର୍ ଦିଅନ୍ତୁ ଏବଂ ଆମକୁ ଫଲୋ କରନ୍ତୁ। ଯଦି ଆମର କାମ ଆପଣଙ୍କ ପାଇଁ ନୁହେଁ, ତେବେ କିଛି ଅସୁବିଧା ନାହିଁ — ପସନ୍ଦ ଆପଣଙ୍କର। ଆମ ପାଖକୁ ଆସିଥିବାରୁ ଧନ୍ୟବାଦ!
- **P — Polish** (Polski): ⭐ Jeśli podoba Ci się ta aplikacja i nasza praca, daj repozytorium gwiazdkę i obserwuj nas. Jeśli nasza praca nie jest dla Ciebie, to zupełnie w porządku — wybór należy do Ciebie. Dziękujemy za odwiedziny!
- **Q — Quechua** (Runa Simi): ⭐ Kay app, ñuqaykup ruwasqanchikpas gustasunki chayqa, repositorioman huk estrella churay, ñuqaykutapas qatipayku. Mana gustasunki chayqa, allinmi — qampa akllayki. Hamusqaykimanta sulpayki!
- **R — Russian** (Русский): ⭐ Если вам нравится это приложение и наша работа, поставьте звёздочку репозиторию и подпишитесь на нас. Если наша работа не для вас — это совершенно нормально, выбор за вами. Спасибо, что заглянули!
- **S — Spanish** (Español): ⭐ Si te gusta esta app y nuestro trabajo, dale una estrella al repositorio y síguenos. Si nuestro trabajo no es para ti, no pasa nada — la decisión es tuya. ¡Gracias por pasarte!
- **T — Tamil** (தமிழ்): ⭐ உங்களுக்கு இந்த ஆப்பும் எங்க வேலையும் பிடிச்சிருந்தா, தயவுசெஞ்சு இந்த ரெபாசிட்டரிக்கு ஒரு ஸ்டார் குடுத்து, எங்களை ஃபாலோ பண்ணுங்க. எங்க வேலை உங்களுக்கு செட் ஆகலைன்னாலும் பரவாயில்லை — அது உங்க விருப்பம். இங்கே வந்ததுக்கு நன்றி!
- **U — Urdu** (اردو): ⭐ اگر آپ کو یہ ایپ اور ہمارا کام پسند آئے تو براہ کرم ریپازٹری کو سٹار دیں اور ہمیں فالو کریں۔ اگر ہمارا کام آپ کے لیے نہیں ہے، تو یہ بالکل ٹھیک ہے — انتخاب آپ کا ہے۔ یہاں تشریف لانے کا شکریہ!
- **V — Vietnamese** (Tiếng Việt): ⭐ Nếu bạn thích ứng dụng này và công việc của chúng tôi, hãy tặng kho lưu trữ một ngôi sao và theo dõi chúng tôi. Nếu công việc của chúng tôi không phù hợp với bạn, điều đó hoàn toàn ổn — lựa chọn là của bạn. Cảm ơn bạn đã ghé qua!
- **W — Welsh** (Cymraeg): ⭐ Os ydych chi'n hoffi'r ap hwn a'n gwaith, rhowch seren i'r storfa a dilynwch ni. Os nad yw ein gwaith ar eich cyfer, mae hynny'n hollol iawn — chi sydd i benderfynu. Diolch am alw heibio!
- **X — Xhosa** (isiXhosa): ⭐ Ukuba uyayithanda le app nomsebenzi wethu, nika irepo inkwenkwezi kwaye usilandele. Ukuba umsebenzi wethu awukufanelanga, akukho ngxaki — lukhetho lwakho. Enkosi ngokutyelela!
- **Y — Yoruba** (Yorùbá): ⭐ Bí app yìí àti iṣẹ́ wa bá wù ọ́, jọ̀wọ́ fún àkójọpọ̀ náà ní ìràwọ̀ kí o sì tẹ̀lé wa. Bí iṣẹ́ wa kò bá yẹ ọ́, kò séwu — ìwọ ni ó yàn. Ẹ ṣé fún wíwá!
- **Z — Zulu** (isiZulu): ⭐ Uma uthanda le app nomsebenzi wethu, nikeza isitoreji inkanyezi bese usilandela. Uma umsebenzi wethu ungakufanele, akunankinga — wukukhetha kwakho. Siyabonga ngokuvakasha!

**More Indian languages**

- **Assamese** (অসমীয়া): ⭐ যদি আপুনি এই এপটো আৰু আমাৰ কাম ভাল পায়, তেন্তে অনুগ্ৰহ কৰি ৰিপজিটৰীটোক এটা ষ্টাৰ দিয়ক আৰু আমাক ফ'ল' কৰক। যদি আমাৰ কাম আপোনাৰ বাবে নহয়, তেন্তে সেয়া একেবাৰে ঠিক আছে — সিদ্ধান্তটো আপোনাৰ। আমাৰ ইয়ালৈ অহাৰ বাবে ধন্যবাদ!
- **Gujarati** (ગુજરાતી): ⭐ જો તમને આ એપ અને અમારું કામ ગમતું હોય, તો પ્લીઝ રિપોઝિટરીને સ્ટાર આપો અને અમને ફોલો કરો. જો અમારું કામ તમને ન ગમતું હોય, તો તે પણ બરાબર છે — પસંદગી તમારી છે. અહીં આવવા બદલ આભાર!
- **Kannada** (ಕನ್ನಡ): ⭐ ನಿಮಗೆ ಈ ಆ್ಯಪ್ ಮತ್ತು ನಮ್ಮ ಕೆಲಸ ಇಷ್ಟವಾದರೆ, ದಯವಿಟ್ಟು ರೆಪೊಸಿಟರಿಗೆ ಸ್ಟಾರ್ ಕೊಡಿ ಮತ್ತು ನಮ್ಮನ್ನು ಫಾಲೋ ಮಾಡಿ. ನಮ್ಮ ಕೆಲಸ ನಿಮಗೆ ಸರಿಹೊಂದದಿದ್ದರೆ, ಅದೂ ಪರವಾಗಿಲ್ಲ — ಆಯ್ಕೆ ನಿಮ್ಮದು. ಭೇಟಿ ನೀಡಿದ್ದಕ್ಕೆ ಧನ್ಯವಾದಗಳು!
- **Marathi** (मराठी): ⭐ जर तुम्हाला हे ॲप आणि आमचं काम आवडलं असेल, तर कृपया रिपॉझिटरीला स्टार द्या आणि आम्हाला फॉलो करा. जर आमचं काम तुम्हाला आवडत नसेल, तर काही हरकत नाही — ती तुमची निवड आहे. इथे आल्याबद्दल धन्यवाद!
- **Punjabi** (ਪੰਜਾਬੀ): ⭐ ਜੇ ਤੁਹਾਨੂੰ ਇਹ ਐਪ ਅਤੇ ਸਾਡਾ ਕੰਮ ਪਸੰਦ ਹੈ, ਤਾਂ ਕਿਰਪਾ ਕਰਕੇ ਰਿਪੋਜ਼ਟਰੀ ਨੂੰ ਸਟਾਰ ਦਿਓ ਅਤੇ ਸਾਨੂੰ ਫਾਲੋ ਕਰੋ। ਜੇ ਸਾਡਾ ਕੰਮ ਤੁਹਾਡੇ ਲਈ ਨਹੀਂ ਹੈ, ਤਾਂ ਕੋਈ ਗੱਲ ਨਹੀਂ — ਚੋਣ ਤੁਹਾਡੀ ਹੈ। ਸਾਡੇ ਪੇਜ 'ਤੇ ਆਉਣ ਲਈ ਧੰਨਵਾਦ!
- **Telugu** (తెలుగు): ⭐ మీకు ఈ యాప్, మా పని నచ్చితే, దయచేసి రిపోజిటరీకి ఒక స్టార్ ఇచ్చి మమ్మల్ని ఫాలో అవ్వండి. ఒకవేళ మా పని మీకు నచ్చకపోతే, పర్వాలేదు — అది మీ ఇష్టం. మమ్మల్ని సందర్శించినందుకు ధన్యవాదాలు!

</details>
