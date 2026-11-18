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
- [x] 47. `renderList()` re-resolves the list container on every track change.
  **Symptom.** `renderList` opens with `const list=$('list')` and runs on every track change, every
  add and every delete, although nothing ever replaces `#list`. The click and keydown handlers on
  it do the same. Item 8's map stopped at the nodes `load()` and `resetSeek()` touch, so the
  playlist container was left out.
  **Fixed in 6.0.** `#list` joined the startup map as `EL.list`, and `renderList` plus both of its
  delegated handlers (`click`, `keydown`) now read that one reference, so the playlist container
  has a single handle again. Measured with `document.getElementById` wrapped in a counter: one
  track switch driven through the real row handler cost **1** `getElementById('list')` call before
  and **0** after. Picking and dedupe, the title swap on a track change, row reuse (node identity),
  the `cur`/`aria-current` pair, the back chain, debounced settings persistence and the flat and
  tilted `elementFromPoint` hit-tests (all six controls resolving to themselves) report exactly
  what they did before.

- [x] 48. The skip chain's give-up branch cancels the pending title and playlist paint, so the
  now-playing block keeps whatever the last successful paint left there.
  **Symptom.** `load()` paints the title, artist, artwork hue, colour slider and playlist 360 ms
  later inside the swap animation, and the give-up branch of `au.onerror` bumps `swapToken` to stop
  that queued run landing over its error message - but the queue carried the whole paint, not just
  the title. A run of unplayable files therefore left the block stale, and picking one unplayable
  file into an empty player left the startup state on screen: the title read "No songs yet" and the
  playlist still showed the "Tap here to add songs" row, over a track that was in `tracks` and
  highlighted nowhere (`cur: -1`). Reproduced by picking a corrupt WAV as the only file.
  **Fixed in 6.1.** The paint is now `paintTrack(t)`, called from `load()`'s `ui()` exactly as
  before, and the give-up branch calls it for the track it gave up on before `playError()` replaces
  the artist line, so the header names the file that failed and the playlist renders its row with
  `aria-current`. The token bump stays, so a stale swap still cannot land afterwards. Measured on
  the shipped page: one unplayable file picked into an empty player now reports
  `{title:"BrokenSong", artist:"Can't play this file", rows:1, addRow:false, cur:0}` where it
  reported `{title:"No songs yet", rows:1, addRow:true, cur:-1}` before. Geometry and computed style
  of `#app`, `#art`, `#title`, `#artist`, `#seek`, `#pp`, `#next`, `#prev`, `#closeBtn`, `#optBtn`,
  `#list`, `.lh` and `.times` are identical to the previous build after picking, after a track
  switch and with the sheet open; picking and dedupe, the title swap, row reuse (node identity), the
  back chain, debounced settings persistence and the flat and tilted `elementFromPoint` hit-tests
  (all six controls resolving to themselves) report what they did before.
- [x] 49. The two paths that stop playback without a pause event never tell the Android shell.
  **Symptom.** `au.onpause` calls `window.GP.setPlaying(false)` so the shell can abandon audio
  focus, but `playRefused()` and the give-up branch of `au.onerror` call `setPlaying(false)` alone.
  A track that errors mid-play leaves the page showing "play" while the shell still holds focus and
  its notification still says playing. Measured with a stubbed `window.GP`: a user pause records
  `[false]`, the same stop caused by an error records `[]`.
  **Fixed in 6.2.** The four `window.GP.setPlaying` writes were typed out at each call site, so the
  two paths that stop without a pause event simply did not have one. They now go through
  `setShellPlaying(v)`, which `onplay`, `onpause`, `playRefused()` and the give-up branch of
  `au.onerror` all call, so every start and every stop reaches the shell by the same route.
  Measured with a stubbed `window.GP` on the shipped page: a user pause records `[false]`, a
  refused play records `[false]` where it recorded `[]`, and the skip chain giving up records
  `[false]` where it recorded `[]`; a play still records `[true]`. Picking and dedupe, the title
  swap, row reuse (node identity), the back chain and the flat and tilted `elementFromPoint`
  hit-tests (all six controls resolving to themselves) report what they did before.
- [x] 50. Three per-press `getElementById` calls on paths a cached node could serve.
  **Symptom.** Tapping the empty-state row costs 1 `getElementById('file')`, tapping play on an
  empty playlist costs 2 (`#pp` and `#file`), and each media-session next/previous press costs 1 for
  the transport button it clicks. None of those nodes is ever replaced. Measured with
  `document.getElementById` wrapped in a counter on the shipped 6.0 page.
  **Fixed in 6.3.** The picker and the two skip buttons are now resolved once at startup into
  `fileInput`, `nextBtn` and `prevBtn`, beside the `ppBtn` reference the transport already had.
  Four call sites read them: the empty-state row and the three transport handlers take
  `fileInput.click()` instead of `$('file').click()`, and the media-session handlers click
  `nextBtn`/`prevBtn` instead of looking the button up first. Re-measured with
  `document.getElementById` wrapped in a counter on the shipped page, one press at a time: the
  empty-state row went from `{file:1}` to `{}`, play on an empty playlist from `{file:1}` to `{}`
  (the `#pp` half of the 6.0 figure had already gone with the cached `ppBtn`), and each
  media-session next/previous press from `{next:1,file:1}` and `{prev:1,file:1}` to `{}` - the
  handler's click still runs and still loads the next track, it just no longer costs a document
  lookup on the way. Nothing on screen is involved: picking and dedupe, the title swap, row reuse
  (node identity), `aria-current`, the debounced settings write, the back chain and the flat and
  tilted `elementFromPoint` hit-tests (all six controls resolving to themselves) report what they
  did before, and the geometry and computed style of `#app`, `#art`, `#title`, `#artist`, `#seek`,
  `#pp`, `#next`, `#prev`, `#closeBtn`, `#optBtn`, `#list`, `.lh` and `.times` are byte-identical
  to the previous build.
- [x] 51. A slider drag frame re-resolved the motion segment and rewrote all three of its buttons.
  **Symptom.** `applyS()` is the settings applier and every slider's `input` handler calls it, so a
  drag runs it once per frame (~60/s). Each of those runs did `getElementById('mot')`, queried its
  three buttons and rewrote their `on` class and `aria-pressed` - six DOM writes for a mode a slider
  cannot change - and each slider's numeric readout was re-resolved with `getElementById(id+'V')` on
  every frame as well. Measured with `document.getElementById` wrapped in a counter on the shipped
  6.3 page, 60 `input` events on the Blur slider cost 120 `getElementById` calls, 180
  `classList.toggle` and 180 `setAttribute` calls on the three motion buttons.
  **Fixed in 6.4.** The segment's buttons and the mode last painted are resolved once (`segBtns`,
  `segMode`), so `applyS()` repaints them only when `S.motion` has actually moved; each slider's
  input and readout now live in one startup map, `SL`, so `syncSlider()` and the `input` handler
  write through references they already hold. Re-measured the same 60 events on the shipped page:
  `getElementById` 120 -> 0, `classList.toggle` 180 -> 0, `setAttribute` 180 -> 0. Picking a mode
  and `Reset defaults` still repaint the segment (Auto / Touch / Off report the same `on` class and
  `aria-pressed` as before, in the same order). Picking and dedupe, the title swap, row reuse (node
  identity), `aria-current`, the back chain, debounced settings persistence, the slider readouts,
  the `--blur` custom property and the flat and tilted `elementFromPoint` hit-tests (all six
  controls resolving to themselves) report what they did before, and the geometry and computed
  style of `#app`, `#art`, `#title`, `#artist`, `#seek`, `#pp`, `#next`, `#prev`, `#closeBtn`,
  `#optBtn`, `#list`, `.lh` and `.times` are identical to the previous build at startup, with the
  sheet open and after a drag.
- [x] 52. Opening and closing the settings sheet each cost a `getElementById('optBtn')`.
  **Symptom.** `openSheet()` and `closeSheet()` both write `aria-expanded` on `#optBtn` through
  `$('optBtn')`, although `sheetFocus` was initialised to that same node at startup. Measured with
  `document.getElementById` wrapped in a counter on the shipped 6.4 page, one open plus one close -
  whether it is closed with `Done` or with the scrim - costs 2 lookups, one per transition.
  **Fixed in 6.5.** The opener is resolved once at startup into `optBtn`, beside `fileInput`,
  `nextBtn` and `prevBtn`, and `openSheet()`, `closeSheet()`, `sheetFocus` and the click binding all
  read that one reference, so a transition no longer touches the document at all. Re-measured with
  `document.getElementById` wrapped in a counter on the shipped page, one press at a time: opening
  went from `{optBtn:1}` to `{}` and closing with `Done` from `{optBtn:1}` to `{}`, and closing with
  the scrim is `{}` too - `aria-expanded` still reads `true` while the sheet is open and `false`
  after either close, and focus still returns to the opener. Picking and dedupe, the title swap, row
  reuse (node identity), `aria-current`, the debounced settings write, the back chain (`true`,
  `true`, `false`) and the flat and tilted `elementFromPoint` hit-tests (all six controls resolving
  to themselves) report what they did before, and the geometry and computed style of `#app`,
  `#art`, `#title`, `#artist`, `#seek`, `#pp`, `#next`, `#prev`, `#closeBtn`, `#optBtn`, `#list`,
  `.lh` and `.times` are identical to the previous build at startup, with the sheet open and after
  two open/close cycles.
- [x] 53. A ten-entry note-frequency table is declared and never used.
  **Symptom.** `const N={C4:261.6,D4:293.7,...,E5:659.3}` sits beside the other constants, and the
  identifier `N` appears nowhere else in the page: it is dead weight in the file that ships inside
  the APK, and it reads as if something plays a note.
  **Fixed in 6.6.** The declaration is deleted. It was one line of 97 bytes, and `N` occurred
  exactly once in the page - at the declaration itself, never read, written or passed anywhere - so
  removing it leaves no dangling reference; the page that ships inside the APK goes from 66,224 to
  66,127 bytes and the extracted script still passes `node --check`. Nothing executes the table, so
  nothing on screen is involved: picking and dedupe (a repeat pick still leaves the list at 3 rows),
  the title swap, row reuse (node identity across a skip), `aria-current`, the debounced settings
  write, the back chain (`true`, `true`, `false`) and the flat and tilted `elementFromPoint`
  hit-tests (all six controls resolving to themselves) report exactly what the 6.5 page reports,
  the two pages run side by side in headless Chromium.

## Backlog added after the 53 items above were cleared

- [x] 54. A settings drag rewrote all four global depth properties on `:root` on every frame,
  including the ones the slider being dragged does not touch.
  **Symptom.** `applyS()` is the settings applier and every slider's `input` handler calls it, so a
  drag runs it once per frame. It wrote `--glass`, `--blur`, `--tiltk` and `--park` unconditionally,
  with no comparison against the value already on the element - unlike `paint()` beside it, which
  remembers what it last wrote. Measured with `CSSStyleDeclaration.prototype.setProperty` wrapped in
  a counter on the shipped 6.6 page, 20 `input` events on the Shadow slider - a slider that feeds
  none of those four properties - cost 80 root `setProperty` calls carrying exactly 4 distinct
  values, so all 80 rewrote a value that had not changed; 10 events on the Blur slider cost 40 calls,
  30 of them no-ops. `--glass` and `--blur` are what `backdrop-filter` on the card and the sheet
  resolve against, so dragging Shadow re-blurred the glass 20 times for nothing.
  **Fixed in 6.7.** The value last written for each of the four is remembered in `pglass`, `pblur`,
  `ptiltk` and `ppark`, and `applyS()` writes a property only when the value it is about to write
  differs from the one already on the element - the same guard `paint()` has always used for its
  own eight. Nothing else in the page writes these four, so the cache cannot go stale, and every
  write that does happen still carries exactly the value it carried before. Re-measured the same
  20 Shadow-slider events on the shipped page: 80 root `setProperty` calls -> 0, and the same 10
  Blur-slider events: 40 -> 10, now one write per real change of `--blur` and nothing else. The
  computed `--glass`, `--blur`, `--tiltk` and `--park` after every slider, `Reset defaults` and a
  restored session are unchanged. Picking and dedupe (3 rows, a repeat pick leaves 3), the title
  swap (`a` -> `b`), row reuse (node identity across a skip), `aria-current`, the debounced settings
  write, the back chain (`true`, `true`, `false`) and the flat and tilted `elementFromPoint`
  hit-tests (all six controls resolving to themselves) report what they did before, and the geometry
  and computed style of `#app`, `#art`, `#title`, `#artist`, `#seek`, `#pp`, `#next`, `#prev`,
  `#closeBtn`, `#optBtn`, `#list`, `.lh` and `.times` - 13 elements x 17 properties, at startup,
  with the sheet open and after a drag, sampled with the decorative animations frozen and the depth
  spring parked at rest - are byte-identical to the 6.6 page.
- [x] 55. The idle-sway animation on `.drift` kept running while the player was collapsed to the
  bubble and while the page was hidden.
  **Symptom.** `syncBlobs()` freezes the three backdrop blobs in both states, but `.drift` - the
  wrapper that carries the 17s `sway` loop around the whole card - was never included, so it kept an
  animation live over a subtree whose only child is `display:none` once the player is collapsed.
  Measured on the shipped 6.6 page with `getAnimations()` on `.drift`: `running` after `#closeBtn`
  collapses the player, while the three `.blobs i` children correctly report `paused`.
  **Fixed in 6.8.** `syncBlobs()` now resolves `.drift` once, lazily, and toggles the same `paused`
  class on it that it has always toggled on `.blobs`, with `.drift.paused{animation-play-state:paused}`
  beside the existing `.drift.off` rule. The freeze goes through `animation-play-state`, so the
  frame on screen is the frame it stopped on and the sway resumes from that point rather than
  restarting - the same way the blobs are already handled - and the class is dropped again the
  moment the player reopens or the page becomes visible. Measured with `getAnimations()` on
  `.drift` in headless Chromium, on the 6.7 page and the 6.8 page side by side: after `#closeBtn`
  collapses the player the sway goes from `running` to `paused`, and with `document.hidden` stubbed
  true and a `visibilitychange` dispatched it goes from `running` to `paused`; on reopening the
  bubble and on becoming visible again it returns to `running` in both cases, and the three
  `.blobs i` children report exactly what they did before (`paused` collapsed and hidden, `running`
  otherwise). The user's own Idle-drift switch still wins: with it off the sway reports `none`
  rather than `paused`, and switching it back on reports `running`. Picking and dedupe (3 rows, a
  repeat pick leaves 3), the title swap (`a` -> `b`), row reuse (node identity across a skip),
  `aria-current`, the debounced settings write, the back chain (`true`, `true`, `false`) and the
  flat and tilted `elementFromPoint` hit-tests (all six controls resolving to themselves) report
  what they did before, and the geometry and computed style of `#app`, `#art`, `#title`, `#artist`,
  `#seek`, `#pp`, `#next`, `#prev`, `#closeBtn` and `#optBtn` - 10 elements x 14 properties - hash
  identically to the 6.7 page. The page goes from 66,970 to 67,404 bytes.
- [x] 56. `load()` started a fresh 700 ms `switching` timer on every call and never cleared the
  previous one.
  **Symptom.** The timer exists to clear `switching` once a source swap's queued pause event has
  landed, but overlapping loads - the bounded skip chain walks up to three tracks, and rapid Next
  taps stack on top - each left their own timer alive, so an earlier timer fires while a later load
  is still settling and clears `switching` under it. Measured with `window.setTimeout` wrapped in a
  counter on the shipped 6.6 page: one pick leaves 2 live 700 ms timers, one skip takes it to 4, two
  more to 5.
  **Fixed in 6.9.** The pending clear is now a single handle, `switchTimer`, owned by the most recent
  load: `armSwitching()` cancels the previous timer before arming its own, and every path that used
  to write `switching=false` outright - a refused play, a source that would not load, a load that
  does not autoplay, the `error` skip chain and a page becoming hidden - now goes through
  `clearSwitching()`, which cancels the timer with the flag, so no timer can outlive the state it
  clears. A load that does not autoplay arms nothing at all, since it clears the flag in the same
  breath. Measured in headless Chromium with `setTimeout` wrapped in a counter and the count read
  synchronously after each call: back-to-back skips leave 2 live 700 ms timers on the 6.8 page and 1
  on the 6.9 page, and the count no longer grows with the length of the skip chain. The symptom the
  item names reproduces on the 6.8 page and is gone on the 6.9 page: with a pick, a skip 500 ms
  later and the swap's own unsolicited `pause` dispatched at 760 ms, 6.8 has already been cleared by
  the first load's timer and paints the transport paused (`aria-pressed="false"`) over a track that
  is starting, while 6.9 still holds the guard and ignores it (`aria-pressed="true"`); a pause the
  page itself asked for is honoured in both, at the same moment. Picking and dedupe (3 rows, a
  repeat pick leaves 3), the title swap (`a` -> `b`, then back to `a` after three skips), row reuse
  (node identity across a skip), `aria-current`, the debounced settings write (`glass: 31` stored,
  label `31%`), the back chain (`true`, `true`, `false`) and the flat and tilted
  `elementFromPoint` hit-tests (all six controls resolving to themselves) report what the 6.8 page
  reports, with no console or unhandled-rejection noise on either, and the geometry and computed
  style of `#app`, `#art`, `#title`, `#artist`, `#seek`, `#pp`, `#next`, `#prev`, `#closeBtn`,
  `#optBtn`, `#list`, `.lh` and `.times` - 13 elements x 17 properties, at startup, with the sheet
  open and after two open/close cycles - hash identically on the two pages (6670-byte snapshot).
  The page goes from 67,404 to 68,359 bytes.

- [x] 57. The duplicate check keyed on the display name alone, so a second song with the same file
  name was silently dropped.
  **Symptom.** `#file`'s dedupe key was `t.t+'|'+t.a` - the display name plus the artist line, and
  the artist line is the constant `My library` - so the key was the bare name. Two different songs
  that share a file name, the common case for `01 Intro.mp3` across two albums or the same title in
  two folders, collapsed into a single entry: the second was dropped without a word and could never
  be played. Measured in headless Chromium on the shipped 6.9 page by picking `Alpha.mp3` (1000
  bytes, mtime 111), `Beta.mp3` and `Gamma.mp3` and then a genuinely different `Alpha.mp3` (9999
  bytes, mtime 444): the playlist stayed at 3 rows and the new file's blob URL was never created,
  while re-picking the first `Alpha.mp3` correctly stayed at 3.
  **Fixed in 7.0.** Each entry now carries `k`, the display name together with the file's own
  `size` and `lastModified`, and the seen-set is built from that. A repeat pick of the same file
  reproduces the key exactly and is still rejected; a different file with the same name is kept.
  The same sequence on the 7.0 page ends at 4 rows, with `Alpha` listed twice, the title reading
  `Alpha` and the highlight on the copy that was just added. Picking and dedupe (3 rows, a repeat
  pick leaves 3), the title swap on a row jump (`Alpha` -> `Beta`), row reuse (node identity across
  a skip), `aria-current`, the debounced settings write, the back chain (`true`, `true`, `false`)
  and the flat and tilted `elementFromPoint` hit-tests (all six controls resolving to themselves)
  report what the 6.9 page reports, and the geometry and computed style of `#app`, `#art`,
  `#title`, `#artist`, `#seek`, `#cur`, `#dur`, `#pp`, `#next`, `#prev`, `#closeBtn`, `#optBtn`,
  `#list`, `#sheet`, `#bubble`, `#scrim`, `#live`, `.lh`, `.times`, `.meta`, `.artwrap`, `.top`,
  `.row`, `.drift`, `.tilt`, `.blobs`, `.blobs i:nth-child(1)`, `.grab`, `.sec`, `.seg`,
  `.seg button`, `.sw`, `.pill` and `.row2` - 34 elements x 22 properties, at startup and with the
  sheet open - hash identically on the two pages (18566-byte snapshot). The page goes from 68,359
  to 69,048 bytes.
- [x] 58. Collapsing the player to the bubble, and reopening it, both leave keyboard focus on a node
  that is no longer rendered.
  **Symptom.** `closeApp()` hides the card 520 ms later and shows the bubble, and `openApp()` does
  the reverse, but neither of them moves focus. Measured in headless Chromium on the 7.0 page: with
  focus on `#closeBtn` and a press on it, `document.activeElement` is still `#closeBtn`, inside the
  card `closeApp()` has just set to `display:none`, 700 ms later; with focus on the bubble and a
  press on it, `document.activeElement` is the bubble, which `openApp()` has just hidden. Either
  way a keyboard or switch-access user is dropped to the document with no control under focus and
  has to tab in from the top to get back to the player.
- [x] 59. The settings sheet is declared `aria-modal` but the player behind the scrim stays in the
  accessibility tree.
  **Symptom.** The sheet carries `aria-modal="true"` and contains Tab, so it behaves as a modal for
  a keyboard, but nothing takes the card behind it out of the accessibility tree: a screen reader
  in browse mode can still read and activate `#pp`, `#next`, `#prev` and the playlist rows under the
  scrim, operating a player the sheet is supposed to be covering.
  **Fixed in 7.2.** `openSheet()` now takes the card out of the accessibility tree for exactly as
  long as the sheet is open, and `closeSheet()` puts it back before it hands focus anywhere.
  `cardModal()` uses `inert` where the WebView has it (Chrome 102+) and `aria-hidden="true"` on the
  same node where it does not; `inert` also refuses the subtree focus, which is why Chrome will not
  apply a bare `aria-hidden` to a subtree that still contains the focused node, and why the opener's
  focus is dropped on the way in. Nothing is lost by that: the Tab trap already sent the opener's
  Tab to the sheet's first control and still does (`#gl`), and `Done`, Escape and the scrim still
  restore that opener on close, where its ring comes back. Neither attribute is read by a selector,
  so no layout, paint or transition changes: measured in headless Chromium against the 7.1 and 7.2
  pages, 34 elements x 24 computed properties plus their bounding rects, at startup, 600 ms in,
  after a pick plus a row jump, with the sheet open, with it closed and collapsed to the bubble,
  hash identically (0 differing values), and the pointer path leaves `document.activeElement` and
  the ring state on the two pages the same. The accessibility tree is where the change shows: with
  the sheet open by pointer and by Enter, `#pp`, `#next`, `#prev`, `#closeBtn`, `#optBtn` and every
  `listitem` are gone from it (7.1 reports all five buttons), the sheet's own controls are still
  there, and closing restores the player to the tree; the same holds with
  `HTMLElement.prototype.inert` deleted, where the card carries `aria-hidden="true"` and is equally
  absent. Picking (3 rows, a repeat pick leaves 3), the title swap on a row jump (`Beta`), row reuse
  (node identity across a skip), the debounced settings write (`#gl` dragged to 30 lands `glass:30`
  in `glass.settings.v1`), the back chain (`true`, `true`, `false`) and the flat and tilted
  `elementFromPoint` hit-tests (all six controls resolving to themselves) report what the 7.1 page
  reports. The page goes from 71,705 to 73,342 bytes.
- [x] 60. An unplayable file starts playback the listener had paused.
  **Symptom.** The skip chain in `au.onerror` passed a hard `true` for autoplay, so it resumed
  playback whatever the transport had last been asked for. Measured in headless Chromium on the
  7.2 page: pause during "One", press Next onto a file the platform will not decode, and the chain
  walks past it and starts "Three" out loud - `playing` true and `currentTime` running from 0, with
  no press from the listener and nothing on screen to say why. It is the opposite of what the same
  press does on a playable file, where `nextBtn` passes `!au.paused` and the player stays quiet.
  **Fixed in 7.3.** `load()` now records the intent it was asked for in `wantPlay`; `attemptPlay()`
  sets it, `requestPause()`, an honoured `onpause` and the give-up branch clear it, and the chain
  re-loads with that instead of a hard `true`. Measured against the 7.2 page: paused + Next onto an
  undecodable file now lands on "Three" with `playing` false and `currentTime` at 0 and stays there
  for the next 1.5 s, and a press on play then starts it (`currentTime` 1.1); playing + the same
  press still skips to "Three" and keeps playing (3.9 -> 5.5); a row tap still asks for playback,
  `ended` still advances and plays, and three unplayable tracks in a row still give up on the third
  with the same visible line and the same "Stopped after 3 unplayable tracks." status. 64 elements
  x 39 computed properties plus their bounding rects, and the rendered 420x900 frame (0 of 378,000
  pixels differ), hash identically against 7.2. The page goes from 73,342 to 74,528 bytes.
- [x] 61. Every slider's accessible name changes with its value.
  **Symptom.** Each slider sits inside a `<label>` whose text includes its own numeric readout, so
  the computed name is "Transparency 18%" and becomes "Transparency 40%" in the middle of a drag:
  measured in the accessibility tree of the 7.2 page, moving `#gl` from 18 to 40 changes the slider's
  name from "Transparency 18%" to "Transparency 40%", while `aria-valuetext` already carries that
  same reading. A screen reader re-announces a changed name on every step of the drag, which buries
  the value it is meant to be reading, and the name no longer identifies the control once it has
  moved. The same holds for `#bl`, `#hu`, `#dp`, `#tl`, `#pr`, `#gv` and `#sd`.
  **Fixed in 7.4.** Each of the eight range inputs now carries a static `aria-label` holding just
  the control's name ("Transparency", "Blur", "Colour shift", "Depth", "Tilt", "Parallax", "Light",
  "Shadow"), which takes precedence over the `<label>` content that the readout was polluting; the
  value stays on `aria-valuetext`, which already carried the unit. Measured in the accessibility
  tree of the 7.3 page with the sheet open, moving each slider to 72% of its range changed its
  computed name on all eight ("Transparency 18%" -> "Transparency 37%", "Blur 28" -> "Blur 43",
  "Colour shift 250 degrees" -> "Colour shift 259 degrees" and so on); on the 7.4 page all eight
  keep one name and the reading stays on `aria-valuetext` ("Transparency", value text "37%").
  Nothing on screen moves: the attribute is not rendered, no stylesheet selects on it, and the
  rendered 420x900 frame is identical to the 7.3 page pixel for pixel (0 of 378,000 pixels differ)
  both on the card and with the sheet open, where 33 elements x 28 computed properties plus their
  bounding rects hash identically. The behaviour harness reports what the 7.3 page reports: picking
  (3 rows, an exact repeat leaves 3, the same name with a different size is accepted as a 4th),
  the title swap on a row jump (`Alpha` -> `Beta`) and on a skip (`Gamma`), row reuse (node identity
  across the skip), the debounced settings write (`#gl` at 30 lands `glass:30` in
  `glass.settings.v1` and nothing is written synchronously), the back chain (`true`, `true`,
  `false`), and the flat and tilted `elementFromPoint` hit-tests (all six controls resolving to
  themselves). The page goes from 74,528 to 75,167 bytes.
- [x] 62. The playlist has no accessible name, and "Playlist" is not a heading.
  **Symptom.** `#list` is `role="list"` with no name of its own and the visible "Playlist" above it
  is a plain `div`, so the accessibility tree of the 7.2 page exposes an unnamed list: a
  screen-reader user hears "list" without learning what it holds, and cannot reach the playlist by
  heading navigation, which is the only way to skip the transport controls. "Now Playing" at the top
  of the card is a plain `b` for the same reason, while the track title under it is the page's only
  heading, so the outline reads as a single heading with no sections around it.

  **Fixed in 7.5.** `#list` takes `aria-labelledby` pointing at its own label, and the two labels
  become headings without becoming new elements: "Now Playing" and "Playlist" each carry
  `role="heading" aria-level="2"` on the node that was already there, so no tag, class, style or
  text changes. Measured in the accessibility tree of the 7.4 page with three tracks picked: the
  list node's name was the empty string and the only heading was the track title ("2:Beta"); on
  the 7.5 page the tree carries `heading` "Now Playing" at level 2, the track title at level 2 and
  `heading` "Playlist" at level 2, and the `list` node's name is "Playlist". Nothing on screen
  moves: neither attribute is rendered, no stylesheet selects on `[role]`, `[aria-level]` or
  `#listLabel`, and 117 elements x 47 computed properties plus their bounding rects hash
  identically at startup and 140 elements x 29 of them with three rows picked and the sheet open,
  the rendered 420x900 frame differing in 0 of 378,000 pixels in all three states (player,
  playlist, sheet). The behaviour harness reports what the 7.4 page reports: picking (3 rows, an
  exact repeat leaves 3, the same name with a different size is accepted as a 4th), the title
  after a row jump (`Beta`), row reuse (node identity across a jump), the debounced settings write
  (`#gl` at 30 lands `glass:30` in `glass.settings.v1` and nothing is written synchronously), the
  back chain (`true`, `true`, `false`), and the flat and tilted `elementFromPoint` hit-tests (all
  six controls resolving to themselves). The page goes from 75,167 to 75,270 bytes.

## Backlog added after the 62 items above were cleared (found 2026-11-12)

- [x] 63. The skip chain's give-up branch leaves the media session reporting "playing" after it has
  stopped.
  **Symptom.** A stop has to be reported in three places - the card's paint, the shell's audio focus
  and the media session - and the third was typed out by hand at each call site that had one
  (`au.onplay`, `au.onpause`, `playRefused`). The give-up branch of `au.onerror` is the one stop
  that fires no pause event of its own, and it never got the line: measured in headless Chromium on
  the 7.5 page, pick a single track, let it play (`navigator.mediaSession.playbackState` is
  "playing", the notification says so), then let the source fail - `#pp` flips to "play",
  `aria-pressed` goes "false", the artist line reads "Can't play this file", the status region
  announces it and `window.GP.setPlaying(false)` drops the shell's audio focus, while
  `navigator.mediaSession.playbackState` is still "playing". The lock screen and the media
  notification therefore keep a play/pause control and a "playing" state over a player that has
  given up, until the next track or a teardown replaces them.
  **Fixed in 7.6.** One helper, `setSessionPlaying(v)`, sits beside `setShellPlaying()` and is now
  the only place the page writes `mediaSession.playbackState`: `au.onplay`, `au.onpause`,
  `playRefused` and the give-up branch all call it (the three hand-written `try/catch` copies are
  gone), so a path that paints a stop cannot report it to the card and the shell while leaving the
  session behind. Measured against the 7.5 page in headless Chromium: after the give-up the state
  is "paused" on 7.6 where 7.5 reported "playing", with `aria-pressed` "false", the `playing` class
  off, the artist line and the "Can't play this file." status identical on both pages; an ordinary
  transport is untouched - pick three tracks (state "playing", `aria-pressed` "true"), press pause
  ("paused", "false"), press play ("playing", "true") - and a refused play still reports "paused".
  Nothing on screen moves: 128 elements x 30 computed properties plus their bounding rects hash
  identically against 7.5 in the player state (0 differing) and with the sheet open (0 differing,
  against a 7.5-vs-7.5 control run that also reports 0), and the rendered 412x892 frame at
  deviceScaleFactor 2 is the same hash on both pages in both states (`e57fd2390a24`), stable across
  two captures of each. The behaviour harness reports what the 7.5 page reports: picking (3 rows, an
  exact repeat leaves 3), the title after a row jump (`two`), row reuse (node identity across a
  jump), the debounced settings write (`#gl` at 30 lands `glass:30` in `glass.settings.v1` and
  survives a reload), the back chain (`true`, `true`, `false`) and the flat and tilted
  `elementFromPoint` hit-tests (all six controls resolving to themselves). The page goes from
  75,270 to 75,898 bytes.
- [x] 64. Five more places hold a second handle to a node the page already caches, and `openApp()`
  re-resolves `#closeBtn` on every reopen.
  **Symptom.** Items 45 and 50 swept the per-press document lookups but missed these:
  `const huEl=$('hu')` at the colour-shift slider duplicates `EL.hu`, which `paintTrack()` already
  writes; `const artBox=$('art')` duplicates `EL.art`; `.drift` is held twice under two names -
  `driftEl` for `applyS()` and `driftField` for `syncBlobs()` - so the same node has two handles
  that can drift apart; `$('file').onchange` re-resolves the input that `fileInput` already holds;
  and `openApp()` calls `$('closeBtn')` inside its focus fallback, a document lookup on every
  reopen of the card from the bubble. None of them is hot enough to see, which is why they survived
  the sweep, but each is a second source of truth for a node that never moves.
  **Not done yet.** Behaviour-identical by construction: the fix is to point every site at the
  existing handle, add `#addBtn`/`#resetBtn` to the startup cache the way `#file`, `#next`, `#prev`
  and `#optBtn` already are, and keep one name for `.drift`. Verify with the behaviour harness, the
  flat and tilted hit-tests and the computed-style/pixel comparison; nothing rendered can change.
  **Fixed in 7.7.** Every site now points at the handle the page already had, so no node is
  reachable by two names: the colour-shift slider's own `$('hu')` is gone and its `input` handler
  writes `EL.hu`/`EL.huV`, the artwork's second `$('art')` is gone and `measureTilt()` and the four
  touch listeners use `EL.art`, `.drift` is held once as `driftEl` (resolved at startup) instead of
  twice as `driftEl` and the lazily resolved `driftField` that `syncBlobs()` used to re-query, the
  picker's handler is `fileInput.onchange` rather than a fresh `$('file')`, and `#closeBtn`,
  `#addBtn` and `#resetBtn` join `#file`, `#next`, `#prev` and `#optBtn` in the startup cache -
  `openApp()`'s focus fallback and `closeApp`'s binding use that one handle, so reopening the card
  from the bubble no longer does a document lookup, and the reset statement no longer resolves the
  same button twice. Nothing rendered can move: the markup and the stylesheet are byte-identical
  outside the `<script>` block (18,315 bytes of head and 18 bytes of tail compare equal), and in
  headless Chromium at 420x900, deviceScaleFactor 2, with animations off for a stable capture, 118
  elements x 36 computed properties plus their bounding rects hash identically to the 7.6 page in
  the player state, the same 118 with the sheet open, and 141 with three rows picked and the sheet
  open - each state's 7.7 hash is one the 7.6 page itself produced on a repeat run, the run-to-run
  spread being the sway's phase, and the rendered frame matches the 7.6 frame in all three states
  (player and playlist identical to the 7.6 control capture, the sheet identical across all three
  captures). The behaviour harness reports what the 7.6 page reports: picking (3 rows, an exact
  repeat leaves 3, the same name with a different size is accepted as a 4th), the title after a row
  jump (`Beta`, and `Gamma` after the next one), row reuse (node identity across a jump, `cur` on
  row 2), the debounced settings write (`#gl` at 30 lands `glass:30` in `glass.settings.v1` with
  nothing written synchronously), the back chain (`true`, `true`, `false`) and the flat and tilted
  `elementFromPoint` hit-tests (all six controls resolving to themselves). The page goes from
  75,898 to 76,766 bytes.

## Backlog added after the 64 items above were cleared (found 2026-11-13)

- [x] 65. `prefers-reduced-motion` makes the two endless loops run a hundred times a second instead
  of stopping them.
  **Symptom.** The reduced-motion block is a blanket `*{animation-duration:.01s!important}`, written
  for the entrances (popIn, sheetIn, artSwap) that should arrive at once. Two animations in the page
  never end - the 14 s `float` on the three background blobs and the 0.9 s `eq` on the playing row's
  equaliser - and the blanket rule only shortens them: `animation-iteration-count` is still
  `infinite`, so each becomes a 10 ms loop, i.e. 100 iterations a second, for as long as the player
  is open. Measured in headless Chromium with `--force-prefers-reduced-motion` on the 7.7 page:
  `getComputedStyle(document.querySelector('.blobs i'))` reports `animation-name: float`,
  `animation-duration: 0.01s`, `animation-iteration-count: infinite`, and the same for `.eq i`. The
  17 s sway is the only loop that is actually stopped, because it has a rule of its own further down
  the sheet (`.drift{animation:none!important}`) - so the one mode whose purpose is to remove motion
  has more of it than any other, and keeps a compositor busy behind the glass the whole time.
  **Fixed in 7.8.** The reduced-motion block now stops those two loops as well:
  `.blobs i,.eq i{animation:none!important}`, with `.eq i{height:16px}` so the equaliser still reads
  as "this row is playing" once its motion is gone (without a height the bars, whose height came
  only from the keyframes, would collapse to nothing). Measured on the 7.8 page under the same flag:
  both report `animation-name: none` where 7.7 reported `float`/`eq` at 0.01 s infinite, the
  equaliser's computed height is a static 16px, and the playing state is unchanged (`playing` class
  on, `#pp` `aria-pressed` "true"). Nothing changes for anyone else: the rule lives inside the media
  query, the stylesheet outside it is byte-identical, and in ordinary mode the computed-style/rect
  hash of 119 elements (player state `ba4de5d5`, sheet open `8d70e6b0`) and the rendered 412x892
  frame at deviceScaleFactor 2 match 7.7 exactly, stable across repeats. The behaviour harness
  reports what the 7.7 page reports: picking (3 rows, an exact repeat leaves 3, a same-name file of
  a different size is a 4th), the title after a row jump (`Beta`, then `Gamma`), row reuse (node
  identity across a jump), the debounced settings write (`#gl` at 30 lands `glass:30` in
  `glass.settings.v1`), the back chain (`true`, `true`, `false`) and the flat and tilted
  `elementFromPoint` hit-tests (all six controls resolving to themselves). The page goes from
  76,766 to 77,448 bytes.
- [x] 66. The skip chain's three-failure budget is not reset by a track that plays, so it counts
  failures across unrelated good tracks.
  **Symptom.** `fails` is incremented in `au.onerror` and cleared only by the `onOk` callback that
  `load()` hands to `attemptPlay()` - and only when the load was asked to autoplay. A press of
  `#pp` (or a row tap on the current track) calls `attemptPlay()` with no callback, so a track that
  starts perfectly well leaves the counter where the last failure put it. Play two unplayable files,
  then one that works, then a single bad one, and `fails` reaches 3 on that one file: the chain gives
  up after one consecutive failure and the status region announces "Stopped after 3 unplayable
  tracks." over a playlist that has exactly one bad entry. The budget is meant to be consecutive
  failures, not a lifetime total.
  **Fixed in 7.9.** `fails` is now cleared in `au.onplay`, the one place a source has demonstrably
  started, and the `onOk` callback that `load()` used to hand to `attemptPlay()` is gone with it -
  it was the only caller, so the parameter and both of its call sites went too. Every successful
  start resets the run, whichever path asked for it. Demonstrated with real media in headless
  Chromium: a playlist of two undecodable files and one real WAV, picked in one go. The chain walks
  Bad1 -> Bad2 -> Good on its own in both versions and lands on row 2 playing, so the ordinary
  failure path is unchanged; but with the counter read from inside the page, 7.8 reports `fails: 2`
  once Good is playing while 7.9 reports `fails: 0`. Pressing Next from there (one bad file, the
  chain wraps to Bad1) is the difference: 7.8 reaches 3 on that single file, announces "Stopped
  after 3 unplayable tracks." and stops playback over a playlist with one bad entry, while 7.9
  counts 1, walks past it through Bad2 and back to Good, and is playing row 2 with an empty status
  region. Nothing on screen changes: the harness reports what the 7.8 page reports - picking (3
  rows, an exact repeat leaves 2, a same-name file of a different size is a 3rd), the title after a
  row jump (`Beta`), row reuse (node identity across a jump, `cur` on row 1), the debounced settings
  write (`#gl` at 30 lands `glass:30` in `glass.settings.v1`), the back chain (`true`, `true`,
  `false`) and the flat and tilted `elementFromPoint` hit-tests (all six controls resolving to
  themselves) - and the change is script-only, with no markup, stylesheet, copy or timing touched.
  The page goes from 77,448 to 78,154 bytes.
- [x] 67. Three declarations are written twice, and the two reduced-motion blocks sit far apart.
  **Symptom.** `.bubble` declares `border-radius:50%` twice inside the same rule (once beside
  `width`/`height`, once after `color`), so an edit to the first is silently dead; `.blobs` is
  declared in two separate blocks a hundred lines apart (position/filter in one, the 4D `transform`
  in the other); and `prefers-reduced-motion` is handled by two blocks - one near the top that
  shortens every animation, one further down that stops the sway - which is exactly why item 65's
  two loops went unnoticed for so long. Nothing renders differently; the cost is that a rule that
  looks authoritative can be overridden by another block with no visible connection to it.
  **Fixed in 8.0.** All three are written once now, with no change to any computed value. The second
  `border-radius:50%` on `.bubble` is gone (same value, so the dead copy had nothing to say); the
  `.blobs` `transform` moved up into the block that already positions and filters it, so one rule
  describes the whole layer; and the second `prefers-reduced-motion` block was folded into the first,
  the 4D `transform`/`animation` resets sitting beside the loop resets they belong with. Every rule
  in that block is `!important` and overrides the same non-important declarations it overrode from
  further down the sheet, so the cascade order it relies on is untouched - the sheet goes from 117
  top-level rules to 115, and from two reduced-motion blocks to one. Verified the way item 65 was:
  the computed-style/rect hash over all 150 elements is unchanged in both modes (default player
  `86fd53a3`, sheet `c4990f92`; reduced motion `6696717e`, sheet `9156c74b`, each stable across
  repeats once the depth inputs are pinned and the audio clock is stopped), and the rendered
  412x892 frame at deviceScaleFactor 2 is byte-identical to 7.9 (same SHA-256, stable across
  repeats) in reduced-motion mode, the mode this item touches. The behaviour harness reports what
  the 7.9 page reports - picking (3 rows, an exact repeat leaves 3, a same-name file of a different
  size is a 4th), the title after a row jump (`Beta`, then `Gamma`), row reuse (node identity across
  a jump, `cur` and `aria-current` on the same row), the debounced settings write (`#gl` at 30 lands
  `"glass":30` in `glass.settings.v1`), the back chain (`true`, `true`, `false`) and the flat and
  tilted `elementFromPoint` hit-tests (all six controls resolving to themselves) - and the explicit
  probes read the same in both modes: `.blobs` still `fixed|inset 0|hidden`, `.bubble` still 50% on
  all four corners, the blob float still `float 14s` and the sway still `sway` in ordinary mode,
  both `none` with the equaliser held at 16px under reduced motion. Stylesheet-only: no markup,
  script, copy, layout or timing touched. The page goes from 78,154 to 78,651 bytes.

## Correctness after the sprint backlog

- [x] 68. The guard that tells a source swap's own pause apart from the listener's is thrown away
  when the page goes to the background, so a swap that was still settling when the app was
  backgrounded is painted as a stop on resume.
  **Symptom.** Press Next and let the app go to the background inside the 700 ms a new source takes
  to settle. The guard was a flag cleared by a timer, and the WebView's timers are frozen with the
  activity, so `visibilitychange` cleared the flag by hand on the way out instead. The pause the
  swap had already queued is then delivered on resume to an `au.onpause` that no longer sees a
  swap, and is taken for the listener's pause: the card paints the play icon over a track that is
  playing, `GP.setPlaying(false)` makes the shell abandon audio focus - so the next app that asks
  for it is not ducked - and the media notification says paused. Measured on 8.0 with the hide
  simulated 30 ms after Next and the queued pause dispatched after it: `playing` read `false` and
  `mediaSession.playbackState` read `paused` over a track that had started. The same pause with no
  hide in between was correctly ignored, which is what pinned the cause to the hide rather than to
  the window itself.
  **Fixed in 8.1.** The window is a timestamp read at the moment the pause arrives -
  `swapping()` compares `performance.now()` against the moment the source on deck was assigned, the
  way `REPEAT_MS` already decides a repeat tap - so nothing has to survive a freeze and the guard
  means the same thing on either side of one. `clearSwitching()` is now `swapAt=null` and the
  `visibilitychange` handler only drops `dragging`; no timer is left behind by a load at all, so
  the one pending-timer hazard the old comment describes is gone with it. Verified: the same probe
  reads `playing: true` and `playbackState: "playing"` after the fix, the control cases are
  unchanged (a late swap pause with no hide is still ignored, a pause the page asked for inside the
  window is still honoured, a pause after the window is still the listener's), the behaviour
  harness reports exactly what 8.0 reports - picking (2 rows, an exact repeat leaves 2, a same-name
  file of a different size is a 3rd), the title after a row jump, row reuse (node identity), the
  debounced settings write (`#gl` at 33 lands `glass:33`), the back chain (`true`, `true`, `false`)
  and the flat and tilted `elementFromPoint` hit-tests (all six controls resolving to themselves) -
  and the markup and stylesheet are byte-identical to 8.0, with the computed-style/rect hash of all
  118 elements in the player state unchanged (`8fdb8105` before and after, stable across repeats).
  The page goes from 78,651 to 79,039 bytes.
- [x] 69. `prefers-reduced-motion` is read once at startup, so a preference changed while the app is
  open never reaches the 4D engine.
  **Symptom.** `REDUCED` is a constant captured from `matchMedia` when the script first runs, and
  the stylesheet's own media block is re-evaluated live by the browser - so the two halves of
  reduced motion disagree the moment the setting changes. Turn "Remove animations" off while the
  player is open and the card stays flat and dead to the gyroscope, the finger and the pointer
  until the app is restarted, because every tilt handler still asks `motionOff()` and still gets
  the stale `true`; turn it on and the spring keeps running frames for a tilt that the stylesheet
  is now overriding. Measured on 8.0 with `matchMedia` stubbed and the preference flipped after
  load: a `pointermove` that moves `--mx` on a fresh load leaves it at `0` on a page that has seen
  the flip.
  **Fixed in 8.2.** The query is kept as a live `MediaQueryList` and the cached `REDUCED` is
  updated on its `change` event, with an `addListener` fallback for the older WebViews the API 24
  floor still admits, so `motionOff()` answers with what the stylesheet is already painting. The
  handler also settles the card the way `applyS()` does when a mode is picked, so a preference
  that turns motion off mid-tilt lands the same flat card the media block paints instead of a
  tilt frozen mid-spring. Verified: the same probe reads `--mx` `-0.5547` after a flip to
  no-preference (it read `0.0000` on 8.1) and returns to `-0.0008` - settled - when the
  preference is turned back on, while a `pointermove` under a preference that was on at load is
  still a no-op; the markup and stylesheet are byte-identical to 8.1, a side-by-side
  computed-style/rect snapshot of both builds in the same process shows 0 differences across all
  127-141 elements in five states (empty, picked, switched, sheet, tilted), and the behaviour
  harness reports exactly what 8.1 reports - picking (2 rows, an exact repeat leaves 2, a
  same-name file of a different size is a 3rd), the title after a row jump, row reuse (node
  identity), the debounced settings write (`#gl` at 33 lands `glass:33`), the back chain
  (`true`, `true`, `false`) and the flat and tilted `elementFromPoint` hit-tests (all six
  controls resolving to themselves). The page goes from 79,046 to 80,333 bytes.
- [x] 70. The settings writer exists twice, and only one copy clears the pending debounce.
  **Symptom.** `writeS()` and `saveS()` have the same body - `JSON.stringify(S)` into
  `glass.settings.v1` - and differ only in that `writeS()` also clears `saveTimer`. A change to the
  key or the serialisation has to be made in both or the debounced and the immediate path disagree
  about what a saved setting is, and a discrete toggle (`Reset defaults`, a motion mode, the drift
  switch) leaves the debounce a slider drag armed, so the write lands twice. `flushS()` already
  does exactly what `saveS()` should do.
  **Fixed in 8.3.** `writeS()` is the only place that names the key and serialises `S`; `saveS()` is
  now `if(saveTimer)clearTimeout(saveTimer);writeS()` - the immediate path cancels the pending
  debounce and then writes through the same function, so a toggle that follows a drag stores the
  drag's value once instead of once now and once 200 ms later with the same bytes. `saveSSoon()`
  and `flushS()` are unchanged. Verified: a probe that counts writes to `glass.settings.v1` reads
  1 write after a `#gl` input followed immediately by a drift toggle, where 8.2 read 2, and the
  stored value is the drag's `glass:33` in both; a drag on its own still coalesces (0 writes at
  60 ms, 1 at 200 ms, `glass:33`), a toggle with nothing pending still writes at once (1), and a
  `pagehide` flush still lands the pending value (1, `glass:40`). The markup and stylesheet are
  byte-identical to 8.2 (the 19,501 bytes before `<script>` compare equal), and the behaviour
  harness reports exactly what 8.2 reports - picking (2 rows, an exact repeat leaves 2, a
  same-name file of a different size is a 3rd), the title after a row jump, row reuse (node
  identity), the debounced settings write (`#gl` at 33 lands `glass:33`), the back chain
  (`true`, `true`, `false`) and the flat and tilted `elementFromPoint` hit-tests (all six
  controls resolving to themselves). The page goes from 80,333 to 80,917 bytes.

## Backlog added after the 70 items above were cleared (found 2026-11-15)

- [x] 71. A playlist whose files the platform cannot decode never stops walking it.
  **Symptom.** Pick three files the WebView cannot decode - a container it does not support, a
  truncated or protected file - into an empty player. Every failure walked the skip chain to the
  next source, and `au.onplay` cleared the budget on the way, because `play` fires as soon as
  `play()` is called: before a byte is loaded, and for a source that is about to fail as well. So
  `fails` could never reach `MAX_SKIP` and the chain never ended. Measured on 8.3: 19 errors in the
  first 1.5 s and 48 by three seconds, still climbing, one source swap per failure, with the CPU
  busy for as long as the app stayed open. Because each load cancelled the previous load's delayed
  paint, the card still read "No songs yet" with the empty-state row in the playlist over three
  files that were in `tracks`, and the transport said it was playing - the card's `playing` class
  and `aria-pressed="true"` - over silence.
  **Fixed in 8.4.** The budget is cleared on `playing` instead of `play`: `playing` is the element
  saying it has data and is actually advancing, which a source that only manages a `play` event
  before erroring never reaches. `au.onplay` keeps painting the transport, so the icon still flips
  on the tap rather than on the first decoded frame, and `au.onplaying` now owns the one line that
  clears the chain. Verified: the same three undecodable files produce 3 errors and then nothing -
  the count is identical at 1.5 s and at 3 s, where 8.3 read 19 and then 48 - and the run settles
  with `data-blocked="1"`, `playing` off, `aria-pressed="false"`, the status region reading
  "Stopped after 3 unplayable tracks. Can't play this file.", the card header and artist line
  naming the track it gave up on, and all three rows in the playlist; picking a good file
  afterwards still plays, clears `data-blocked` and the status message, and the markup and
  stylesheet are byte-identical to 8.3 (the 19,506 bytes before `<script>` compare equal). The
  behaviour harness reports the same picking (2 rows, an exact repeat leaves 2, a same-name file of
  a different size is a 3rd), the title after a row jump, row reuse (node identity), the debounced
  settings write (`#gl` at 33 lands `glass:33`), the back chain (`true`, `true`, `false`) and the
  flat and tilted `elementFromPoint` hit-tests (all six controls resolving to themselves), plus
  play, pause and next on a playlist that does play. The page goes from 80,917 to 81,848 bytes.
- [x] 72. `Reset defaults` leaves the Colour shift slider, and the hue it drives, where the drag
  left them.
  **Symptom.** Drag Colour shift to 100 degrees in the options sheet and press `Reset defaults`.
  The other six sliders snap back to their defaults and `glass.settings.v1` is rewritten with them,
  but the Colour shift slider keeps its value and its readout, and `--h` keeps the page hue - the
  blob field, the album art and the glass tint - shifted. The control has a well-defined default:
  `paintTrack()` puts it back to the current track's own hue on every track change, so the sheet is
  the only place it can be left off it. `resetBtn` resets `S` and then re-syncs `SL`, the seven
  sliders it owns, and the hue is not one of them.
  **Fixed in 8.5.** The four writes that move the hue - `--h` on `:root`, the slider's value, its
  readout and its `--p` fill - are one function, `setHue()`, which the drag, `paintTrack()` and
  `resetBtn` all go through, so the reset restores the same hue a track change would. The default
  is the current track's own hue, or, with nothing on the card, the value the control and `:root`
  start with, read from `EL.hu.defaultValue` rather than repeated as a literal. `--ah` stays where
  it was: the album art is painted from the track in `paintTrack()` and a drag never moved it.
  Verified: with Beta playing (hue 138) a drag of Colour shift to 100 then `Reset defaults` leaves
  `--h`, the slider, the readout and `--p` at 100 on 8.4 and at 138 on 8.5, and on an empty player
  the same drag resets to 250 instead of staying at 100; the stored blob is the defaults in both
  cases and a reset armed behind a slider drag still writes once, not twice. The markup and
  stylesheet are byte-identical to 8.4 (the 19,506 bytes before `<script>` compare equal) and the
  behaviour harness reports the same picking (2 rows, an exact repeat leaves 2, a same-name file of
  a different size is a 3rd), the title after a row jump, row reuse (node identity), the debounced
  settings write, the back chain (`true`, `true`, `false`), the flat and tilted `elementFromPoint`
  hit-tests (all six controls resolving to themselves) and play/pause on a playlist that plays. The
  page goes from 81,848 to 82,707 bytes.

- [x] 73. `prefers-reduced-motion` shortens every animation and leaves every transition at full
  length, so the one mode whose purpose is to remove motion still has plenty of it.
  **Symptom.** Turn "Remove animations" on and press play: the album art still springs from 0.84
  to 1 over 0.7 s, every chip, playlist row and pill still squishes over 0.45 s on a press, the
  drift switch's knob still slides over 0.35 s, the colour-shift slider's thumb still swells over
  0.3 s, and the whole backdrop still sweeps to the new hue over 1 s on a track change. Measured
  in headless Chromium with `--force-prefers-reduced-motion` on 8.5, the computed
  `transition-duration` of `.art`, `.it`, `.chip`, `.ctl.main`, `.pill`, `.seg button`, `.sw`,
  `.sw::after`, `.blobs`, `#seek` and `#hu` was identical to the page without the preference:
  the block's blanket rule names `animation-duration` only, and the block's own comment calls
  itself the whole of the reduced-motion story.
  **Fixed in 8.6.** The block now shortens `transition-duration` the same way it shortens
  `animation-duration`, on `*`, `*::before` and `*::after` - a pseudo-element is not matched by
  `*`, which is why the switch knob needed naming - plus the slider thumb, which is a vendor
  pseudo-element (`::-webkit-slider-thumb`) and so is not covered by `::before`/`::after` either.
  0.01 s rather than `none`: a transition still lands on its end state and still fires
  `transitionend`, so no control is left half-way, and nothing in the page listens for it. Verified
  by comparing the computed `transition-duration`, `transition-property`, `animation-duration`,
  `animation-name` and `transform` of twenty selectors on 8.5 and 8.6, with and without the
  preference: without it the two pages agree on every one of those properties (the only deltas are
  the sampled `transform` matrices of the idle sway and the spring, mid-frame); with it, all
  eleven transitions that had a duration go from 0.7/0.45/0.4/0.35/0.3/1 s to 0.01 s and nothing
  else moves - the animations, the transforms and the layout are byte for byte what 8.5 had. The
  behaviour harness reports the same picking (2 rows, an exact repeat leaves 2, a same-name file of
  a different size is a 3rd), the title after a row jump, row reuse (node identity), the debounced
  settings write, the back chain (`true`, `true`, `false`) and the flat and tilted
  `elementFromPoint` hit-tests (all six controls resolving to themselves), plus play, pause and
  next on a playlist that does play. Only the stylesheet changed: the `<script>` block is byte-
  identical to 8.5's (63,182 bytes). The page goes from 82,707 to 83,896 bytes.
- [x] 74. `data-blocked` carries three states for a boolean: absent, `0` and `1`.
  **Symptom.** `setBlocked()` writes the attribute only when the value changes and `blocked`
  starts `false`, so a page that has never refused a play has no `data-blocked` at all while one
  that refused and then recovered has `data-blocked="0"`. Measured on 8.5 in headless Chromium:
  `getAttribute('data-blocked')` is `null` on a fresh page and `"0"` after a refused play that
  then started. Nothing in the page reads the attribute - no rule matches it - so every reader,
  a future stylesheet rule or the behaviour harness, has to spell "absent or `0`" by hand, and a
  selector written as `[data-blocked="0"]` silently misses the fresh page.
  **Fixed in 8.7.** The attribute is written once at startup, before anything can refuse a play,
  so the only two values it ever holds are `"0"` and `"1"` and a `[data-blocked="0"]` selector
  matches a page that has never been blocked. `setBlocked()` is unchanged: it still writes only
  when the value changes, which is now a change between two present states rather than between
  absent and present. Nothing reads the attribute in the page and no rule matches it, so the
  markup and stylesheet are byte-identical to 8.6 (the 20,695 bytes before `<script>` compare
  equal). Verified on 8.6 and 8.7 side by side with the same harness: on 8.6
  `getAttribute('data-blocked')` is `null` on a fresh page and `app.matches('[data-blocked="0"]')`
  is false, on 8.7 both read `"0"`; a stubbed `NotAllowedError` from `play()` still takes it to
  `"1"` with the "Playback blocked" status message, and a play that then succeeds takes it back to
  `"0"`, on both builds. Everything else is unchanged on both: the behaviour harness reports the
  same picking (2 rows, an exact repeat leaves 2, a same-name file of a different size is a 3rd),
  the title after a row jump, row reuse (node identity), the debounced settings write, the back
  chain (`true`, `true`, `false`), play/pause toggling a playlist that plays, and the flat and
  tilted `elementFromPoint` hit-tests (all six controls resolving to themselves). The page goes
  from 83,896 to 84,639 bytes.
- [x] 75. The seek bar is dead while the clock runs whenever the media reports no usable duration.
  **Symptom.** `ontimeupdate` computes the position as `0` unless `duration` is finite and
  positive, but writes `#cur` and the slider's `aria-valuetext` from `currentTime` regardless, so
  a source whose duration reads `Infinity` (some VBR MP3s and streams do until their header is
  reparsed) shows a bar pinned at the far left under a clock that is counting. Measured on 8.5 in
  headless Chromium with `duration = Infinity` and `currentTime = 95`: `#dur` correctly reads
  `--:--`, but `#cur` reads `1:35`, the slider's `value` is `0`, its `--p` fill is `0%` and its
  `aria-valuetext` reads `1:35` - the bar never moves, and the control announces a position it
  does not hold.
  **Fixed in 8.8.** `setSeekAvail()` is the one place the control's availability is decided, and it
  is driven by the same `isFinite(d)&&d>0` test the position maths already uses: while the duration
  is unusable the slider carries `aria-disabled="true"` and no `aria-valuetext` at all, so it no
  longer announces a position it does not hold, while `#cur` keeps counting exactly as before and
  the bar keeps its `0` value and `0%` fill. The moment a usable duration arrives - `showDur()` on
  `loadedmetadata`/`durationchange`, or the next `timeupdate` - the attribute is removed and the
  real position is announced again, and `resetSeek()` re-applies the state for a new or emptied
  playlist so a fresh page starts `"true"` rather than `null`. The drag handler skips its
  `aria-valuetext` write under the same condition, since with no duration there is no position to
  convert into. Attributes only: the markup and stylesheet are byte-identical to 8.7 (the 20,690
  bytes before `<script>` compare equal). Verified on 8.7 and 8.8 side by side in headless
  Chromium with `duration = Infinity` and `currentTime = 95`: on 8.7 the slider's `aria-valuetext`
  reads `1:35` and `aria-disabled` is `null`, on 8.8 `aria-valuetext` is absent and
  `aria-disabled` is `"true"`, on both `#cur` reads `1:35`, `#dur` reads `--:--`, `value` is `0`
  and `--p` is `0%`; giving the same page a 240 s duration puts `value` at `39.6`, `--p` at
  `39.583%`, `aria-valuetext` at `1:35` and `#dur` at `4:00` on both, and a `NaN` duration takes
  8.8 back to absent/`"true"` where 8.7 kept the stale `1:35`. Everything else is unchanged on
  both: the behaviour harness reports the same picking (2 rows, an exact repeat leaves 2, a
  same-name file of a different size is a 3rd), the title after a row jump, row reuse (node
  identity), the debounced settings write, the back chain (`true`, `true`, `false`) and the flat
  and tilted `elementFromPoint` hit-tests (all six controls resolving to themselves). The page goes
  from 84,639 to 86,427 bytes.

## Backlog added after the 75 items above were cleared (found 2026-11-16)

- [x] 76. A `play()` the page itself superseded is reported as "Can't play this file".
  **Symptom.** `playRefused()` classifies every rejection of `au.play()` except `NotAllowedError`
  and `SecurityError` as a failure of the file, and sets `data-blocked`, clears the swap window and
  announces `ERR_PLAY` in the status region. But the element also rejects a `play()` with
  `AbortError` whenever the request is superseded - "interrupted by a new load request" or "by a
  call to pause()" - and both of those are things this page does on every track change and every
  deliberate pause. Measured on 8.8 in headless Chromium with three 60 s tracks picked and playing,
  then three `#next` taps 120 ms apart: three rejections, all `AbortError: The play() request was
  interrupted by a new load request`; `#app` went `data-blocked="0"` -> `"1"` and the `#live`
  status region read "Can't play this file" while the player was playing - `playing` true,
  `au.paused` false, `#pp` `aria-pressed="true"` - and 2.5 s later it still read `"1"` and the same
  sentence, because only a later play that settles successfully clears it. The same rejection also
  ran `clearSwitching()`, so `swapAt` was `null` 150 ms after a tap that had just armed the 700 ms
  window the new source needs: the pause that swap queues is no longer recognised as the swap's and
  is painted as the listener's stop. Two more paths reach the same place: the `pagehide` teardown
  (`releaseUrls()` pauses the element, so the pending autoplay rejects) left `data-blocked="1"` on
  the way out, and walking past one undecodable file lets the superseded attempt's late
  `NotSupportedError` announce "Can't play this file" over the good track the skip chain moved to.
  **Fix.** `playRefused()` returns immediately on `AbortError` - a play the page itself interrupted
  is not a failure of the file, so it must not set `data-blocked`, must not announce, must not drop
  the swap window and must not paint a stop. Each attempt also carries a token, and a rejection
  from an attempt that is no longer the current one is dropped, so a stale failure cannot talk over
  the track that replaced it. Nothing on screen changes: `data-blocked` is read by no selector and
  the status region is off-screen, so the only difference is that the false report stops.
  **Fixed in 8.9.** `playRefused()` now returns before anything else when the rejection is an
  `AbortError`, and each `attemptPlay()` carries a token (`playToken`) that a rejection must still
  match to be reported at all, so an attempt whose source has already been replaced cannot speak
  for the track that replaced it. Verified on 8.8 and 8.9 side by side with the same harness: three
  `#next` taps 120 ms apart on a playing playlist still produce the same three `AbortError`
  rejections on both builds, but on 8.8 `data-blocked` reads `"1"` and `#live` "Can't play this
  file" over a player that is playing (`playing` true, `au.paused` false, `aria-pressed` `"true"`),
  still `"1"` 2.5 s later, and `swapAt` is `null` 150 ms after the tap; on 8.9 `data-blocked` stays
  `"0"`, `#live` stays empty, and `swapAt` still holds the window the load armed
  (`swapping()` true). The `pagehide` teardown that pauses the element no longer leaves
  `data-blocked="1"` on the way out, and one undecodable file in a three-track list is now skipped
  silently - measured with a dispatched `error`: on 8.8 `#live` reads "Can't play this file" over
  the good track the chain walked to (`blocked` `"1"`), on 8.9 `blocked` stays `"0"` and `#live`
  empty. The give-up path is unchanged on both: three consecutive `error` events still end at
  `blocked` `"1"`, `stopped` true, `fails` 1, `idx` 0 and "Stopped after 3 unplayable tracks. Can't
  play this file." on 8.8 and 8.9 alike. Everything else is unchanged on both: the behaviour
  harness reports the same picking (2 rows, an exact repeat leaves 2, a same-name file of a
  different size is a 3rd), the title after a row jump, row reuse (node identity), the debounced
  settings write, the back chain (`true`, `true`, `false`), play/pause toggling a playlist that
  plays, and the flat and tilted `elementFromPoint` hit-tests (all six controls resolving to
  themselves). Markup and stylesheet are byte-identical to 8.8 - the 20,695 bytes before `<script>`
  compare equal. The page goes from 86,433 to 87,963 bytes.

- [x] 77. The seek drag writes the three nodes `tuCache` mirrors and never invalidates the cache.
  **Symptom.** `tuCache` exists so `ontimeupdate` can skip writes it has already made, and its
  comment states the rule every writer must follow: "reset whenever we write these nodes
  elsewhere". `resetSeek()` follows it. `sk.oninput` does not: it writes `#seek`'s `--p` and
  `value` and `#cur`, and leaves the cache holding the pre-drag values, so the next tick compares
  against a value the DOM no longer has and skips the write it owes. With a usable duration the
  drag's own seek masks it; with none it does not. Measured on 8.8 in headless Chromium with the
  media reporting `duration = Infinity` (the VBR/stream case item 75 is about), where the bar is
  correctly `aria-disabled="true"` with no `aria-valuetext`: dragging it to 70 left `--p` at `70%`
  and `value` at `70`, and 600 ms of ticks later it was still `70%`/`70` under a clock counting
  from a position the bar does not hold, because every tick computed `0%` and found `0%` already in
  the cache. Item 75 established that in that state the bar holds `0` and a `0%` fill.
  **Fix.** Invalidate `tuCache.p`, `tuCache.v` and `tuCache.cur` in the drag handler, so the next
  tick re-owns the bar. With a usable duration the values the tick then writes are the ones the
  drag already wrote, so nothing visible changes.
  **Done (9.0).** `sk.oninput` now ends by clearing `tuCache.p`, `tuCache.v` and `tuCache.cur`, the
  way `resetSeek()` already did. Measured on 8.9 and 9.0 in headless Chromium with `duration`
  stubbed to `Infinity` and `currentTime` counting: on both, the bar seeds at `--p` `0%` / `value`
  `0` with `aria-disabled="true"`, no `aria-valuetext` and `#dur` `--:--`, and a drag to 70 moves it
  to `70%`/`70`; six further ticks leave 8.9 at `70%`/`70` and return 9.0 to `0%`/`0`, while `#cur`
  keeps counting to `11:40` on both. With a usable 300 s duration the drag seeks (`currentTime`
  150) and the next tick rewrites exactly the drag's own `50%`/`50` on both, so nothing visible
  changes. Everything else is unchanged on both: picking (2 rows, an exact repeat leaves 2, a
  same-name file of a different size is a 3rd), the title after a row jump, row reuse (node
  identity), the debounced settings write, the back chain (`true`, `true`, `false`), and the flat
  and tilted `elementFromPoint` hit-tests (all six controls resolving to themselves). Markup and
  stylesheet are byte-identical to 8.9 - the 20,690 bytes before `<script>` compare equal. The page
  goes from 87,963 to 88,488 bytes.

- [x] 78. A restored setting is checked against its slider's min/max but not its step.
  **Symptom.** The settings guard accepts a stored value when it is a finite number inside `LIM`,
  and `syncSlider()` then writes the thumb from `S` and the readout from `S`, while the slider's own
  `input` handler writes the readout from `el.value`. A stored value off the control's `step`
  therefore lands in four spellings at once. Measured on 8.8 in headless Chromium with
  `glass.settings.v1` holding `glass: 2.5` (inside `LIM` `[2,50]`, and the only writer this page
  has produces integers): `S.glass` 2.5, `#gl.value` `"3"` because the range snaps to its step,
  `#glV` `"2.5%"` and `--glass` `0.025` - the thumb, the label and the card disagree, and the next
  discrete toggle writes 2.5 back to storage so it survives.
  **Fix.** Accept a restored value only when it is also on the slider's `step` grid (or snap it to
  that grid), so the thumb, the readout and the custom property cannot disagree.
  **Done (9.1).** The restore now snaps an accepted value onto the grid the control itself uses
  (`onGrid()`: step base is the slider's `min`, an absent `step` attribute means the range default
  of 1, `step="any"` is left alone, and the result is clamped to `max`). Measured on 9.0 and 9.1 in
  headless Chromium with `glass.settings.v1` holding `glass: 2.5`: on 9.0 `S.glass` 2.5 with
  `#gl.value` `"3"`, `#glV` `"2.5%"` and `--glass` `0.025`; on 9.1 all four read 3 - `"3"`, `"3%"`,
  `0.03`, and the fill `2.083%` the thumb sits at - and a discrete write stores `"glass":3` instead
  of 2.5. A stored blob of values the page itself writes (`glass` 22, `blur` 40, `depth` 70, `tilt`
  120, `par` 90, `glow` 80, `shadow` 60, `drift` 0, `motion` `touch`) restores exactly on both, so
  an ordinary session is unchanged, and the junk blob (`"abc"`, `-5`, `null`, `1e9`, `"nope"`) still
  falls back to the defaults on both. Everything else is unchanged on both: the behaviour harness
  reports the same picking (2 rows, an exact repeat leaves 2, a same-name file of a different size
  is a 3rd), the title after a row jump, row reuse (node identity), the debounced settings write,
  the back chain (`true`, `true`, `false`), and the flat and tilted `elementFromPoint` hit-tests
  (all six controls resolving to themselves). Markup and stylesheet are byte-identical to 9.0 - the
  20,695 bytes before `<script>` compare equal. The page goes from 88,488 to 89,857 bytes.

- [x] 79. The Colour shift slider's track shows no fill at all on a fresh launch, under a thumb
  that sits two thirds of the way along it.
  **Symptom.** Open the options sheet on a page that has not had a song added yet. Transparency,
  Blur, Depth, Tilt, Parallax, Light and Shadow all draw their white fill up to the thumb; Colour
  shift draws none, while its thumb sits at 250 of 360 and its readout says "250°". The fill and
  the thumb are two readings of one value and they disagree by 69% of the track. `setHue()` is the
  only writer of that control's four values - `--h`, the slider's value, its readout and its `--p`
  fill - and nothing calls it on a fresh page: `paintTrack()` needs a track, the drag and `Reset
  defaults` need a gesture, and the hue is not one of the seven sliders in `SL` that `syncSlider()`
  seeds at startup. So `input[type=range]`'s `var(--p,0%)` fallback drew the track empty.
  **Fix.** Seed the group from the control's own value once at startup, through the one function
  that writes it, so the fill, the thumb and the readout agree from the first frame.
  **Done (9.2).** `if(EL.hu)setHue(HUE0);` now runs where the hue's drag handler is bound, so the
  fill is written before the sheet can be opened. Measured in headless Chromium on a fresh page:
  9.1 carries no `--p` on `#hu` and computes `linear-gradient(90deg,#fff 0%,rgba(255,255,255,.25)
  0%)` - no fill - against `value` 250, readout "250°", `aria-valuetext` "250 degrees" and a thumb
  at 69.4%; 9.2 carries `--p` `69.44444444444444%` and computes the same gradient at
  `69.4444%`/`69.4444%`, the fill the seven other sliders already had, the fill screenshot 03 of
  the sheet documents at 265°, and the fill a `Reset defaults` on the same empty player already
  painted. Nothing else moves: a full state snapshot of both builds - every slider's value, `--p`,
  readout and `aria-valuetext`, the seek bar's, `--glass`/`--blur`/`--h`/`--tiltk`/`--park`/`--mx`/
  `--my`/`--lx`/`--ly`/`--glow`, the app's class and `data-blocked`, the transport's `aria-pressed`,
  the sheet's `aria-expanded`, the drift switch, the three motion buttons, the title, artist, clock
  and playlist, and the 22 element rects - differs in exactly the two hue-fill entries. At
  420x900 with animations and transitions frozen and `--mx`/`--my` pinned, the now-playing card is
  pixel-identical (0 of 378,000 pixels differ) and the open sheet differs only inside
  (40,367)-(262,375): 1,774 pixels, all of them on the hue track's own 8px band. Markup and
  stylesheet are byte-identical to 9.1 - the 20,690 bytes before `<script>` compare equal - and
  the behaviour harness reports the same picking (2 rows, an exact repeat leaves 2, a same-name
  file of a different size is a 3rd), the title after a row jump, row reuse (node identity), the
  debounced settings write (`#gl` at 40 lands `glass:40` and `--glass` 0.4), the back chain
  (`true`, `true`, `false`) and the flat and tilted `elementFromPoint` hit-tests (all six controls
  resolving to themselves), with no console error. The page goes from 89,857 to 91,023 bytes.

- [x] 80. `setHue()` derives the slider's fill from a literal 0-360 domain instead of the control's
  own range.
  **Symptom.** Every other slider's fill is `(value - min) / (max - min)` - `syncSlider()` and each
  `input` handler both spell it that way - but the hue's is `h / 360 * 100`, a second copy of the
  rule that knows the markup's `min="0" max="360"` only as a literal. The fill and the thumb agree
  today because that is what the markup happens to say; move either end of the range and the fill
  silently stops following the thumb, which is the same disagreement item 79 just fixed at
  startup, one markup change away from coming back.
  **Fix.** One fill helper, used by `syncSlider()`, the slider `input` handlers and `setHue()`, that
  reads the control's own `min`/`max`, so there is one spelling of the rule rather than two.

## Backlog added after the 80 items above were cleared (found 2026-11-17)

- [x] 81. `applyS()` rewrites the idle-drift switch on every slider-drag frame, for a mode no slider
  can change.
  **Symptom.** Item 51 guarded the motion segment in this function - the three buttons are now
  repainted only when the mode they show has moved - and the switch beside them was left out. A
  slider cannot change the drift mode, but every `input` event of every drag still ran
  `driftEl.classList.toggle('off',!S.drift)` and rewrote `#driftBtn`'s `aria-pressed` with the value
  it already held. Measured on 9.3 in headless Chromium with `setAttribute` and
  `DOMTokenList.toggle` counted: a thirty-event drag of the Shadow slider (100 -> 129, one event per
  frame) produced 30 `aria-pressed` writes and 30 `off` toggles, and a ten-event drag with the drift
  already off produced 10 of each; `Reset defaults`, which normally leaves the drift exactly where
  it was, wrote both again. Two DOM writes and a class-list toggle per frame of every settings drag,
  for a control the drag never touches.
  **Fix.** Remember the drift mode last painted, the way `segMode` beside it remembers the motion
  segment, and write the class and the attribute only when it has moved.
  **Done (9.4).** `applyS()` now compares `S.drift` against `pdrift` (undefined until the first
  call, so the first paint always writes) and only then toggles `off` and writes `aria-pressed`.
  Nothing else in the page touches either one - the only other handle on the wrapper, `syncBlobs()`,
  writes `paused` - so the cache cannot go stale. The same instrumented drag on 9.4 reports 0
  `aria-pressed` writes and 0 `off` toggles, and the ten-event drag with the drift off reports 0 and
  0; the switch itself is unchanged in every state (off: `aria-pressed` "false", `off` present,
  computed `animation-name` `none`; on: "true", no `off`, `sway`; after `Reset defaults`: on), the
  stored blob is byte-identical, and the page is pixel-identical at 420x900 with animations and
  transitions frozen and `--mx`/`--my` pinned (0 of 378,000 pixels differ, against a 0-pixel
  run-to-run noise floor). Markup and stylesheet are byte-identical to 9.3 - the 20,690 bytes before
  `<script>` compare equal - and the behaviour harness reports the same picking (3 rows, an exact
  repeat leaves 3, a same-name file of a different size is a 4th), the same title after a row jump,
  row reuse by node identity, the debounced settings write (`#gl` at 40 lands `glass:40` and
  `--glass` 0.4), the back chain (`true`, `true`, `false`) and the flat and tilted
  `elementFromPoint` hit-tests (all six controls resolving to themselves), with no console error.
  The page goes from 91,458 to 92,510 bytes.

- [x] 82. `setHue()` writes all four of its values on every call, so `Reset defaults` repaints a hue
  that is already on the card.
  **Symptom.** `setHue()` writes `--h`, the slider's `value`, its readout and its `--p` fill
  unconditionally, and its one caller that is not a drag is `Reset defaults` - which passes the
  current track's own hue, the value the control is already showing - and the skip chain's give-up
  branch, which repaints the track that is already on the card. Measured on 9.3 with every
  `setProperty` and `textContent` write logged: pressing `Reset defaults` on a playing track wrote
  `--h` 138, the readout "138°" and `--p` 38.333333333333336% again, four writes that changed
  nothing, while `setVT()` beside them already skips its identical write. The whole reset is 19
  writes; four of them are the hue control restating itself.
  **Fix.** Compare each of the four against the last value written and skip the ones that have not
  moved, the same way `setVT()`, `paint()` and `applyS()` already do - `paintTrack()` on a real
  track change still writes all four, because there the hue really moves.

  **Done (9.5).** `setHue()` compares the hue against `pHue`, the value it last painted - undefined
  until the first call, so the startup seeding still writes everything - and returns before writing
  anything when it has not moved. All four values it writes are pure functions of that one hue and
  nothing else in the page touches `--h`, `#hu`'s `value`, its readout or its `--p` fill, so a
  single remembered value cannot go stale the way four independent ones could. Instrumented on 9.4
  and 9.5 in headless Chromium, counting `setProperty`, the `value` setter, `textContent` and
  `aria-valuetext`: `Reset defaults` on a track whose hue is already on the card went from four
  writes (`--h`, value, readout, `--p`) to zero, while a real track change (hue 0 -> 53) still
  writes all four and a drag to 100 still writes all four, and the control ends in the same state
  in every case (`value`, readout, `aria-valuetext`, `--h`, `--p` 14.722222222222223% at 53 and
  27.77777777777778% at 100 on both builds). Markup and stylesheet are byte-identical to 9.4 - the
  20,690 bytes before `<script>` compare equal - and the page is pixel-identical at 420x900 with
  animations and transitions frozen and `--mx`/`--my` pinned, on both the player (0 of 378,000
  pixels differ) and the open options sheet (0 of 378,000). The behaviour harness reports the same
  picking (3 rows, an exact repeat leaves 3, a same-name file of a different size is a 4th), the
  same title after a row jump, row reuse by node identity, the debounced settings write (`#gl` at
  40 lands `glass:40` and `--glass` 0.4), the back chain (`true`, `true`, `false`) and the flat and
  tilted `elementFromPoint` hit-tests (all six controls resolving to themselves), with no console
  error. The page goes from 92,518 to 93,189 bytes.

- [ ] 83. `LIM` is a second copy of the eight sliders' own ranges, so one markup edit re-opens the
  restore disagreement items 77 and 78 closed.
  **Symptom.** A stored setting is accepted only if it sits inside `LIM[key]`, a hand-written table
  of `[min,max]` pairs that restates what each range input already declares in its markup - the
  same duplication item 80 removed from `setHue()`, where a literal 0-360 stood in for the
  control's own range. `onGrid()` a few lines below already reaches the control through `SLIDERS`
  and reads `el.min`/`el.max` from it, so the restore consults the markup and the table in turn.
  Demonstrated on 9.3 by changing one attribute: with `#dp`'s `min` moved from 0 to 10 and a stored
  `depth` of 5, `LIM.depth` still says `[0,100]`, so the value is accepted and snapped against the
  control's own grid - the readout reads "5%", `aria-valuetext` "5%", the fill computes to
  -5.555555555555555%, and the thumb sits at 10, the value the control can actually express. That is
  the `S`-versus-thumb disagreement item 78 fixed, reachable by editing one attribute in the markup
  and leaving the table behind.
  **Fix.** Derive the accepted range from the control itself - `+el.min` and `+el.max` through the
  same `SLIDERS` row `onGrid()` already uses - and delete `LIM`, so there is one spelling of every
  range. Every value the current table accepts and the markup agrees with keeps restoring exactly as
  it does today.
