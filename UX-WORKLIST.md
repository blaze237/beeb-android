# Beeb Android TV — UX work list

Read-only review, no app code changed. Ordered by user impact.
Each item has a concrete TV acceptance check.

## P0 — fix first

### 1. Home banner rebuilds the whole screen every 8s
Source: `MainActivity` rotate timer calls full `renderHome()` (`reset()` + `removeAllViews()`).
Result: scroll position and focus are lost while browsing. The `!body.hasFocus()` guard checks the container, which rarely holds focus itself.
Work: pause rotation when any Home child has focus. Rotate only the banner content, never the full screen.
Accept: on Home with focus on any row card, wait 30s. Banner must not reset. Scroll and focus must stay put. Left/Right on a focused banner still changes feature.

### 2. Playback failure is a dead end
Source: `PlaybackActivity` lookup and `onPlayerError` paths show a technical string with no Retry.
Work: add Retry + Back actions on both paths. Keep the UHD-to-HD fallback. Use plain language for common cases (offline, restricted network, decoder timeout).
Accept: with network off, start playback. Error card offers Retry and Back. Turn network on, press Retry, playback starts. Force a UHD failure on a non-UHD device: banner shows trying-HD state, then HD plays.

### 3. Episode progress is invisible
Source: `MainActivity.card()` shows title/subtitle only. Continue Watching has no bar. Episode dialog Play button hides the position.
Work: add a progress bar + remaining time to Continue Watching cards, episode cards with saved progress, and the dialog (for example Resume 23:14 left).
Accept: watch half an episode, go Home. Card shows a half-full bar and remaining time. Reopen the dialog: Resume shows the same position.

### 4. One Continue Watching entry per show
Source: `LocalStore.update()` filters by `showId`.
Work: keep one entry per episode, or per episode plus per-show grouping. Decide before building.
Accept: watch S1 E1 halfway, then S1 E2 halfway. Both remain resumable from Continue Watching.

### 5. No discoverable way to remove history
Source: long-press only, no hint, no dialog action.
Work: add a visible Remove action in the episode dialog and/or a Manage mode on Continue Watching.
Accept: a first-time remote user can find and remove an entry without being told about long-press.

### 6. Search results are hard to reach by remote
Source: `MainActivity.search()` keeps focus in the field. `More results` view is removed and re-added, dropping focus.
Work: make Down move cleanly from field to first result. Preserve focus across More-results appends. Keep the heard-text confirmation for voice.
Accept: type three letters with the TV keyboard, press Down once, focus lands on the first result. Press More results: list grows, focus stays on a sensible card.

### 7. Temporary rewind captions have no persistent state
Source: one Toast in `NativePlayerExtras.replayNotice()`, nothing after.
Work: add a small persistent indicator while temp captions are active. Clear it on restore or manual change.
Accept: rewind with captions Off and auto-captions On. Indicator appears and stays until playback passes the pre-rewind point. Manual subtitle change clears it.

## P1 — high value

### 8. OK on player metadata opens Details instead of pausing
Source: `NativePlayerExtras` metadata is focusable; `centerShouldToggle()` returns false there.
Work: remove metadata from the OK-toggles-pause path confusion. Either make metadata non-focusable during playback or move Details behind an explicit button.
Accept: pressing OK with transport focused always toggles play/pause. Details opens only via its own explicit action.

### 9. Skip intro/recap is easy to miss
Work: add an accessibility announcement and a brief visible emphasis when the button appears. Do not steal focus.
Accept: with TalkBack on, Skip intro is announced when it appears. Focus stays where the user left it.

### 10. Up Next window is too easy to miss
Source: `nextButton` shows only in the last 30s.
Work: add a post-credits Up Next offer or short autoplay countdown. Keep manual Back working.
Accept: let an episode end. Without pressing anything during the last 30s, the user still gets a clear next-episode action after playback ends.

### 11. A–Z is 27 D-pad stops
Work: add jump-to-letter, section shortcuts, or wrap-around plus a faster path.
Accept: from Browse, reach letter S in under five key presses.

### 12. Categories failure has no Retry
Work: match the `load()` pattern used elsewhere: message plus Retry button.
Accept: with categories endpoint blocked, Browse shows the error plus Retry. Unblock, press Retry, categories load.

### 13. Catalogue refreshes lose scroll
Source: UHD catalogue and search rebuild the full screen on refresh completion.
Work: update in place. Keep scroll and focus. Show a non-destructive updated stamp.
Accept: scroll halfway down UHD, trigger Refresh catalogue. List updates, position and focus stay.

### 14. Episode dialog gaps
Work: add UHD/HDR badge, series position (S1 E3), scroll hint for long text, plus Save and Remove-from-history actions.
Accept: on a UHD S1 E3 with a long synopsis, the dialog shows the badge, position, a scroll cue, and both Save and history actions.

### 15. Bookmark is icon-only
Work: add a text label or a focused-state label next to the icon.
Accept: a sighted user can tell saved versus unsaved state and what OK will do, without hover or audio.

## P2 — polish and trust

### 16. Live tab is a flat grid
Work: add now/next titles and progress where the API supplies them, plus channel numbers or last-channel memory.
Accept: open Live. Each card shows what is on now and what is next during the current slot.

### 17. Live refresh stops when drilled in
Source: `liveRefresh` only runs when `backStack.isEmpty()`.
Work: keep periodic refresh alive or mark cards stale on return.
Accept: open a channel page from Live, wait over 60s, go Back. Cards refresh rather than showing stale now-playing art.

### 18. Settings UHD action never changes
Work: when configured, label it Reconnect or Refresh UHD setup. Keep connected state prominent.
Accept: with a working identity, Settings shows connected state plus a reconnect action, not a first-time enable prompt.

### 19. Caption toggle feedback can silently fail
Source: rewrites `(content.findFocus() as? TextView)?.text`.
Work: update the row by identity, not by current focus. Confirm with a brief label change.
Accept: toggle captions on rewind with focus anywhere on the row. The row text always reflects the new state.

### 20. Images flash, blank, and refetch
Source: `Images` has memory cache only, no placeholder, error art, or disk cache.
Work: add a stable placeholder, a failure tile with Retry on focus, and disk caching.
Accept: scroll rows fast on slow Wi-Fi. No card shows another programme's art. Failed art shows a clear tile, not an empty box.

### 21. Errors speak developer language
Work: map common failures (offline, HTTP blocked, empty catalogue, decoder timeout) to plain sentences with one action each.
Accept: each mapped case shows one sentence plus one button (Retry or Back). Raw codes never appear alone.

### 22. Home offline looks live
Source: `BbcApi.home()` falls back to cache silently.
Work: stamp cached Home with last-updated time and an offline note.
Accept: with network off, Home loads cache and says it is offline plus when it was last updated.

### 23. No first-run guidance
Work: add a one-time Setup hint card pointing at Settings UHD and Search.
Accept: fresh install shows one dismissible hint. It never appears again after dismissal.

### 24. No user data controls
Work: add Clear Continue Watching, Clear Saved, and Clear image cache to Settings with confirms.
Accept: each action asks for confirmation, completes, and the matching screen empties immediately.

### 25. Focus ring is thin at TV distance
Work: thicken the focused stroke and verify muted-grey contrast on the background at 10-foot distance.
Accept: side-by-side check at 1080p and 4K: focused versus unfocused states are obvious from the sofa.

## Out of scope for this list
Sustained-playback soak, physical 4K HLG output proofs, launcher-specific home-row placement, and microphone transcription accuracy. These need device time and are tracked in `VALIDATION.md`.
