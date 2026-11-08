# Improvement backlog

One item per scheduled run, top to bottom. Every item must leave the **interface identical** —
no changes to layout, colour, spacing, type, copy, animation timing or interaction model. Only
correctness, performance, robustness, accessibility and code quality.

Rules for adding items: only add things you have actually observed to be a problem, and say what
the symptom is. Do not add "refactor everything" style entries.

## Performance and fluidity

- [x] 1. Playlist rebuilt its whole innerHTML on every track change — reuse the rows and only move
  the `cur` class. *(1.5)*
- [x] 2. `timeupdate` writes four DOM values on every audio tick (~4/s). Skip the write when the
  rounded value has not changed.
- [x] 3. `restart()` forces a synchronous reflow with `void el.offsetWidth` on every call. Batch
  the class toggle into a `requestAnimationFrame` instead. *(1.7)* — done as a style flush
  (`getComputedStyle(el).animationName`) rather than a rAF: a single rAF runs before the frame's
  style recalc, so the removal is never observed and the animation does not restart, and rAF is
  paused while the page is hidden, which would leave the class off during background playback.
- [x] 4. `touchTilt()` calls `getBoundingClientRect()` on every `touchmove`. Measure once at
  `touchstart` and reuse it for the gesture. *(1.8)* — the cached box is dropped on touch end,
  scroll, resize and rotation, so a card that moves under the finger is still measured correctly.
- [x] 5. Settings are written to `localStorage` on every slider `input` event, including during a
  drag. Debounce the write to ~200 ms. *(1.9)* — discrete toggles (motion, drift, reset) still
  write immediately, and a pending write is flushed on `pagehide` and on hide, so a drag that ends
  with the app being backgrounded still persists.
- [x] 6. The blob animation keeps running while the player is collapsed to the bubble and while the
  page is hidden. Pause it in both states. *(2.0)* — both states now drive a `paused` class on the
  blob field, which sets `animation-play-state` on the three blobs. The loop freezes in place, so
  the frame on screen is unchanged; the collapse animation itself still runs, and reopening or
  returning to the tab resumes exactly where it stopped.
- [x] 7. `paint()` writes nine CSS custom properties every frame, even when the value is unchanged.
  Compare against the previous value and skip identical writes. *(2.1)* — each of the eight
  properties `paint()` sets (the item's "nine" counts `--glow` twice; there are eight) now keeps its
  last written string and skips the write when the formatted value is identical. Twenty
  back-to-back frames in an unchanged state went from 160 `setProperty` calls to 0; a frame whose
  values really moved still writes every one that changed.
- [x] 8. `load()` re-queries the same DOM nodes on every call. Hoist the lookups into a single
  element map created once. *(2.2)* — the nine nodes `load()`/`resetSeek()` touch (`title`,
  `artist`, `art`, `.meta`, `hu`, `huV`, `seek`, `cur`, `dur`) are resolved once at startup into
  `EL`; nothing ever replaces those elements, so the references stay valid. A `load()` call now
  performs one `getElementById` (inside `renderList`) instead of ten, measured in the harness.
- [x] 9. Audit for properties that trigger layout in hot paths (width/height/top/left) and replace
  with transforms where the visual result is identical. *(2.3)* — audited with CDP layout counters
  rather than by eye. The depth loop (`paint()` on every frame of the tilt spring) and `timeupdate`
  cause no layout at all: every value they write lands on `transform`, `opacity`, `box-shadow` or a
  gradient. The one layout-triggering property left in a hot path is the playlist equaliser's
  `height` animation — 24 layout passes in 2 s while the current row is on screen, and cancelling
  just those three animations takes it to 1. It was **not** replaced: a `transform: scaleY()`
  version scales the 2px `border-radius` along with the bar, so the caps change shape at every point
  of the cycle (118–200 differing pixels per frame at 4× DPR, measured), which the identical
  interface rule forbids. Pausing it while collapsed would gain nothing either — the browser already
  drops the animation once `display: none` hides the card.

## Robustness

- [x] 10. `au.duration` can be `Infinity` or `NaN` for some files; every read site must guard it.
  *(2.3)* — `loadedmetadata` and `durationchange` ran the raw value through `fmt()`, which turns a
  non-finite duration into `0:00`, so a stream or a half-parsed file read as a zero-length track.
  Both handlers now share `showDur()`, which leaves the `--:--` marker `resetSeek()` already writes
  when the duration is not finite and positive, and clears the cached value so a real duration
  arriving later still lands. The `timeupdate`, seek-bar and previous-button read sites were already
  guarded.
- [x] 11. `au.play()` rejections are swallowed by `.catch(()=>{})`. Surface a non-intrusive state so
  the user knows playback was blocked rather than broken. *(2.4)* — every call site (transport
  button, playlist row, autoplay on load, media-session `play`) now goes through one `attemptPlay()`
  that classifies the rejection: `NotAllowedError`/`SecurityError` is the autoplay policy refusing,
  anything else is a file the platform cannot play. The state is surfaced without touching the
  interface — a `data-blocked` attribute on the app element, and a message in an off-screen
  `role="status"` region that assistive tech reads ("Playback blocked. Tap play to start." vs
  "Can't play this file."). A refused play also left the *previous* track's `playing` class behind,
  so the button showed "pause" for a track that was never running; that false state is now dropped
  when `au` really is paused, which is what makes the button show "play" — the thing that works.
  Verified: the message and flag appear on a stubbed `NotAllowedError` and clear on a successful
  play; all 107 elements keep byte-identical geometry and computed styles, and the rendered page is
  pixel-identical to the previous build.
- [x] 12. The "skip unplayable track" path can walk the whole playlist on a run of bad files. Cap
  the attempts and stop with a clear message. *(2.5)* — `au.onerror` advanced to the next track for
  as long as `fails < tracks.length`, so six unplayable files in a row meant six source swaps and
  six swap animations before anything settled, and a long playlist meant dozens. The chain is now
  capped at three consecutive failures (`MAX_SKIP`): on a six-file playlist of undecodable sources
  the old build walked all five remaining entries, the new one stops after two. The message the
  path already showed ("Can't play this file") is unchanged on screen; the count goes to the
  off-screen status region ("Stopped after 3 unplayable tracks…"), so nothing on the page moves.
  Stopping also cancels the title swap still queued from the last attempt, which used to land
  ~360 ms later and replace the message with the track's artist, and a `stopped` flag keeps the
  trailing `play()` rejection from talking over it with the generic text.
- [x] 13. A cancelled or empty file-picker result should leave the playlist untouched (verify, and
  fix if not). *(2.6)* — verified first, and the playlist side was already sound: a cancel delivered as
  an empty `FileList` and as `files === null` both left the rows, title, hue, seek bar and sheet state
  identical, with no track added and no `load()`. The defect was the null shape — `[...e.target.files]`
  threw an uncaught `TypeError` on every back-out from the system picker, which the WebView logs as a
  JS error each time the user dismisses the chooser. The handler now reads
  `e.target.files?[...e.target.files]:[]`, so a cancel is a no-op in both shapes; picking real files,
  duplicate rejection and the multi-file add path are unchanged. 17/17 harness checks pass (cancel in
  four states, null-files with no exception, pick, dedupe, track switch with row reuse, back chain,
  hit-test flat and tilted) and all 116 elements keep byte-identical geometry and computed style.
- [x] 14. Track hues from the golden-angle step can exceed 359 on long lists; clamp to 0–359.
  *(2.7)* — the hue was `Math.round((tracks.length*137.508)%360)`, and rounding lands on exactly
  360 for the 611th, 1221st and 1831st track added, so those three positions in a long list stored
  a hue outside the 0–359 domain every read site assumes. Nothing looked wrong on the Now Playing
  screen — hue 360 and hue 0 are the same angle, and `hsl(360 …)` resolves to the same colour — but
  the colour-shift slider read back clamped to its end while the label beside it read "360°", and
  the blob field's `hue-rotate` was handed 110deg instead of the equivalent −250deg. The rounded
  value is now wrapped back into range. Verified on a 614-track list: every row's `--ah` matches its
  golden-angle value and none exceeds 359 (the old build fails all four of those assertions and the
  new one passes 32/32). The rendered result is unchanged, and provably so: the 360/0 and
  −250/110 degree forms were compared through the canvas filter and are byte-identical, and the deep
  state (611 tracks, current track at the wrap) fingerprints identically to the old build except for
  the readout itself, which now says "0°".
- [x] 15. `seek` input should be clamped to `[0, duration]` before assignment. *(2.8)* — both paths
  that carry a position into the bar were unclamped. `timeupdate` computed
  `currentTime / duration * 100` and wrote it straight into `--p` and `sk.value`; a stream that runs
  past its header duration (VBR MP3s and some WAVs report exactly this) hands the bar a
  `currentTime` greater than `duration`, so a 210.5 s position on a 200 s track produced
  `--p: 105.25%`. The white fill then ran past the end of the track, and the value the input
  believed it held drifted out of step with the value it actually held — the element silently clamps
  to 100, the code kept 105.25. The `input` handler then mapped its percentage back to
  `currentTime` with no domain check either. Both ends are now clamped: the position into [0, 100]
  and the resulting time into [0, duration], with a non-finite position or duration treated as a
  no-op. Verified in the harness — the 210.5 s / 200 s state that produced `--p: 105.25%` now
  produces 100%, and in-range positions still convert to exactly the time they did before (25% of
  200 s is still 50 s, and the readout still says 0:50). All 32 checks pass, all 106 elements keep
  byte-identical geometry and computed style, and the rendered page is pixel-identical.
- [x] 16. `deviceorientation` can deliver null `beta`/`gamma`; the guard exists but confirm it also
  handles the first event having only `alpha`. *(2.9)* — confirmed first: an alpha-only event
  (`beta`/`gamma` null) was already a no-op, and a valid event after it still drives the tilt. The
  guard only tested `== null`, though, so a reading that is not a number at all walked straight
  through: `NaN` reached `tg`, the spring copied it into `tv`, `paint()` wrote `NaN` into `--mx`,
  `--my`, `--lx` and `--glow`, and because `NaN` survives the smoothing the depth and glow were
  dead for the rest of the session — a later good reading could not bring them back. Both angles
  must now be finite, so every partial or broken event is a no-op and the next real one lands.
  Verified in the harness: the old build turns all four properties into `NaN` and never recovers,
  the new one leaves them untouched and still follows a valid `beta`/`gamma`; 19/19 checks pass,
  and all 116 elements keep byte-identical geometry and computed style with a pixel-identical
  render.
- [x] 17. Rapid taps on a playlist row can call `load()` twice for the same track; make it
  idempotent. *(3.0)* — a repeat request for the track already on deck is now a no-op: it no
  longer re-assigns `au.src`, so the media does not reload, the position does not drop to 0, the
  swap animation does not replay and the pause the reload queued does not leave the transport
  showing "play" for a track that is starting. The row handler had the matching hole — it read a
  repeat tap in the same burst as a request to pause and stopped the track the first tap had just
  started — so a tap on the row whose source was assigned within the last 400 ms is absorbed
  instead of toggled. Both windows are a monotonic timestamp rather than the existing `switching`
  flag, which a fixed 700 ms timer from an earlier load can clear underneath the current one.
  Verified with real WAV sources: on the old build a second tap 60 ms after selecting a row left
  it paused (and a third tap 150 ms later also paused it), on the new one the track keeps playing,
  one `load()` runs per burst, and a deliberate later tap still pauses and resumes. 20/20 harness
  checks pass, all 140 elements keep byte-identical geometry and computed style, and the frozen
  render at 2x DPR is pixel-identical (0 of 1,507,920 pixels differ).
- [x] 18. Rotation/resize can leave the card mid-tilt; re-measure and reset the tilt target.
  *(3.1)* — `resize` and `orientationchange` dropped the cached artwork rect so the next move
  re-measured, but they left the *target* tilt where the last `touchmove` put it. A resize that
  cancels an active touch does not always deliver the `touchend`/`touchcancel` that springs the
  card back, so the card held the pre-rotation angle with no finger on it, indefinitely. Both
  layout events now run the same settle a lifted finger does — invalidate the box, zero the
  target, let the spring return the card flat — while `scroll` still only drops the box (the
  finger is still down and the next move re-aims it) and a gyro-driven tilt is left alone.
  Verified in the harness: pressing the artwork and pushing it to the corner reaches `--mx`
  0.480, and after a `resize` the old build still reads 0.480 while the new one reads 0.000;
  `orientationchange` behaves the same (0.457 → 0.000), a `touchend` still springs back, a
  gyro tilt survives a resize, and a fresh gesture after a rotation tilts again. 25/25 checks
  pass, and all 118 elements keep byte-identical geometry and computed style.

## Memory and battery

- [x] 19. Object URLs from the file picker are never revoked. Revoke a track's URL when the page is
  torn down, and on `pagehide`. *(3.2)* — an object URL keeps its blob alive until it is revoked or
  the document dies, so every file the picker handed over held its whole audio payload in memory
  whether it was playing or not: adding twenty songs carried all twenty for the rest of the
  session. Every URL the page creates now goes through one `mkUrl()` that records it in a set, and
  a `pagehide` handler revokes them all and detaches the audio element first, since a URL the
  element still holds is not released. A `pagehide` carrying `persisted` is a page entering the
  back/forward cache, which can be restored with its state intact, so that case is skipped and its
  sources stay valid. Verified in the harness: after a real `pagehide` every track's blob URL stops
  resolving in the old build and resolves in neither the new one nor a restored page; 20/20 checks
  pass, and all 106 elements keep byte-identical geometry and computed style.
- [x] 20. The `requestAnimationFrame` loop should stop when the document is hidden and restart on
  visibility. *(3.3)* — the depth spring's loop kept asking for a frame every time it ran, whether
  or not anything could be seen, and it was left holding the id of a frame the WebView never
  delivers: the Android shell pauses the WebView with the activity, so the queued callback never
  runs, while `kick()` reads a non-null `rafId` as "a frame is already queued" and declines to
  schedule another. One trip to the background therefore left the depth engine dead for the rest
  of the session — no gyroscope, no finger tilt, no idle settle — until the page was reloaded. The
  loop now cancels its frame and stops on `visibilitychange` to hidden, and on the way back picks
  the spring up again if a target moved while it was stopped. Verified in the harness: on the old
  build nine frames are requested while hidden and the loop never recovers after a dropped one
  (`--mx` frozen at 0.3598 instead of the −0.3612 its target implies, and a later gesture no
  longer tilts at all); on the new one zero frames are requested while hidden, one frame is
  cancelled, the spring reaches its target on return and still tracks a fresh gesture afterwards
  (18/18 checks, the four new ones failing on the old build). Nothing visible moves: the spring is
  left exactly where the last delivered frame put it, and all 116 elements keep byte-identical
  geometry and computed style in a frozen snapshot of both builds.
- [x] 21. Media session metadata is left behind when the player closes; clear it. *(3.4)* — the
  session was set on every track change and never cleared, and a media session outlives the document
  that created it: backing out of the player destroys the WebView, but Android keeps the notification
  and the lock-screen controls it was handed, so the last track stayed on the lock screen with a play
  button wired to a page that no longer exists and a state that still read "playing" over silence.
  `clearSession()` now nulls the metadata, sets the playback state to `none` and unbinds the four
  action handlers, on the `pagehide` that really tears the page down — the same condition that
  releases the object URLs. Nothing else moves: a page entering the back/forward cache keeps its
  session (it can come back playing) and so does a player collapsed to the bubble, which is still
  playing. Verified in the harness with a recording media-session stub — after a teardown `pagehide`
  the old build still holds the last track's metadata, `playing` and four live handlers, the new one
  holds `null`, `none` and four nulls, while a `persisted` `pagehide` and the collapse to the bubble
  leave both untouched; 29/29 checks pass (file picking, dedupe, row reuse, the back chain and the
  `elementFromPoint` hit-test flat and tilted), all 117 elements keep byte-identical geometry and
  computed style, and the frozen 2x render is pixel-identical.

## Accessibility

Track changes were silent to a screen reader. `load()` rewrites `#title` and `#artist` on every
switch, but the title block was an ordinary `<div>`: nothing about it told assistive tech that its
text had changed, so a user driving the transport from a headset or a keyboard got the new song
with no announcement at all — the off-screen `#live` status region only ever carries playback
errors. The block now carries `aria-live="polite"` with `aria-atomic="true"`, so the title and
artist are announced once, in reading order, as a single unit. The write happens inside `ui()`,
which runs after the 360 ms art wipe rather than during it, so the announcement lands on the track
that is actually on deck and never on a half-swapped title. Verified in the harness: the region
resolves to `polite`/`true` and its text is the new track after a switch (both attributes absent on
the old build); dedupe still holds at 2 → 2 tracks, playlist rows keep their node identity while
the highlight moves, settings still persist, the back chain still returns `true, true, false`, and
all twelve `elementFromPoint` hit-tests (six controls, flat and tilted) resolve to the control or
a descendant. All 117 elements keep byte-identical geometry and computed style in a frozen
snapshot of both builds, so nothing visible moved.
- [x] 22. Announce track changes with `aria-live="polite"` on the title block. *(3.5)* — the
  title block is a polite, atomic live region, so every track change is announced as "title, artist"
  once the swap has settled.
- [x] 23. Give every range input an `aria-valuetext` describing its unit. *(3.6)* — set from
  the same places the visible readout is written, so the two cannot drift apart; the blur slider
  reads in pixels and the colour shift in degrees, the rest in percent.
- [x] 24. Ensure keyboard focus is visible on buttons (currently only `:active` is styled). *(3.7)* — a
  `:focus-visible` ring (2px `var(--tx)`, 3px offset, radius inherited; 6px on the seek bar) on
  every button and range input. `:focus-visible` never matches a pointer or touch press, so the
  on-device look is unchanged: all 232 nodes keep identical geometry and computed style, flat
  and with the sheet open.
- [x] 25. Give the playlist `role="list"` and its rows `role="listitem"`. *(3.8)* — the
  container is a list and both row templates (a track row and the empty "no songs yet" row) are
  list items, so a screen reader announces the playlist as a list and counts its rows. `role`
  carries no presentation, the stylesheet matches no `[role]` selector, and all 117 elements keep
  identical geometry, so nothing visible moved.

## Code quality

- [x] 26. `synth()` builds a WAV for demo tracks that no real code path uses; confirm it is
  unreachable and remove it, or document why it stays. **Done — removed.** The only caller was
  `if(!t.src)t.src=synth(t)` in `load()`, and the only writer of `src` is the file-picker handler,
  which sets `src:mkUrl(f)` on every track it pushes; `tracks` starts empty, so no track can reach
  `load()` without a source, and nothing anywhere defines the `beat`/`n` fields `synth()` reads.
  The function and its call site are gone (892 bytes); `load()` now assigns `au.src=t.src` inside
  the same `try`, so an unreadable source still reports "Can't play this file" exactly as before.
  Behaviour and hit-test harnesses pass unchanged.
- [x] 27. Add a short architecture comment at the top of the script (state, render, audio, depth).
- [x] 28. Unify the error strings ("Can't play this file", "Skipping…") into one helper.

## Android shell (`android/app/src/main/java/.../MainActivity.java`)

- [x] 29. Request and abandon audio focus so other apps duck correctly. **Done.** The shell held no
  focus at all, so a phone call, a notification or another player simply mixed with whatever was
  coming out of the WebView. `AudioFocus.java` now holds `AUDIOFOCUS_GAIN` with media/music
  attributes for exactly as long as the page is playing - the page reports play/pause through a
  `GP.setPlaying()` bridge, because requesting on resume would silence other apps even with nothing
  loaded - and gives it back on pause, on teardown, and on a permanent or transient loss, which also
  pauses the page through its own transport button so the visible state stays in step. A duck
  request is left alone: a WebView has no per-stream volume, and moving the element's volume would
  desync the page's own slider. No layout, colour, type, copy or animation changed.

- [x] 30. Handle `onTrimMemory` by releasing the WebView's cache. **Done.** The shell ignored
  `ComponentCallbacks2` entirely, so under pressure the system killed the process while a cache
  of decoded resources sat unused. `MainActivity.onTrimMemory()` now clears the WebView cache at
  `TRIM_MEMORY_RUNNING_LOW` and above - the levels an activity actually receives, including
  `UI_HIDDEN` and `BACKGROUND` - through a `releaseWebCache()` helper that hops to the UI thread if
  it is called off it, no-ops once the view has been destroyed, and swallows a throw from a WebView
  torn down mid-call. It clears only the cache: DOM storage, the loaded page, the playlist and the
  audio element are untouched, so playback, settings and every pixel stay exactly as they were.
- [x] 31. Guard against a WebView that fails to instantiate, with a plain-text fallback screen.
  **Done.** `onCreate` inflated the layout and dereferenced `web` on the next line, so a device
  where Android System WebView is missing, disabled or mid-update threw out of `setContentView`
  (or came back with a null field) and the process died before anything was drawn. Inflation and
  the `findViewById` now sit in one `try`, the field is null-checked once, and either failure
  routes to `showWebViewFallback()`: a `TextView` on the same `#0b0b1a` background, built in code
  so it needs no second layout, naming the component to enable and then reopening the app. Every
  other `web.` call site already sat behind its own null check, so nothing else moved; the
  fallback is only reachable when the page could not have loaded anyway, and no pixel of the
  normal interface changed.
- [x] 32. Confirm the file chooser returns `null` cleanly when the user backs out.
  **Confirmed.** Both halves of the path were checked, and neither needed a change. In the shell,
  `onActivityResult` builds a result only when `resultCode == RESULT_OK` and the intent is
  non-null, so a back-out leaves `results` at `null`, hands exactly that to `onReceiveValue(...)`
  once and clears `pendingFileCallback` in the same breath; a result arriving with no callback
  pending (the activity was recreated while the chooser was up) falls through to
  `super.onActivityResult` instead of dereferencing it, and `onDestroy` already answers a
  still-pending callback with `null` before the WebView is torn down. In the page the handler
  reads `e.target.files?[...e.target.files]:[]`, so both shapes a WebView can deliver - `files
  === null` and an empty `FileList` - are the same no-op. Verified in the harness with the
  back-out delivered in four states (no songs yet, and with a three-track playlist, in each
  shape): the track list, index, title, artist, seek value and fill, duration, row count and
  sheet state were identical before and after, with no `error` or `unhandledrejection` event.
  19/19 harness checks pass (pick, dedupe, track switch with row reuse, back chain, settings
  persistence, hit-test flat and tilted) and the page itself is unchanged, so the interface is
  identical by construction.

## Robustness and accessibility

- [x] 33. A settings blob read back from storage is applied without checking it against the
  controls that produced it, so one bad value blanks the glass.
  **Symptom.** Restoring `glass.settings.v1` copied every key straight into `S`. A `glass` of
  `"abc"` reached `--glass` as `NaN`, which makes `background:rgba(255,255,255,var(--glass))`
  invalid, so the card painted with `background-color:rgba(0,0,0,0)` and `backdrop-filter:none` -
  no glass and no blur at all - while a null depth and a negative blur did the same to `--tiltk`
  and `--blur`, a parallax of `1e9` threw the layers off the card, the readouts printed `abc%` and
  `null%`, and the Motion segment showed no mode selected. Dragging the glass slider back repaired
  only `--glass`: nothing re-reads `S` after load, so every other damaged value survived the
  session.
  **Fixed.** Each key is now accepted only in the shape its own control produces - a finite number
  inside that slider's own `min`/`max`, `0`/`1` for the drift switch, one of the three motion
  modes - and anything else keeps the default, which is the only value guaranteed to work. The
  page is unchanged in every other respect: a value this page could have written is kept exactly
  as stored, so an ordinary session restores precisely what it saved. Verified by loading the page
  with a corrupt blob (every property back to its default, `background-color:
  rgba(255,255,255,0.18)`, `backdrop-filter:blur(28px) saturate(1.9)`, readouts `18%|28|60%|100%|
  100%|100%|100%`, Motion on Auto, drift on) and with a valid blob (all eight values restored
  exactly, `--glass:0.3`, `--blur:40px`, Motion on Touch, drift off), then comparing the computed
  style and box geometry of all 118 nodes against the previous page: identical. 13/13 harness
  checks pass (pick, dedupe, track switch with row reuse, settings persistence, back chain,
  hit-test flat and tilted).
- [x] 34. The seek bar has no accessible name.
  **Symptom.** `#seek` carries an `aria-valuetext` but no `aria-label` and no `<label>` of its own,
  so the accessibility tree exposes a nameless slider: TalkBack announces "slider, 1:23" with no
  indication that it is the playback position, while every settings slider gets its name from the
  `<label class="opt">` it sits in. *(4.7)* — `#seek` now carries `aria-label="Playback position"`;
  verified through the browser's accessibility tree (`Accessibility.getPartialAXTree`), which
  reported `{role:"slider", name:""}` before and `{role:"slider", name:"Playback position"}` after,
  with every other player control still named. Nothing visible changed: with animations frozen and
  `--mx`/`--my` pinned, the computed style and box geometry of all 117 nodes are byte-identical to
  the previous page, and 15/15 harness checks pass (pick, dedupe, duplicate within one pick, track
  switch with row reuse, back chain, settings persistence, hit-test flat and tilted, accessible
  names).
- [x] 35. The collapsed player bubble is not exposed as a control.
  **Symptom.** `#bubble` is a plain `div` with an `aria-label`, and `aria-label` on a generic
  element with no role is ignored, so once the player is closed the only way to reopen it is an
  element assistive tech does not announce, cannot focus and cannot activate. *(4.8)* - the
  accessibility tree reported `{role:"generic", name:"Open player"}`, a `tabIndex` of `-1` and no
  keyboard path at all, so a switch or keyboard user who closed the player could not get back in.
  **Fixed.** `#bubble` now carries `role="button"` and `tabindex="0"`, and a `keydown` handler
  activates it on Enter and Space (preventing the default scroll on Space, since a `div` fires no
  synthetic click the way a native button does). The existing `:focus-visible` rule already
  matches `[tabindex]`, so the bubble gets the same keyboard focus ring as every other control and
  nothing else about it moved. Verified through the browser's accessibility tree
  (`Accessibility.getPartialAXTree`), which now reports `{role:"button", name:"Open player"}` with
  `tabIndex 0` and focus accepted, and by driving the keyboard: Enter and Space both reopen the
  collapsed player and Space reports `defaultPrevented:true`, while a pointer tap still opens it as
  before. Nothing visible changed: with animations and rAF frozen and `--mx`/`--my` pinned, the
  computed style and box geometry of all 119 nodes are identical to the previous page, and 21/21
  harness checks pass (pick, dedupe, duplicate within one pick, track switch with row reuse, back
  chain, settings persistence, hit-test flat and tilted, accessible names, bubble role/name/focus/
  Enter/Space/tap, visual identity).

- [x] 36. Playlist rows cannot be reached or activated from the keyboard.
  **Symptom.** Every other control on the page has been made operable without a pointer - the
  transport buttons, the chip buttons, the sliders, the motion segment, the drift switch and (item
  35) the collapsed bubble - but the playlist rows never were. They are `div`s with an `onclick`
  and nothing else, so the tab order steps straight over the whole playlist: a keyboard or
  switch-access user can walk the transport and change track with Next/Previous, but cannot jump to
  an entry, and the empty-state row ("No songs yet / Tap here to add songs") cannot open the picker
  at all, so the app is unusable without a pointer on a device with no files in it yet. Measured
  before the change: `tabIndex` -1 on every row, `focus()` left `document.activeElement` on the
  body, and a `keydown` of Enter on a row changed nothing (`idx` stayed where it was).
  **Fixed.** Rows are tab stops (`tabindex="0"` in both the track-row and the empty-state template)
  and Enter or Space activates them. Both the click handler and the key handler go through one
  `activateRow()`, so the pointer path and the keyboard path cannot drift - including the
  repeat-tap guard that stops a second activation inside `REPEAT_MS` from pausing the track the
  first one started. Space calls `preventDefault()` so it activates the row instead of scrolling the
  player, which is what a native button would do; Enter needs no default suppressed. Verified by
  driving the page: all three rows report `tabIndex 0`, `focus()` puts `activeElement` on the row,
  Space reports `defaultPrevented:true` and selects row 0, Enter selects row 2, Enter on the row
  already playing toggles instead of reloading, and a pointer click still selects as before.
  Nothing visible changed: with animations frozen and `--mx`/`--my` pinned, the computed style and
  box geometry of all 141 nodes are byte-identical to the previous page (the focus ring is drawn
  only for `:focus-visible`, i.e. keyboard navigation), and 21/21 harness checks pass (pick, dedupe,
  dedupe across picks, track switch with row reuse, back chain, settings persistence, hit-test flat
  and tilted, row tab stops, row focus, Space/Enter activation, pointer activation, visual identity).
- [x] 37. Closing the settings sheet throws focus away instead of returning it.
  *(5.0)* — the element that held focus when the sheet opened is remembered and focused again on
  close, and only when focus was still inside the sheet or had been dropped to `<body>`, so a
  close never pulls focus off a control the user has moved to since. `closeApp` closes the sheet
  with the restore off: the player is collapsing under it and the restore would be dropped again
  when the app is hidden. `preventScroll` keeps the restore from scrolling, and a programmatic
  focus on a button only paints the `:focus-visible` ring when the last input was the keyboard, so
  a pointer or touch user sees no new ring. The `aria-expanded` on `#optBtn` and
  `role="dialog"`/`aria-labelledby` on the sheet named in the symptom were added in the same pass;
  the focus boundary itself (Tab still leaves the sheet) is item 38 and was left alone. Measured:
  `activeElement` was `doneBtn` after Done and `gl` after the back key, and is `optBtn` after all
  three close paths now.
  **Symptom.** `#optBtn` opens the sheet and `#doneBtn`, the scrim or the hardware back closes it,
  but nothing moves focus on either edge. Opening the sheet leaves `document.activeElement` on
  `#optBtn`, so the first Tab walks into the sheet from behind it; closing it while a slider has
  focus leaves `activeElement` on that slider inside a `display:none` subtree, which the browser
  then drops to the body - a keyboard or switch user loses their place in the page and has to start
  tabbing from the top. Measured before the change: `activeElement` was `gl` after `#doneBtn` was
  activated. `#optBtn` also carries no `aria-expanded` and the sheet no `role="dialog"` /
  `aria-labelledby`, so assistive tech is not told that the button opened anything.
- [x] 38. The settings sheet is not a focus boundary: Tab walks out of it into the player behind.
  **Symptom.** The sheet is a `section` painted over the player, not a dialog. Once it is open the
  scrim covers the page but the controls underneath are still in the tab order and still receive
  the keyboard, so tabbing past the last control in the sheet ("Reset defaults") continues into
  `#closeBtn`, `#pp`, `#seek` and the playlist rows - controls the user cannot see and did not
  mean to reach - while Escape, the key every other sheet-shaped surface answers, does nothing.
  *(5.1)* — one document-level `keydown` handler owns Tab while the sheet is open and wraps it
  between the first and last visible control, pulling focus in from `#optBtn`/`<body>` if it is
  still outside; Escape runs the same `closeSheet()` that Done, the scrim and the back key run. The
  sheet gained `aria-modal="true"`, and the 1x1 `#file` input parked at `left:-9999px` is out of the
  tab order (`tabindex="-1"`, it is still clicked by "Add songs"), so the ring no longer lands on a
  control that is not on screen.
- [x] 39. The row that is playing is marked only by a CSS class, so a screen reader cannot tell
  which song is current.
  *(5.2)* — the current row now carries `aria-current="true"` and every other row carries nothing,
  written from both places the `cur` class is already written (the rebuild and the highlight-only
  path) so the attribute and the paint cannot disagree. No stylesheet selects on the attribute, so
  nothing visible moved: with animations frozen and `--mx`/`--my` pinned, the box geometry and 33
  computed properties of all 139 nodes are identical to the previous page with the player up and
  with the sheet open, and 14/14 harness checks pass (pick, dedupe, dedupe across picks, track
  switch with row reuse, back chain, reopen, settings persistence, hit-test flat and tilted, row
  state before and after a rebuild). The two checks that probe items 40 and 41 still fail, as they
  should.
  **Symptom.** `#list .cur` was the only thing saying which track is playing, and a class is not
  exposed to assistive tech: every row announced itself identically ("Alpha, My library, list
  item"), so a TalkBack user had no way to tell which song the transport buttons were pointed at.
  Measured before the change: `aria-current` was `null` on all three rows.
- [x] 40. The Motion segmented control exposes no selected state.
  **Symptom.** Auto / Touch / Off toggle a visual `on` class and nothing else, so the accessibility
  tree says three identical buttons and never which one is in force; a screen-reader user cannot
  tell whether the card is following the gyroscope, the finger or nothing. Measured: all three
  report `aria-pressed: null` while exactly one is painted as selected. The drift switch in the
  same sheet already reports `aria-pressed`.
  **Fixed in 5.3.** All three buttons now publish `aria-pressed` from `applyS`, the same function
  that moves the `on` class, and the markup ships the initial state. Measured after: `true,false,false`
  on load, exactly one `true` after each of the three taps. No stylesheet selects on `[aria-pressed]`
  for `.seg button`, so the paint is unchanged.
- [x] 41. Adding a song throws keyboard focus out of the playlist.
  **Symptom.** `renderList` replaces every row node when the set of files changes, so a row that
  held focus when the picker returned is destroyed under it and the browser drops focus to
  `<body>`; a keyboard or switch user is back at the top of the page and has to tab in again to
  reach the song they were on. Measured: with focus on row 1, picking one new file left
  `document.activeElement` as `BODY`.
  **Fixed in 5.4.** The row that last held focus is remembered by its `data-i` (not by node
  identity — the node is about to be replaced) from a `focusin` listener on the list, and the
  rebuilt row with the same key is focused again with `preventScroll`. Focus is only taken back
  when it was inside the list or had already fallen to `<body>`, so a user who has since moved to
  another control keeps it; if the row is gone (the empty-state row after the first song) focus
  lands on the row now at that position. Measured after: focus on row 1, one file added,
  `document.activeElement` is the row with `data-i="1"`; with the same harness on the previous
  page it was `BODY`. Rows are still reused on a track change, and the flat and tilted
  `elementFromPoint` hit-tests are unchanged.

## Transport state (found 2026-11-07, backlog exhausted at 41)

- [x] 42. A pause tapped inside the source-swap window is swallowed: the transport stays painted as
  playing over silence and the Android shell keeps audio focus.
  **Symptom.** Swapping a source fires a `pause` of its own, so `au.onpause` ignored *every* pause
  for 700 ms after a track change — including one the user asked for. Tapping Next and then pause
  ~250 ms later (the new track already playing) left `au.paused` true while `#app` still carried
  `playing`: pause icon, scaled-up artwork, equaliser still animating, `GP.setPlaying(false)` never
  called, and nothing on screen corrected it until the next play/pause. The same hole was open on
  the current playlist row and on the media-session pause action. Measured before, in the harness:
  `{auPaused: true, playingClass: true, setPlaying calls: [1]}`, unchanged 1.5 s later; the settled
  pause control case reported `{auPaused: true, playingClass: false, calls: [1,1,0]}`.
  **Fixed in 5.5.** Every deliberate pause now goes through `requestPause()`, which marks the pause
  as the page's own; `au.onpause` still ignores an unsolicited pause inside the switching window but
  honours one the page asked for, and `attemptPlay()` clears the mark so a play supersedes a pause
  that has not landed. Measured after: the same Next-then-pause tap gives
  `{auPaused: true, playingClass: false, calls: [1,0]}`, and the row-tap path matches. A plain Next
  still keeps `playing` and reports no spurious pause (`calls: [1]`), settled pause/resume, autoplay
  on Next, end-of-track advance, row reuse, dedupe, settings persistence and the flat and tilted
  `elementFromPoint` hit-tests are all unchanged.
- [x] 43. The play/pause button never says whether it is playing.
  **Symptom.** `#pp` carries the constant accessible name "Play or pause" and no `aria-pressed`, so
  the only thing that distinguishes the two states is the SVG swap — paint, not meaning, the same
  gap items 39 and 40 closed on the playlist row and the Motion switch. Measured: `aria-label` is
  "Play or pause" and `aria-pressed` is `null` both while playing and while paused.
  **Fixed in 5.6.** `#pp` now carries `aria-pressed`, written by a new `setPlaying()` that also owns
  the `playing` class, so the attribute and the icon cannot disagree. All four places that painted
  the state (`au.onplay`, `au.onpause`, `playRefused` and the end of the skip chain) go through it,
  and it writes only when the value actually changes. The label, the icons and every painted pixel
  are untouched. Measured after, tapping the real button through play/pause/play/pause:
  `[{ap:true,cls:true},{ap:true,cls:true},{ap:false,cls:false},{ap:true,cls:true},{ap:false,cls:false}]`
  - the attribute equals the class at every sample, and from the settled paused state the taps give
  `false, true, false`. Picking and dedupe, the title swap on track change, row reuse, the back
  chain and the flat and tilted `elementFromPoint` hit-tests are all unchanged.
- [x] 44. The audio hot paths resolve their nodes by id on every tick although `EL` already caches
  them.
  **Symptom.** `ontimeupdate` calls `$('seek')` on every tick and `$('cur')`/`$('dur')` whenever
  their text changes; `sk.oninput` and `onplay`/`onpause` do the same. Item 8 hoisted exactly these
  nodes into `EL` for `load()` and `resetSeek()`, so the hot paths are the only ones still looking
  them up. Measured: 10 `getElementById` calls over 2 s of playback (8 `timeupdate` ticks, 1.25 per
  tick) plus one per scrub frame and per play/pause.
  **Fixed in 5.7.** The four remaining lookups on those paths now read the cached node:
  `ontimeupdate` takes `EL.seek` instead of `$('seek')` and writes `EL.cur`/`EL.dur`, and
  `sk.oninput` writes `EL.cur`. Measured after, with `document.getElementById` wrapped in a
  counter: 8 dispatched `timeupdate` ticks and 4 scrub `input` events cost **0**
  `getElementById` calls, down from 9 + 4 = 13 on the same script before the change. The
  `onplay`/`onpause` half of the symptom had already gone away — neither handler looks anything up
  in the shipped 5.6 page — so nothing was changed there. No markup, style or handler wiring was
  touched: picking and dedupe, the title swap on a track change, row reuse, the back chain,
  debounced settings persistence, the seek readout advancing during playback and the flat and
  tilted `elementFromPoint` hit-tests all report exactly what they did before.
- [x] 45. Three more places hold a second handle to a node `EL` already caches.
  **Symptom.** `$('file').onchange`'s "everything picked was already in the list" branch writes
  `$('title')`/`$('artist')`, `huEl.oninput` writes `$('huV')`, and the seek-bar block binds
  `const sk=$('seek')` — all four nodes are already in `EL`. `load()`'s empty-playlist branch
  writes the same two title nodes through `EL`, so one node now has two write paths and a change
  to one silently misses the other. Found while doing item 44: `grep -c "\$('"` on the shipped
  page reports 33 lines carrying 50 `$()` calls, and these are the last four for nodes already in
  the map.
- [x] 46. The play/pause bump re-resolves the button it is bumping.
  **Symptom.** `restart($('pp'),'bump')` is called from the next/prev handlers, but `ppBtn` was
  cached at startup and is the same node, so every play and every pause costs one avoidable
  `getElementById` on a path the user hits constantly. Same shape as item 44, on the transport
  rather than the tick.
  **Fixed in 5.9.** `onplay` and `onpause` now bump `ppBtn`, the same reference `setPlaying()`
  already writes `aria-pressed` through, so the transport has one handle again. Measured with
  `document.getElementById` wrapped in a counter: a full play → pause → play cycle driven through
  the real handlers cost **1** `getElementById('pp')` call before and **0** after. The bump still
  fires on both events and `aria-pressed` still tracks the state; picking and dedupe, the title
  swap on a track change, row reuse, the back chain and the flat and tilted `elementFromPoint`
  hit-tests (all six controls resolving to themselves, control rects identical to the previous
  build) report exactly what they did before.
- [ ] 47. `renderList()` re-resolves the list container on every track change.
  **Symptom.** `renderList` opens with `const list=$('list')` and runs on every track change, every
  add and every delete, although nothing ever replaces `#list`. The click and keydown handlers on
  it do the same. Item 8's map stopped at the nodes `load()` and `resetSeek()` touch, so the
  playlist container was left out.
