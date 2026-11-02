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
- [ ] 11. `au.play()` rejections are swallowed by `.catch(()=>{})`. Surface a non-intrusive state so
  the user knows playback was blocked rather than broken.
- [ ] 12. The "skip unplayable track" path can walk the whole playlist on a run of bad files. Cap
  the attempts and stop with a clear message.
- [ ] 13. A cancelled or empty file-picker result should leave the playlist untouched (verify, and
  fix if not).
- [ ] 14. Track hues from the golden-angle step can exceed 359 on long lists; clamp to 0–359.
- [ ] 15. `seek` input should be clamped to `[0, duration]` before assignment.
- [ ] 16. `deviceorientation` can deliver null `beta`/`gamma`; the guard exists but confirm it also
  handles the first event having only `alpha`.
- [ ] 17. Rapid taps on a playlist row can call `load()` twice for the same track; make it
  idempotent.
- [ ] 18. Rotation/resize can leave the card mid-tilt; re-measure and reset the tilt target.

## Memory and battery

- [ ] 19. Object URLs from the file picker are never revoked. Revoke a track's URL when the page is
  torn down, and on `pagehide`.
- [ ] 20. The `requestAnimationFrame` loop should stop when the document is hidden and restart on
  visibility.
- [ ] 21. Media session metadata is left behind when the player closes; clear it.

## Accessibility

- [ ] 22. Announce track changes with `aria-live="polite"` on the title block.
- [ ] 23. Give every range input an `aria-valuetext` describing its unit.
- [ ] 24. Ensure keyboard focus is visible on buttons (currently only `:active` is styled).
- [ ] 25. Give the playlist `role="list"` and its rows `role="listitem"`.

## Code quality

- [ ] 26. `synth()` builds a WAV for demo tracks that no real code path uses; confirm it is
  unreachable and remove it, or document why it stays.
- [ ] 27. Add a short architecture comment at the top of the script (state, render, audio, depth).
- [ ] 28. Unify the error strings ("Can't play this file", "Skipping…") into one helper.

## Android shell (`android/app/src/main/java/.../MainActivity.java`)

- [ ] 29. Request and abandon audio focus so other apps duck correctly.
- [ ] 30. Handle `onTrimMemory` by releasing the WebView's cache.
- [ ] 31. Guard against a WebView that fails to instantiate, with a plain-text fallback screen.
- [ ] 32. Confirm the file chooser returns `null` cleanly when the user backs out.
