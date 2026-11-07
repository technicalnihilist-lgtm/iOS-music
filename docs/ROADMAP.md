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
